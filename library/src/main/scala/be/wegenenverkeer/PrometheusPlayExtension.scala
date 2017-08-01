package be.wegenenverkeer

import akka.actor.{ExtendedActorSystem, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import com.monsanto.arch.kamon.prometheus.PrometheusSettings
import kamon.Kamon
import kamon.metric.TickMetricSnapshotBuffer

object PrometheusPlay extends ExtensionId[PrometheusPlayExtension] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): PrometheusPlayExtension = new PrometheusPlayExtension(system)

  override def lookup() = PrometheusPlay
}

/**
  * A Kamon extension that provides a Play controller so that Prometheus can retrieve metrics from Kamon.
  *
  * TODO: add real documentation
  *
  * @param system the Actor system to which this class acts as an extension
  */
class PrometheusPlayExtension(val system: ExtendedActorSystem) extends Kamon.Extension {

  /** Handy log reference. */
  private val log = Logging(system, classOf[PrometheusPlayExtension])
  private val config = system.settings.config

  /** Expose the extension’s settings. */
  val settings: PrometheusSettings = new PrometheusSettings(config)

  /** Returns true if the results from the extension need to be buffered because the refresh less frequently than the
    * tick interval.
    */
  val isBuffered: Boolean = settings.refreshInterval > Kamon.metrics.settings.tickInterval

  /** Listens to and records metrics. */
  val controller: PrometheusController = new PrometheusController(settings, system.dispatcher)
  private[wegenenverkeer] val listener = system.actorOf(PrometheusListener.props(controller), "prometheus-listener")

  /** If the listener needs to listen less frequently than ticks, set up a buffer. */
  private[wegenenverkeer] val buffer = {
    if (isBuffered) {
      system.actorOf(TickMetricSnapshotBuffer.props(settings.refreshInterval, listener), "prometheus-buffer")
    } else {
      listener
    }
  }

  log.info("Starting the Kamon(Prometheus) extension")
  settings.subscriptions.foreach {case (category, selections) ⇒
    selections.foreach { selection ⇒
      Kamon.metrics.subscribe(category, selection, buffer, permanently = true)
    }
  }

}
