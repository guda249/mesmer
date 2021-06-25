package io.scalac.mesmer.core.module
import com.typesafe.config.{ Config => TypesafeConfig }
import io.scalac.mesmer.core.model.Version
import io.scalac.mesmer.core.module.Module.{ Combine, Traverse }
import io.scalac.mesmer.core.util.LibraryInfo.LibraryInfo

sealed trait AkkaPersistenceMetricsModule extends MetricsModule {
  this: Module =>

  override type Metrics[T] <: AkkaPersistenceMetricsDef[T]

  trait AkkaPersistenceMetricsDef[T] {
    def recoveryTime: T
    def recoveryTotal: T
    def persistentEvent: T
    def persistentEventTotal: T
    def snapshot: T
  }
}

object AkkaPersistenceModule extends MesmerModule with AkkaPersistenceMetricsModule with RegisterGlobalConfiguration {
  override type Metrics[T] = AkkaPersistenceMetricsDef[T]

  val name: String = "akka-persistence"

  final case class Impl[T](
    recoveryTime: T,
    recoveryTotal: T,
    persistentEvent: T,
    persistentEventTotal: T,
    snapshot: T
  ) extends AkkaPersistenceMetricsDef[T]

  val defaultConfig: AkkaPersistenceModule.Result = Impl(true, true, true, true, true)

  protected def extractFromConfig(config: TypesafeConfig): Config = {

    val moduleEnabled = config
      .tryValue("enabled")(_.getBoolean)
      .getOrElse(true)
    if (moduleEnabled) {
      val recoveryTime = config
        .tryValue("recovery-time")(_.getBoolean)
        .getOrElse(defaultConfig.recoveryTime)
      val recoveryTotal = config
        .tryValue("recovery-total")(_.getBoolean)
        .getOrElse(defaultConfig.recoveryTotal)
      val persistentEvent = config
        .tryValue("persistent-event")(_.getBoolean)
        .getOrElse(defaultConfig.persistentEvent)
      val persistentEventTotal = config
        .tryValue("persistent-event-total")(_.getBoolean)
        .getOrElse(defaultConfig.persistentEventTotal)
      val snapshot = config
        .tryValue("snapshot")(_.getBoolean)
        .getOrElse(defaultConfig.snapshot)

      Impl[Boolean](recoveryTime, recoveryTotal, persistentEvent, persistentEventTotal, snapshot)
    } else Impl[Boolean](false, false, false, false, false)

  }

  override type All[T]     = Metrics[T]
  override type AkkaJar[T] = Jars[T]

  final case class Jars[T](akkaPersistence: T, akkaPersistenceTyped: T)

  def jarsFromLibraryInfo(info: LibraryInfo): Option[AkkaJar[Version]] =
    for {
      persistence      <- info.get(requiredAkkaJars.akkaPersistence)
      persistenceTyped <- info.get(requiredAkkaJars.akkaPersistenceTyped)
    } yield Jars(persistence, persistenceTyped)

  val requiredAkkaJars: AkkaJar[String] = Jars("akka-persistence", "akka-persistence-typed")

  implicit val combineConfig: Combine[All[Boolean]] = (first, second) => {
    Impl(
      recoveryTime = first.recoveryTime && second.recoveryTime,
      recoveryTotal = first.recoveryTotal && second.recoveryTotal,
      persistentEvent = first.persistentEvent && second.persistentEvent,
      persistentEventTotal = first.persistentEventTotal && second.persistentEventTotal,
      snapshot = first.snapshot && second.snapshot
    )
  }

  implicit val traverseAll: Traverse[All] = new Traverse[All] {
    def sequence[T](obj: All[T]): Seq[T] = Seq(
      obj.recoveryTime,
      obj.recoveryTotal,
      obj.persistentEvent,
      obj.persistentEventTotal,
      obj.snapshot
    )
  }

}
