package be.wegenenverkeer

import com.monsanto.arch.kamon.prometheus.PrometheusSettings
import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter
import com.monsanto.arch.kamon.prometheus.metric.{ProtoBufFormat, TextFormat}
import play.api.mvc._

import scala.concurrent.ExecutionContext

/** Manages the Play endpoint that Prometheus can use to scrape metrics.
  *
  */
class PrometheusController(settings: PrometheusSettings,
                           implicit val executionContext: ExecutionContext)
  extends Rendering
    with Results
    with SnapshotListener {

  val actionBuilder: ActionBuilder[Request, AnyContent] = DefaultActionBuilder(BodyParsers.utils.ignore(AnyContentAsEmpty: AnyContent))

  /** Converts snapshots from Kamon’s native type to the one used by this extension. */
  override val snapshotConverter = new SnapshotConverter(settings)

  val AcceptsProtoBuf = Accepting(PrometheusController.ProtobufContentType)
  val AcceptsText = Accepting("text/plain")

  def metrics = actionBuilder { implicit request =>
    Option(snapshot.get) match {
      case Some(s) =>
        render {
          case AcceptsProtoBuf() =>
            Ok(ProtoBufFormat.format(s))
              .as("application/vnd.google.protobuf; proto=io.prometheus.client.MetricFamily; encoding=delimited")

          case AcceptsText() =>
            Ok(TextFormat.format(s))
              .as("text/plain; version=0.0.4")
        }

      case None => NoContent
    }
  }

}

object PrometheusController {

  val ProtobufContentType: String = "application/vnd.google.protobuf"

}



