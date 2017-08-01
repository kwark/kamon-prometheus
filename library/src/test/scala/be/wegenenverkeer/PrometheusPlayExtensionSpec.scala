package com.monsanto.arch.kamon.prometheus

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import be.wegenenverkeer.{PrometheusController, PrometheusPlay}
import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter
import com.monsanto.arch.kamon.prometheus.metric._
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.metric.SingleInstrumentEntityRecorder
import kamon.util.MilliTimestamp
import org.scalactic.Uniformity
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, OptionValues, WordSpec}
import play.api.http.ContentTypeOf
import play.api.mvc.{RequestHeader, Result, Results}
import play.api.test.FakeRequest
import play.api.http.Status._

import scala.collection.immutable.ListMap
import scala.concurrent.Future

/** Tests the end-to-end functionality of the
  * [[be.wegenenverkeer.PrometheusPlayExtension PrometheusExtension]].
  *
  */
class PrometheusPlayExtensionSpec extends WordSpec with Results with Matchers with ScalaFutures with OptionValues {

  import scala.concurrent.ExecutionContext.Implicits.global

  "The Prometheus extension endpoint" when {
    "it has no snapshots" should {
      "returns an empty response" in {
        KamonTestKit.resetKamon(ConfigFactory.parseString("kamon.prometheus.refresh-interval = 10 seconds").withFallback(ConfigFactory.load()))
        Kamon.start()
        val controller = new PrometheusController(new PrometheusSettings(ConfigFactory.load()), scala.concurrent.ExecutionContext.Implicits.global)
        val result: Future[Result] = controller.metrics().apply(FakeRequest())
        result.futureValue shouldBe NoContent
        Kamon.shutdown()
      }
    }

    "doing a plain-text request" when {

      "it has content" should {
        "handle GET requests" in new WithData {
          val result: Result = extension.controller.metrics().apply(FakeRequest().withHeaders("Accept" -> "text/plain")).futureValue
          result.header.status shouldBe play.api.http.Status.OK
          result.body.contentType.value.toString shouldBe "text/plain; version=0.0.4"
          val s = result.body.consumeData(ActorMaterializer()).map(bs => new String(bs.toArray)).futureValue
          (TextFormat.parse(s) should contain theSameElementsAs snapshot) (after being normalised)
          Kamon.shutdown()
        }
      }
    }

    "doing a protocol buffer request" when {

      "it has content" should {
        "handle GET requests" in new WithData {
          val result: Result = extension.controller.metrics().apply(FakeRequest().withHeaders("Accept" -> PrometheusController.ProtobufContentType)).futureValue
          result.header.status shouldBe play.api.http.Status.OK
          result.body.contentType.value.toString shouldBe "application/vnd.google.protobuf; proto=io.prometheus.client.MetricFamily; encoding=delimited"
          val bytes = result.body.consumeData(ActorMaterializer()).map(bs => bs.toArray).futureValue
          (ProtoBufFormat.parse(bytes) should contain theSameElementsAs snapshot) (after being normalised)
          Kamon.shutdown()
        }
      }
    }
  }

  /** Normalises a metric family by ensuring its metrics are given an order and their timestamps are all given the
    * same value.
    */
  val normalised = new Uniformity[MetricFamily] {
    /** Sorts metrics according to their labels.  Assumes the labels are sorted. */
    def metricSort(a: Metric, b: Metric): Boolean = {
      (a.labels.headOption, b.labels.headOption) match {
        case (Some(x), Some(y)) ⇒
          if (x._1 < y._1) {
            true
          } else if (x._1 == y._1) {
            x._2 < y._2
          } else {
            false
          }
        case (None, Some(_)) ⇒ true
        case (Some(_), None) ⇒ false
        case (None, None) ⇒ false
      }
    }

    override def normalizedOrSame(b: Any): Any = b match {
      case mf: MetricFamily ⇒ normalized(mf)
      case _ ⇒ b
    }
    //noinspection ComparingUnrelatedTypes
    override def normalizedCanHandle(b: Any): Boolean = b.isInstanceOf[MetricFamily]

    override def normalized(metricFamily: MetricFamily): MetricFamily = {
      val normalMetrics = metricFamily.metrics.map { m ⇒
        val sortedLabels = ListMap(m.labels.toSeq.sortWith(_._1 < _._2): _*)
        Metric(m.value, new MilliTimestamp(0), sortedLabels)
      }.sortWith(metricSort)
      MetricFamily(metricFamily.name, metricFamily.prometheusType, metricFamily.help, normalMetrics)
    }
  }

  trait WithData {
    import SnapshotConverter.{KamonCategoryLabel, KamonNameLabel}
    val ts = MilliTimestamp.now
    import MetricValue.{Bucket => B, Histogram => HG}
    val ∞ = Double.PositiveInfinity
    val snapshot = Seq(
      MetricFamily("test_counter", PrometheusType.Counter, None,
        Seq(
          Metric(MetricValue.Counter(1), ts,
            Map("type" → "a",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
              KamonNameLabel → "test_counter")),
          Metric(MetricValue.Counter(2), ts,
            Map("type" → "b",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
              KamonNameLabel → "test_counter")))),
      MetricFamily("another_counter", PrometheusType.Counter, None,
        Seq(Metric(MetricValue.Counter(42), ts,
          Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter, KamonNameLabel → "another_counter")))),
      MetricFamily("a_histogram", PrometheusType.Histogram, None,
        Seq(
          Metric(HG(Seq(B(1, 20), B(4, 23), B(∞, 23)), 23, 32), ts,
            Map("got_label" → "yes",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram,
              KamonNameLabel → "a_histogram")),
          Metric(HG(Seq(B(3, 2), B(5, 6), B(∞, 6)), 6, 26), ts,
            Map("got_label" → "true",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram,
              KamonNameLabel → "a_histogram")))),
      MetricFamily("another_histogram", PrometheusType.Histogram, None,
        Seq(Metric(HG(Seq(B(20, 20), B(∞, 20)), 20, 400), ts,
          Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → "another_histogram")))),
      MetricFamily("a_min_max_counter", PrometheusType.Histogram, None,
        Seq(Metric(HG(Seq(B(0, 1), B(1, 2), B(3, 3), B(∞, 3)), 3, 4), ts,
          Map(
            KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter,
            KamonNameLabel → "a_min_max_counter")))))

    KamonTestKit.resetKamon(ConfigFactory.parseString("kamon.prometheus.refresh-interval = 10 seconds").withFallback(ConfigFactory.load()))
    Kamon.start()
    implicit val actorSystem: ActorSystem = KamonTestKit.kamonActorSystem()
    val extension = actorSystem.registerExtension(PrometheusPlay)

    Kamon.metrics.counter("test_counter", Map("type" -> "a")).increment()
    Kamon.metrics.counter("test_counter", Map("type" -> "b")).increment(2)
    Kamon.metrics.counter("another_counter").increment(42)
    val h = Kamon.metrics.histogram("a_histogram", Map("got_label" → "true"))
    h.record(3, 2)
    h.record(5, 4)
    val h2 = Kamon.metrics.histogram("a_histogram", Map("got_label" → "yes"))
    h2.record(1, 20)
    h2.record(4, 3)
    val h3 = Kamon.metrics.histogram("another_histogram")
    h3.record(20, 20)
    val mmc = Kamon.metrics.minMaxCounter("a_min_max_counter")
    mmc.increment(3)
    mmc.decrement(2)

    KamonTestKit.flushSubscriptions()
    Thread.sleep(500)
  }
}
