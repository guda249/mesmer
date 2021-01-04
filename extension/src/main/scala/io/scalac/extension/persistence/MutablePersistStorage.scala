package io.scalac.extension.persistence

import java.util.concurrent.ConcurrentHashMap

import io.scalac.extension.event.PersistenceEvent.{PersistingEventFinished, PersistingEventStarted}
import io.scalac.extension.persistence.PersistStorage.PersistEventKey
import io.scalac.extension.resource.MutableStorage

import scala.collection.concurrent.{Map => CMap}
import scala.jdk.CollectionConverters._

class MutablePersistStorage private[persistence] (
  protected val buffer: CMap[PersistEventKey, PersistingEventStarted]
) extends PersistStorage
    with MutableStorage[PersistEventKey, PersistingEventStarted] {

  override def persistEventStarted(event: PersistingEventStarted): PersistStorage = {
    buffer.putIfAbsent(eventToKey(event), event)
    this
  }

  override def persistEventFinished(event: PersistingEventFinished): Option[(PersistStorage, Long)] =
    buffer.remove(eventToKey(event)).map { started =>
      val latency = calculate(started, event)
      (this, latency)
    }
}

object MutablePersistStorage {
  def empty: MutablePersistStorage =
    new MutablePersistStorage(new ConcurrentHashMap[PersistEventKey, PersistingEventStarted]().asScala)
}