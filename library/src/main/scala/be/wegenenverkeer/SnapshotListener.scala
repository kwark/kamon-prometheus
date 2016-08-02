package be.wegenenverkeer

import java.util.concurrent.atomic.AtomicReference

import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter
import com.monsanto.arch.kamon.prometheus.metric.MetricFamily
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot

trait SnapshotListener {

  /** Converts snapshots from Kamon’s native type to the one used by this extension. */
  def snapshotConverter: SnapshotConverter

  /** Mutable cell with the latest snapshot. */
  val snapshot = new AtomicReference[Seq[MetricFamily]]

  /** Updates the endpoint's current snapshot atomically. */
  def updateSnapShot(newSnapshot: TickMetricSnapshot): Unit = {
    snapshot.set(snapshotConverter(newSnapshot))
  }

}
