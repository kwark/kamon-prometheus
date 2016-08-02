package be.wegenenverkeer

import akka.actor.{Actor, Props}
import akka.event.Logging
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot

/** An actor that receives messages from Kamon and updates the endpoint with the latest snapshot. */
class PrometheusListener(snapshotListener: SnapshotListener) extends Actor {
  private val log = Logging(context.system, this)

  override def receive = {
    case tick: TickMetricSnapshot => {
      log.debug(s"Got a tick: $tick")
      snapshotListener.updateSnapShot(tick)
    }
    case x => {
      log.warning(s"Got an $x")
    }
  }
}
object PrometheusListener {
  /** Provides the props to create a new PrometheusListener. */
  def props(snapshotListener: SnapshotListener): Props = Props(new PrometheusListener(snapshotListener))
}
