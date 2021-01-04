package io.scalac.extension.persistence

import io.scalac.extension.event.PersistenceEvent.{PersistingEventFinished, PersistingEventStarted}
import io.scalac.extension.persistence.PersistStorage.PersistEventKey

class InMemoryPersistStorage private (private val persist: Map[PersistEventKey, PersistingEventStarted])
    extends PersistStorage {

  override def persistEventStarted(event: PersistingEventStarted): PersistStorage = {
    val key = eventToKey(event)
    new InMemoryPersistStorage(persist + (key -> event))
  }

  override def persistEventFinished(event: PersistingEventFinished): Option[(PersistStorage, Long)] = {
    val key = eventToKey(event)
    persist.get(key).map { started =>
      val duration = calculate(started, event)
      (new InMemoryPersistStorage(persist - key), duration)
    }
  }
}

object InMemoryPersistStorage {

  def empty: InMemoryPersistStorage = new InMemoryPersistStorage(Map.empty)
}