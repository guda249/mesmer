package io.scalac.mesmer.extension

import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.util.Timeout
import akka.{ actor => classic }
import io.scalac.mesmer.core.akka.actorPathPartialOrdering
import io.scalac.mesmer.core.model.{ ActorKey, ActorRefDetails, Node, Tag }
import io.scalac.mesmer.core.util.{ ActorCellOps, ActorPathOps, ActorRefOps }
import io.scalac.mesmer.extension.ActorEventsMonitorActor._
import io.scalac.mesmer.extension.actor.{ ActorCellDecorator, ActorMetrics, MetricStorageFactory }
import io.scalac.mesmer.extension.metric.ActorMetricsMonitor
import io.scalac.mesmer.extension.metric.ActorMetricsMonitor.Labels
import io.scalac.mesmer.extension.metric.MetricObserver.Result
import io.scalac.mesmer.extension.service.ActorTreeService.Command.{ GetActorTree, TagSubscribe }
import io.scalac.mesmer.extension.service.{ actorTreeServiceKey, ActorTreeService }
import io.scalac.mesmer.extension.util.Tree.TreeOrdering._
import io.scalac.mesmer.extension.util.Tree.{ Tree, _ }
import io.scalac.mesmer.extension.util.{ GenericBehaviors, Tree, TreeF }
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object ActorEventsMonitorActor {

  sealed trait Command
  private[ActorEventsMonitorActor] final case object StartActorsMeasurement                       extends Command
  private[ActorEventsMonitorActor] final case class MeasureActorTree(refs: Tree[ActorRefDetails]) extends Command
  private[ActorEventsMonitorActor] final case class ActorTerminateEvent(ref: ActorRefDetails)     extends Command
  private[ActorEventsMonitorActor] final case class ServiceListing(listing: Listing)              extends Command

  def apply(
    actorMonitor: ActorMetricsMonitor,
    node: Option[Node],
    pingOffset: FiniteDuration,
    storageFactory: MetricStorageFactory[ActorKey],
    actorMetricsReader: ActorMetricsReader = ReflectiveActorMetricsReader
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      GenericBehaviors
        .waitForService(actorTreeServiceKey) { ref =>
          Behaviors.withTimers[Command] { scheduler =>
            new ActorEventsMonitorActor(
              ctx,
              actorMonitor,
              node,
              pingOffset,
              storageFactory,
              scheduler,
              actorMetricsReader
            ).start(ref)
          }
        }
    }

  /**
   * This trait is not side-effect free - aggregation of metrics depend
   * on this to report metrics that changed only from last read - this is required
   * to account for disappearing actors
   */
  trait ActorMetricsReader {
    def read(actor: classic.ActorRef): Option[ActorMetrics]
  }

  object ReflectiveActorMetricsReader extends ActorMetricsReader {

    private val logger = LoggerFactory.getLogger(getClass)

    def read(actor: classic.ActorRef): Option[ActorMetrics] =
      for {
        cell    <- ActorRefOps.Local.cell(actor)
        metrics <- ActorCellDecorator.get(cell)
      } yield ActorMetrics(
        mailboxSize = safeRead(ActorCellOps.numberOfMessages(cell)),
        mailboxTime = metrics.mailboxTimeAgg.metrics,
        processingTime = metrics.processingTimeAgg.metrics,
        receivedMessages = Some(metrics.receivedMessages.take()),
        unhandledMessages = Some(metrics.unhandledMessages.take()),
        failedMessages = Some(metrics.failedMessages.take()),
        sentMessages = Some(metrics.sentMessages.take()),
        stashSize = metrics.stashSize.take(),
        droppedMessages = metrics.droppedMessages.map(_.take())
      )

    private def safeRead[T](value: => T): Option[T] =
      try Some(value)
      catch {
        case ex: Throwable =>
          logger.warn("Fail to read metric value", ex)
          None
      }

  }

}

