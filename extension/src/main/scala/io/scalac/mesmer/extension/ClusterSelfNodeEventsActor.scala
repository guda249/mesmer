package io.scalac.mesmer.extension
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.ReachableMember
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ClusterEvent.{ ReachabilityEvent => AkkaReachabilityEvent }
import akka.cluster.UniqueAddress
import akka.cluster.typed.Cluster
import akka.cluster.typed.Subscribe

import io.scalac.mesmer.core.model.AkkaNodeOps
import io.scalac.mesmer.extension.metric.ClusterMetricsMonitor
import io.scalac.mesmer.extension.metric.ClusterMetricsMonitor.Attributes

class ClusterSelfNodeEventsActor
object ClusterSelfNodeEventsActor extends ClusterMonitorActor {

  sealed trait Command extends SerializableMessage

  object Command {
    private[ClusterSelfNodeEventsActor] final case class ClusterMemberEvent(event: MemberEvent) extends Command

    sealed trait ReachabilityEvent extends Command

    private[ClusterSelfNodeEventsActor] case class NodeUnreachable(address: UniqueAddress) extends ReachabilityEvent

    private[ClusterSelfNodeEventsActor] case class NodeReachable(address: UniqueAddress) extends ReachabilityEvent
  }

  def apply(clusterMetricsMonitor: ClusterMetricsMonitor): Behavior[Command] =
    OnClusterStartUp { selfMember =>
      Behaviors.setup { ctx =>
        import ctx.{ log, messageAdapter, system }

        import Command._

        val monitor = clusterMetricsMonitor.bind(Attributes(selfMember.uniqueAddress.toNode))
        val cluster = Cluster(system)

        // bootstrap messages

        cluster.subscriptions ! Subscribe(
          messageAdapter[MemberEvent](ClusterMemberEvent.apply),
          classOf[MemberEvent]
        )

        cluster.subscriptions ! Subscribe(
          messageAdapter[AkkaReachabilityEvent] {
            case UnreachableMember(member) => NodeUnreachable(member.uniqueAddress)
            case ReachableMember(member)   => NodeReachable(member.uniqueAddress)
          },
          classOf[AkkaReachabilityEvent]
        )

        // behavior setup

        def initialized(unreachableNodes: Set[UniqueAddress]): Behavior[Command] = Behaviors.receiveMessage[Command] {
          case ClusterMemberEvent(MemberRemoved(member, _)) =>
            if (unreachableNodes.contains(member.uniqueAddress)) {
              monitor.unreachableNodes.decValue(1L)
            } else {
              monitor.reachableNodes.decValue(1L)
            }
            initialized(unreachableNodes - member.uniqueAddress)

          case ClusterMemberEvent(event) =>
            event match {
              case MemberUp(_) => monitor.reachableNodes.incValue(1L)
              case _           => //
            }
            Behaviors.same

          case NodeReachable(address) =>
            log.trace("Node {} become reachable", address)
            monitor.atomically(monitor.reachableNodes, monitor.unreachableNodes)(1L, -1L)
            initialized(unreachableNodes - address)

          case NodeUnreachable(address) =>
            log.trace("Node {} become unreachable", address)
            monitor.atomically(monitor.reachableNodes, monitor.unreachableNodes)(-1L, 1L)
            initialized(unreachableNodes + address)

        }

        val unreachable = cluster.state.unreachable.map(_.uniqueAddress)
        initialized(unreachable)
      }
    }

}
