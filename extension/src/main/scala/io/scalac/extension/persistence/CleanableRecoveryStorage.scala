package io.scalac.extension.persistence

import scala.collection.mutable

import io.scalac.core.event.PersistenceEvent.RecoveryStarted
import io.scalac.core.util.Timestamp
import io.scalac.extension.config.CleaningSettings
import io.scalac.extension.resource.MutableCleanableStorage

class CleanableRecoveryStorage private[persistence] (_recoveries: mutable.Map[String, RecoveryStarted])(
  val cleaningConfig: CleaningSettings
) extends MutableRecoveryStorage(_recoveries)
    with MutableCleanableStorage[String, RecoveryStarted] {

  protected def extractTimestamp(value: RecoveryStarted): Timestamp = value.timestamp
}

object CleanableRecoveryStorage {
  def withConfig(flushConfig: CleaningSettings): CleanableRecoveryStorage =
    new CleanableRecoveryStorage(mutable.Map.empty)(flushConfig)
}
