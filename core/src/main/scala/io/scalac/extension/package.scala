package io.scalac

import akka.actor.typed.receptionist.ServiceKey
import io.scalac.extension.event.{ ClusterEvent, HttpEvent, PersistenceEvent }

package object `extension` {

  val persistenceService: ServiceKey[PersistenceEvent] =
    ServiceKey[PersistenceEvent](s"io.scalac.metric.persistence")

  val httpService: ServiceKey[HttpEvent] =
    ServiceKey[HttpEvent](s"io.scalac.metric.http")

  val clusterService: ServiceKey[ClusterEvent] = ServiceKey[ClusterEvent]("io.scalac.metric.cluster")
}