private[extension] class ActorEventsMonitorActor private[extension] (
  context: ActorContext[Command],
  monitor: ActorMetricsMonitor,
  node: Option[Node],
  pingOffset: FiniteDuration,
  storageFactory: MetricStorageFactory[ActorKey],
  scheduler: TimerScheduler[Command],
  actorMetricsReader: ActorMetricsReader = ReflectiveActorMetricsReader
) {

  import context._

  private[this] var terminatedActorsMetrics =
    Tree.builder[
      ActorKey,
      (ActorKey, ActorMetrics)
    ] // we aggregate only ActorMetrics to not prevent actor cell to be GC'd

  private[this] val boundMonitor = monitor.bind()

  private[this] val treeSnapshot = new AtomicReference[Option[Vector[(Labels, ActorMetrics)]]](None)

  private def updateMetric(extractor: ActorMetrics => Option[Long])(result: Result[Long, Labels]): Unit = {
    val state = treeSnapshot.get()
    state
      .foreach(_.foreach { case (labels, metrics) =>
        extractor(metrics).foreach(value => result.observe(value, labels))
      })
  }

  // this is not idempotent!
  private def registerUpdaters(): Unit = {
    boundMonitor.mailboxSize.setUpdater(updateMetric(_.mailboxSize))
    boundMonitor.failedMessages.setUpdater(updateMetric(_.failedMessages))
    boundMonitor.processedMessages.setUpdater(updateMetric(_.processedMessages))
    boundMonitor.receivedMessages.setUpdater(updateMetric(_.receivedMessages))
    boundMonitor.mailboxTimeAvg.setUpdater(updateMetric(_.mailboxTime.map(_.avg)))
    boundMonitor.mailboxTimeMax.setUpdater(updateMetric(_.mailboxTime.map(_.max)))
    boundMonitor.mailboxTimeMin.setUpdater(updateMetric(_.mailboxTime.map(_.min)))
    boundMonitor.mailboxTimeSum.setUpdater(updateMetric(_.mailboxTime.map(_.sum)))
    boundMonitor.processingTimeAvg.setUpdater(updateMetric(_.processingTime.map(_.avg)))
    boundMonitor.processingTimeMin.setUpdater(updateMetric(_.processingTime.map(_.min)))
    boundMonitor.processingTimeMax.setUpdater(updateMetric(_.processingTime.map(_.max)))
    boundMonitor.processingTimeSum.setUpdater(updateMetric(_.processingTime.map(_.sum)))
    boundMonitor.sentMessages.setUpdater(updateMetric(_.sentMessages))
    boundMonitor.stashSize.setUpdater(updateMetric(_.stashSize))
    boundMonitor.droppedMessages.setUpdater(updateMetric(_.droppedMessages))
  }

  private def subscribeToActorTermination(treeService: ActorRef[ActorTreeService.Command]): Unit =
    treeService ! TagSubscribe(Tag.terminated, context.messageAdapter[ActorRefDetails](ActorTerminateEvent.apply))

  // this is not idempotent
  def start(treeService: ActorRef[ActorTreeService.Command]): Behavior[Command] = {

    subscribeToActorTermination(treeService)
    setTimeout()
    registerUpdaters()
    loop(treeService)
  }

  private def loop(actorService: ActorRef[ActorTreeService.Command]): Behavior[Command] = {
    implicit val timeout: Timeout = 2.seconds

    Behaviors.receiveMessagePartial[Command] {
      case StartActorsMeasurement =>
        context
          .ask[ActorTreeService.Command, Tree[ActorRefDetails]](actorService, adapter => GetActorTree(adapter)) {
            case Success(value) => MeasureActorTree(value)
            case Failure(_)     => StartActorsMeasurement // keep asking
          }
        Behaviors.same
      case MeasureActorTree(refs) =>
        update(refs)
        setTimeout() // loop
        Behaviors.same
      case ActorTerminateEvent(details) =>
        val path = ActorPathOps.getPathString(details.ref)

        actorMetricsReader.read(details.ref).foreach { metrics =>
          terminatedActorsMetrics.insert(path, path -> metrics)
        }

        Behaviors.same
    }
  }.receiveSignal { case (_, PreRestart | PostStop) =>
    boundMonitor.unbind()
    Behaviors.same
  }

  private def setTimeout(): Unit = scheduler.startSingleTimer(StartActorsMeasurement, pingOffset)

  private def update(refs: Tree[ActorRefDetails]): Unit = {

    val storage = refs.unfix.foldRight[storageFactory.Storage] { case TreeF(details, childrenMetrics) =>
      import details._

      // is fold better?
      val storage =
        if (childrenMetrics.isEmpty) storageFactory.createStorage
        else childrenMetrics.reduce(storageFactory.mergeStorage)

      actorMetricsReader.read(ref).fold(storage) { currentMetrics =>
        import configuration.reporting._
        val actorKey = ActorPathOps.getPathString(ref)

        storage.save(actorKey, currentMetrics, visible)
        if (aggregate) {
          val (metrics, builder) = terminatedActorsMetrics.removeAfter(actorKey) // get all terminated information
          terminatedActorsMetrics = builder //
          metrics.foreach { case (key, metric) =>
            storage.save(key, metric, persistent = false)
          }
          storage.compute(actorKey)
        } else storage
      }
    }

    captureState(storage)
  }

  private def captureState(storage: storageFactory.Storage): Unit = {
    log.debug("Capturing current actor tree state")

    val currentSnapshot = treeSnapshot.get().getOrElse(Vector.empty)
    val metrics = storage.iterable.map { case (key, metrics) =>
      currentSnapshot.find { case (labels, _) =>
        labels.actorPath == key
      }.fold((Labels(key, node), metrics)) { case (labels, existingMetrics) =>
        (labels, existingMetrics.sum(metrics))
      }
    }.toVector

    treeSnapshot.set(Some(metrics))
    log.trace("Current actor metrics state {}", metrics)
  }

}
