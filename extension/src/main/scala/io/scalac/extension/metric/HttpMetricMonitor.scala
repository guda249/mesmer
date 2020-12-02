package io.scalac.extension.metric

import io.opentelemetry.api.common.{ Labels => OpenTelemetryLabels }
import io.scalac.extension.metric.HttpMetricMonitor._
import io.scalac.extension.model._

object HttpMetricMonitor {
  trait BoundMonitor {
    def requestTime: MetricRecorder[Long]
    def requestCounter: UpCounter[Long]
  }

  final case class Labels(path: Path, method: Method) {
    def toOpenTelemetry: OpenTelemetryLabels = OpenTelemetryLabels.of("path", path, "method", method)
  }
}

trait HttpMetricMonitor extends Bindable[Labels] {
  import HttpMetricMonitor._

  override type Bound = HttpMetricMonitor.BoundMonitor

  override def bind(labels: Labels): Bound
}
