package io.scalac.mesmer.core.event

import akka.ActorSystemOps._
import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.Subscribe
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.DynamicVariable

import io.scalac.mesmer.core.AkkaDispatcher.safeDispatcherSelector
import io.scalac.mesmer.core.util.MutableTypedMap

trait EventBus extends Extension {
  def publishEvent[T <: AbstractEvent](event: T)(implicit service: Service[event.Service]): Unit
}

object EventBus extends ExtensionId[EventBus] {

  def createExtension(system: ActorSystem[_]): EventBus = {
    implicit val s                = system
    implicit val timeout: Timeout = 1 second
    implicit val ec               = system.executionContext
    new ReceptionistBasedEventBus()
  }
}

object ReceptionistBasedEventBus {
  private final case class Subscribers(refs: Set[ActorRef[Any]])

  def cachingBehavior[T](serviceKey: ServiceKey[T])(implicit timeout: Timeout): Behavior[T] =
    Behaviors
      .setup[Any] { ctx =>
        ctx.log.debug("Subscribe to service {}", serviceKey)
        Receptionist(ctx.system).ref ! Subscribe(
          serviceKey,
          ctx.messageAdapter { key =>
            val set = key.serviceInstances(serviceKey).filter(_.path.address.hasLocalScope)
            Subscribers(set.asInstanceOf[Set[ActorRef[Any]]])
          }
        )

        def withCachedServices(services: Set[ActorRef[T]]): Behavior[Any] =
          Behaviors.withStash(1024)(buffer =>
            Behaviors.receiveMessage {
              case Subscribers(refs) =>
                ctx.log.debug("Subscribers for service {} updated", serviceKey)
                buffer.unstashAll(withCachedServices(refs.asInstanceOf[Set[ActorRef[T]]]))
              case event: T @unchecked
                  if services.nonEmpty => // T is removed on runtime but placing it here make type downcast
                ctx.log.trace("Publish event for service {}", serviceKey)
                services.foreach(_ ! event)
                Behaviors.same
              case event =>
                ctx.log.warn("Received event but no services registered for key {}", serviceKey)
                buffer.stash(event)
                Behaviors.same
            }
          )
        withCachedServices(Set.empty)
      }
      .narrow[T] // this mimic well union types but might fail if interceptor are in place

}

private[scalac] class ReceptionistBasedEventBus(implicit
  val system: ActorSystem[_],
  val timeout: Timeout
) extends EventBus {

  private val log = LoggerFactory.getLogger(this.getClass)

  import ReceptionistBasedEventBus._

  type ServiceMapFunc[K <: AbstractService] = ActorRef[K#ServiceType]

  private[this] val serviceBuffers = MutableTypedMap[AbstractService, ServiceMapFunc]

  private[this] val dispatcher = safeDispatcherSelector

  def publishEvent[T <: AbstractEvent](event: T)(implicit service: Service[event.Service]): Unit =
    ref.fold(log.trace("Prevented publishing event {}", event)) { ref =>
      ref ! event
    }

  private val positiveFeedbackLoopBarrierClosed = new DynamicVariable[Boolean](false)

  @inline private def ref[S](implicit service: Service[S]): Option[ActorRef[S]] =
    if (!positiveFeedbackLoopBarrierClosed.value) {
      val ref = serviceBuffers
        .get(service)
        .orElse {
          if (system.toClassic.isInitialized)
            Some(serviceBuffers.getOrCreate(service) {
              positiveFeedbackLoopBarrierClosed.withValue(true) {
                val actorName = s"event-bus-${service.serviceKey.id}"
                log.debug("Start actor for service {}", service.serviceKey.id)
                system.systemActorOf(cachingBehavior(service.serviceKey), actorName, dispatcher)
              }
            })
          else {
            // we prevent publishing events before actor system if initialized -
            // we rely heavily on actor system for message routing and sending a message
            // before it's ready might yield unexpected results
            // those events are going to be lost so this must be taken into consideration
            // when implementing listeners
            None
          }
        }
      ref
    } else None

}
