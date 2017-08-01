package com.monsanto.arch.kamon.prometheus.converter

import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}

import akka.kamon.instrumentation.AkkaDispatcherMetrics
import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter.{KamonCategoryLabel, KamonNameLabel}
import com.monsanto.arch.kamon.prometheus.metric.PrometheusType.Counter
import com.monsanto.arch.kamon.prometheus.metric._
import com.monsanto.arch.kamon.prometheus.{KamonTestKit, PrometheusGen, PrometheusSettings}
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.akka.{ActorMetrics, RouterMetrics}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric._
import kamon.metric.instrument.{InstrumentFactory, Memory, Time, UnitOfMeasurement}
import kamon.util.executors.{ForkJoinPoolMetrics, ThreadPoolExecutorMetrics}
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{LoneElement, Matchers, WordSpec}

import scala.concurrent.forkjoin.ForkJoinPool

/** Tests for the conversion of Kamon TickMetricSnapshot instances into our own MetricFamily instances. */
class SnapshotConverterSpec extends WordSpec with KamonTestKit with Matchers with GeneratorDrivenPropertyChecks with LoneElement {
  def handle = afterWord("handle")

  def _have = afterWord("have")

  def are = afterWord("are")

  def converter = new SnapshotConverter(new PrometheusSettings(ConfigFactory.load()))

  "a snapshot converter" should handle {
    "empty ticks" in {
      val tick = TickMetricSnapshot(start, end, Map.empty[Entity, EntitySnapshot])
      val result = converter(tick)

      result shouldBe Seq.empty[MetricFamily]
    }

    "counters" which _have {
      "valid names and simple counts" in {
        forAll(
          PrometheusGen.metricName → "name",
          PrometheusGen.count → "count"
        ) { (name: String, count: Int) ⇒
          val entity = counter(name, count)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result.loneElement shouldBe
            MetricFamily(name, Counter, None, Seq(Metric(MetricValue.Counter(count), end,
              Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter, KamonNameLabel → name))))
        }
      }

      "names that need munging" in {
        forAll(PrometheusGen.unicodeString → "name") { name ⇒
          val entity = counter(name, 1)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result.loneElement.name shouldBe Mungers.asMetricName(name)
          result.loneElement.metrics.loneElement.labels(KamonNameLabel) shouldBe name
        }
      }

      "tags" in {
        val name = "counter"
        forAll(PrometheusGen.tags → "tags") { tags ⇒
          val entity = counter(name, 2, tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result.loneElement.metrics.loneElement.labels shouldBe
            tags + (KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter, KamonNameLabel → name)
        }
      }

      "tags that require munging" in {
        val name = "counter"
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(
          labelName → "key",
          PrometheusGen.unicodeString → "value"
        ) { (key: String, value: String) ⇒
          val tags = Map(key → value)
          val entity = counter(name, 1, tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result.loneElement.metrics.loneElement.labels shouldBe
            Map(
              Mungers.asLabelName(key) → value,
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
              KamonNameLabel → name)
        }
      }

      "multiple sets of tags" in {
        val name = "counter"
        val count1 = 1
        val count2 = 2

        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1, tags2) ⇒
          whenever(tags1 != tags2) {
            val entity1 = counter(name, count1, tags1)
            val entity2 = counter(name, count2, tags2)
            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)
            val extraTags = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter, KamonNameLabel → name)

            result.loneElement.metrics shouldBe
              Seq(
                Metric(MetricValue.Counter(count1), end, tags1 ++ extraTags),
                Metric(MetricValue.Counter(count2), end, tags2 ++ extraTags))
          }
        }
      }

      "units of measurement" which are {
        import SnapshotConverterSpec.{Celsius, Joules}

        "unknown" in {
          val entity = counter("counter", 20, unitOfMeasurement = UnitOfMeasurement.Unknown)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter"
        }

        "nanoseconds" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time.Nanoseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_nanoseconds"
        }

        "microseconds" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time.Microseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_microseconds"
        }

        "milliseconds" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time.Milliseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_milliseconds"
        }

        "seconds" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time.Seconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_seconds"
        }

        "bytes" in {
          val entity = counter("counter", 20, unitOfMeasurement = Memory.Bytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_bytes"
        }

        "kilobytes" in {
          val entity = counter("counter", 20, unitOfMeasurement = Memory.KiloBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_kilobytes"
        }

        "megabytes" in {
          val entity = counter("counter", 20, unitOfMeasurement = Memory.MegaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_megabytes"
        }

        "gigabytes" in {
          val entity = counter("counter", 20, unitOfMeasurement = Memory.GigaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_gigabytes"
        }

        "hours (custom time type)" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time(3600, "h"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_h"
        }

        "terabytes (custom memory type)" in {
          val entity = counter("counter", 20, unitOfMeasurement = Time(1024E4, "Tb"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_Tb"
        }

        "joules (custom type)" in {
          val entity = counter("counter", 20, unitOfMeasurement = Joules)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter_J"
        }

        "celsius (mungeable custom type)" in {
          val entity = counter("counter", 20, unitOfMeasurement = Celsius)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "counter__C"
        }
      }
    }

    "histograms" which _have {
      "nothing special" in {
        val name = "test_histogram"
        val values = 1L.to(5)
        val entity = histogram(name, values)
        val tick = snapshotOf(entity)
        val labels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → name)

        val metricValue = MetricValue.Histogram(
          Seq(
            MetricValue.Bucket(1, 1),
            MetricValue.Bucket(2, 2),
            MetricValue.Bucket(3, 3),
            MetricValue.Bucket(4, 4),
            MetricValue.Bucket(5, 5),
            MetricValue.Bucket(Double.PositiveInfinity, 5)),
          5,
          15.0)

        val result = converter(tick)

        result.loneElement shouldBe
          MetricFamily(name, PrometheusType.Histogram, None,
            Seq(Metric(metricValue, end, labels)))
      }

      "valid names" in {
        val value: Gen[Long] = PrometheusGen.chooseExponentially(1L, 1L, 3600000000000L)

        /** Generates a list of values for a histogram. */
        val values: Gen[Seq[Long]] = Gen.listOf(value)
        forAll(
          PrometheusGen.metricName → "name",
          values → "values"
        ) { (name: String, values: Seq[Long]) ⇒
          val entity = histogram(name, values)
          val tick = snapshotOf(entity)
          val expected = {
            val value = MetricValue.Histogram(tick.metrics(entity).histogram("histogram").get)
            val labels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → name)
            MetricFamily(name, PrometheusType.Histogram, None, Seq(Metric(value, end, labels)))
          }
          val result = converter(tick)
          result.loneElement shouldBe expected
        }
      }

      "names that require munging" in {
        forAll(PrometheusGen.unicodeString → "name") { (name: String) ⇒
          val entity = histogram(name, Seq.empty[Long])
          val tick = snapshotOf(entity)
          val result = converter(tick)

          val mungedName = Mungers.asMetricName(name)

          result.loneElement.name shouldBe mungedName
        }
      }

      "non-help tags" in {
        forAll(PrometheusGen.tags → "tags") { (tags: Map[String, String]) ⇒
          val name = "tagged_histogram"
          val entity = histogram(name, tags = tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result.loneElement.metrics.loneElement.labels shouldBe
            (tags ++ Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → name))
        }
      }

      "non-help tags which require munging" in {
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(
          labelName → "label name",
          PrometheusGen.unicodeString → "label value"
        ) { (key: String, value: String) ⇒
          val name = "histogram"
          val tags = Map(key → value)
          val entity = histogram(name, tags = tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          val mungedTag = Map(Mungers.asLabelName(key) → value)
          result.loneElement.metrics.loneElement.labels shouldBe
            (mungedTag ++ Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → name))
        }
      }

      "multiple sets of tags" in {
        type Tags = Map[String, String]
        val name = "multi_tagged"
        val extraLabels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → name)
        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1: Tags, tags2: Tags) ⇒
          whenever(tags1 != tags2) {
            val entity1 = histogram(name, values =  Seq(1L), tags = tags1)
            val value1 = MetricValue.Histogram(
              Seq(MetricValue.Bucket(1, 1), MetricValue.Bucket(Double.PositiveInfinity, 1)),
              1,
              1)
            val entity2 = histogram(name, values =  Seq(2L), tags = tags2)
            val value2 = MetricValue.Histogram(
              Seq(MetricValue.Bucket(2, 1), MetricValue.Bucket(Double.PositiveInfinity, 1)),
              1,
              2)

            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)

            result.loneElement.metrics shouldBe
              Seq(
                Metric(value1, end, tags1 ++ extraLabels),
                Metric(value2, end, tags2 ++ extraLabels))
          }
        }
      }

      "units of measurement" which are {
        import SnapshotConverterSpec.{Celsius, Joules}

        "unknown" in {
          val entity = histogram("histogram", unitOfMeasurement = UnitOfMeasurement.Unknown)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram"
        }

        "nanoseconds" in {
          val entity = histogram("histogram", unitOfMeasurement = Time.Nanoseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_nanoseconds"
        }

        "microseconds" in {
          val entity = histogram("histogram", unitOfMeasurement = Time.Microseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_microseconds"
        }

        "milliseconds" in {
          val entity = histogram("histogram", unitOfMeasurement = Time.Milliseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_milliseconds"
        }

        "seconds" in {
          val entity = histogram("histogram", unitOfMeasurement = Time.Seconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_seconds"
        }

        "bytes" in {
          val entity = histogram("histogram", unitOfMeasurement = Memory.Bytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_bytes"
        }

        "kilobytes" in {
          val entity = histogram("histogram", unitOfMeasurement = Memory.KiloBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_kilobytes"
        }

        "megabytes" in {
          val entity = histogram("histogram", unitOfMeasurement = Memory.MegaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_megabytes"
        }

        "gigabytes" in {
          val entity = histogram("histogram", unitOfMeasurement = Memory.GigaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_gigabytes"
        }

        "hours (custom time type)" in {
          val entity = histogram("histogram", unitOfMeasurement = Time(3600, "h"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_h"
        }

        "terabytes (custom memory type)" in {
          val entity = histogram("histogram", unitOfMeasurement = Time(1024E4, "Tb"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_Tb"
        }

        "joules (custom type)" in {
          val entity = histogram("histogram", unitOfMeasurement = Joules)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram_J"
        }

        "celsius (mungeable custom type)" in {
          val entity = histogram("histogram", unitOfMeasurement = Celsius)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "histogram__C"
        }
      }
    }

    "min-max counters" which _have {
      "nothing special" in {
        val name = "test_min_max_counter"
        val changes = Seq(5L, -1L, 2L, -3L, 6L, 20L, -10L)
        val entity = minMaxCounter(name, changes)
        val tick = snapshotOf(entity)

        val value = MetricValue.Histogram(tick.metrics(entity).minMaxCounter("min-max-counter").get)
        val labels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter, KamonNameLabel → name)

        val expected = MetricFamily(name, PrometheusType.Histogram, None, Seq(Metric(value, end, labels)))
        val result = converter(tick)

        result.loneElement shouldBe expected
      }

      "arbitrary values" in {
        val name = "arbitrary_values"
        forAll(PrometheusGen.minMaxCounterChanges → "changes") { (changes: Seq[Long]) ⇒
          val entity = minMaxCounter(name, changes)
          val tick = snapshotOf(entity)
          val value = MetricValue.Histogram(tick.metrics(entity).minMaxCounter("min-max-counter").get)
          val result = converter(tick)
          result.loneElement.metrics.loneElement.value shouldBe value
        }
      }

      "valid names" in {
        val changes = Seq.empty[Long]
        forAll(PrometheusGen.metricName → "name") { (name: String) ⇒
          val entity = minMaxCounter(name, changes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe name
          result.loneElement.metrics.loneElement.labels(KamonNameLabel) shouldBe name
        }
      }

      "names that require munging" in {
        val changes = Seq.empty[Long]
        forAll(PrometheusGen.unicodeString → "name") { (name: String) ⇒
          val entity = minMaxCounter(name, changes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe Mungers.asMetricName(name)
          result.loneElement.metrics.loneElement.labels(KamonNameLabel) shouldBe name
        }
      }

      "non-help tags" in {
        val name = "min_max_counter"
        val changes = Seq.empty[Long]
        forAll(PrometheusGen.tags → "tags") { tags ⇒
          val entity = minMaxCounter(name, changes = changes, tags = tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.metrics.loneElement.labels shouldBe
            (tags ++ Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter, KamonNameLabel → name))
        }
      }

      "non-help tags which require munging" in {
        val name = "min_max_counter"
        val changes = Seq.empty[Long]
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(labelName → "label name", PrometheusGen.unicodeString → "label value") { (key, value) ⇒
          val tags = Map(key → value)
          val entity = minMaxCounter(name, tags = tags, changes = changes)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          val mungedTags = Map(Mungers.asLabelName(key) → value,
            KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter,
            KamonNameLabel → name)
          result.loneElement.metrics.loneElement.labels shouldBe mungedTags
        }
      }

      "multiple sets of tags" in {
        val name = "min_max_counter"
        val commonLabels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter, KamonNameLabel → name)
        val changes = Seq.empty[Long]
        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1, tags2) ⇒
          whenever(tags1 != tags2 && (tags1 ++ tags2).keys.forall(Metric.isValidLabelName)) {
            val entity1 = minMaxCounter(name, tags = tags1, changes = changes)
            val entity2 = minMaxCounter(name, tags = tags2, changes = changes)

            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)

            result.loneElement.metrics.map(_.labels) shouldBe Seq(tags1 ++ commonLabels, tags2 ++ commonLabels)
          }
        }
      }

      "units of measurement" which are {
        import SnapshotConverterSpec.{Celsius, Joules}

        "unknown" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = UnitOfMeasurement.Unknown)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter"
        }

        "nanoseconds" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time.Nanoseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_nanoseconds"
        }

        "microseconds" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time.Microseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_microseconds"
        }

        "milliseconds" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time.Milliseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_milliseconds"
        }

        "seconds" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time.Seconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_seconds"
        }

        "bytes" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Memory.Bytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_bytes"
        }

        "kilobytes" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Memory.KiloBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_kilobytes"
        }

        "megabytes" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Memory.MegaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_megabytes"
        }

        "gigabytes" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Memory.GigaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_gigabytes"
        }

        "hours (custom time type)" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time(3600, "h"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_h"
        }

        "terabytes (custom memory type)" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Time(1024E4, "Tb"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_Tb"
        }

        "joules (custom type)" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Joules)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter_J"
        }

        "celsius (mungeable custom type)" in {
          val entity = minMaxCounter("minMaxCounter", unitOfMeasurement = Celsius)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "minMaxCounter__C"
        }
      }
    }

    "gauges" which _have {
      "nothing special" in {
        val name = "gauge"
        val values = Seq(1L, 3L, 6L, 10L)
        val entity = gauge(name, values)
        val labels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Gauge, KamonNameLabel → name)
        val tick = snapshotOf(entity)
        val result = converter(tick)

        val value = MetricValue.Histogram(
          Seq(
            MetricValue.Bucket(1, 1),
            MetricValue.Bucket(3, 2),
            MetricValue.Bucket(6, 3),
            MetricValue.Bucket(10, 4),
            MetricValue.Bucket(Double.PositiveInfinity, 4) ),
          4,
          20)

        result.loneElement shouldBe
          MetricFamily(name, PrometheusType.Histogram, None, Seq(Metric(value, end, labels)))
      }

      "arbitrary values" in {
        val name = "arbitrary_values"
        forAll(PrometheusGen.instrumentLevels → "readings") { readings ⇒
          val entity = gauge(name, readings)
          val tick = snapshotOf(entity)
          val value = MetricValue.Histogram(tick.metrics(entity).gauge("gauge").get)
          val result = converter(tick)
          result.loneElement.metrics.loneElement.value shouldBe value
        }
      }

      "valid names" in {
        val readings = Seq.empty[Long]
        forAll(PrometheusGen.metricName → "name") { name ⇒
          val entity = gauge(name, readings)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe name
          result.loneElement.metrics.loneElement.labels(KamonNameLabel) shouldBe name
        }
      }

      "names that require munging" in {
        val readings = Seq.empty[Long]
        forAll(PrometheusGen.unicodeString → "name") { name ⇒
          val entity = gauge(name, readings)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe Mungers.asMetricName(name)
          result.loneElement.metrics.loneElement.labels(KamonNameLabel) shouldBe name
        }
      }

      "non-help tags" in {
        val name = "gauge"
        val readings = Seq.empty[Long]
        forAll(PrometheusGen.tags → "tags") { tags ⇒
          val entity = gauge(name, tags = tags, readings = readings)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.metrics.loneElement.labels shouldBe
            (tags ++ Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Gauge, KamonNameLabel → name))
        }
      }

      "non-help tags which require munging" in {
        val name = "gauge"
        val readings = Seq.empty[Long]
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(labelName → "label name", PrometheusGen.unicodeString → "label value") { (key, value) ⇒
          val tags = Map(key → value)
          val entity = gauge(name, tags = tags, readings = readings)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          val mungedTags = Map(Mungers.asLabelName(key) → value,
            KamonCategoryLabel → SingleInstrumentEntityRecorder.Gauge,
            KamonNameLabel → name)
          result.loneElement.metrics.loneElement.labels shouldBe mungedTags
        }
      }

      "multiple sets of tags" in {
        val name = "gauge"
        val commonLabels = Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Gauge, KamonNameLabel → name)
        val readings = Seq.empty[Long]
        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1, tags2) ⇒
          whenever(tags1 != tags2 && (tags1 ++ tags2).keys.forall(Metric.isValidLabelName)) {
            val entity1 = gauge(name, tags = tags1, readings = readings)
            val entity2 = gauge(name, tags = tags2, readings = readings)

            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)

            result.loneElement.metrics.map(_.labels) shouldBe Seq(tags1 ++ commonLabels, tags2 ++ commonLabels)
          }
        }
      }

      "units of measurement" which are {
        import SnapshotConverterSpec.{Celsius, Joules}

        "unknown" in {
          val entity = gauge("gauge", unitOfMeasurement = UnitOfMeasurement.Unknown)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge"
        }

        "nanoseconds" in {
          val entity = gauge("gauge", unitOfMeasurement = Time.Nanoseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_nanoseconds"
        }

        "microseconds" in {
          val entity = gauge("gauge", unitOfMeasurement = Time.Microseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_microseconds"
        }

        "milliseconds" in {
          val entity = gauge("gauge", unitOfMeasurement = Time.Milliseconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_milliseconds"
        }

        "seconds" in {
          val entity = gauge("gauge", unitOfMeasurement = Time.Seconds)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_seconds"
        }

        "bytes" in {
          val entity = gauge("gauge", unitOfMeasurement = Memory.Bytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_bytes"
        }

        "kilobytes" in {
          val entity = gauge("gauge", unitOfMeasurement = Memory.KiloBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_kilobytes"
        }

        "megabytes" in {
          val entity = gauge("gauge", unitOfMeasurement = Memory.MegaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_megabytes"
        }

        "gigabytes" in {
          val entity = gauge("gauge", unitOfMeasurement = Memory.GigaBytes)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_gigabytes"
        }

        "hours (custom time type)" in {
          val entity = gauge("gauge", unitOfMeasurement = Time(3600, "h"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_h"
        }

        "terabytes (custom memory type)" in {
          val entity = gauge("gauge", unitOfMeasurement = Time(1024E4, "Tb"))
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_Tb"
        }

        "joules (custom type)" in {
          val entity = gauge("gauge", unitOfMeasurement = Joules)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge_J"
        }

        "celsius (mungeable custom type)" in {
          val entity = gauge("gauge", unitOfMeasurement = Celsius)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result.loneElement.name shouldBe "gauge__C"
        }
      }
    }

    "empty custom metrics" in {
      val name = "test"

      class EmptyMetrics(instrumentFactory: InstrumentFactory) extends GenericEntityRecorder(instrumentFactory)
      object EmptyMetrics extends EntityRecorderFactory[EmptyMetrics] {
        override val category: String = "empty"

        override def createRecorder(instrumentFactory: InstrumentFactory): EmptyMetrics = new EmptyMetrics(instrumentFactory)
      }

      Kamon.metrics.entity[EmptyMetrics](EmptyMetrics, name)
      val entity = Entity(name, EmptyMetrics.category, Map.empty)
      val tick = snapshotOf(entity)

      val result = converter(tick)

      result shouldBe empty
    }

    "dual counter custom metrics" which _have {
      def dualCounter(name: String, count1: Long, count2: Long, tags: Map[String,String] = Map.empty): Entity = {
        val dualCounter = Kamon.metrics.entity(DualCounter, name, tags)
        dualCounter.count1.increment(count1)
        dualCounter.count2.increment(count2)
        Entity(name, DualCounter.category, tags)
      }

      "nothing special" in {
        val name = "test"

        val entity = dualCounter(name, 42, 1)
        val tick = snapshotOf(entity)
        val labels = Map(KamonCategoryLabel → DualCounter.category, KamonNameLabel → name)

        val result = converter(tick)

        result shouldBe
          Seq(
            MetricFamily("dual_counter_count_1", PrometheusType.Counter, None,
              Seq(Metric(MetricValue.Counter(42), end, labels))),
            MetricFamily("dual_counter_count_2", PrometheusType.Counter, None,
              Seq(Metric(MetricValue.Counter(1), end, labels))))
      }

      "arbitrary values" in {
        val name = "test"
        val labels = Map(KamonCategoryLabel → DualCounter.category, KamonNameLabel → name)

        forAll(PrometheusGen.count → "count1", PrometheusGen.count → "count2") { (count1, count2) ⇒
          val entity = dualCounter(name, count1, count2)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          result shouldBe
            Seq(
              MetricFamily("dual_counter_count_1", PrometheusType.Counter, None,
                Seq(Metric(MetricValue.Counter(count1), end, labels))),
              MetricFamily("dual_counter_count_2", PrometheusType.Counter, None,
                Seq(Metric(MetricValue.Counter(count2), end, labels))))
        }
      }

      "non-help tags" in {
        val name = "dual_counter"
        val commonLabels = Map(KamonCategoryLabel → DualCounter.category, KamonNameLabel → name)
        forAll(PrometheusGen.tags → "tags") { tags ⇒
          val entity = dualCounter(name, 1, 2, tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result should have size 2
          all(result.map(_.metrics.loneElement.labels)) shouldBe (tags ++ commonLabels)
        }
      }

      "non-help tags which require munging" in {
        val name = "dual_counter"
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(labelName → "label name", PrometheusGen.unicodeString → "label value") { (key, value) ⇒
          whenever(Mungers.asLabelName(key) != key) {
            val tags = Map(key → value)
            val entity = dualCounter(name, 1, 2, tags)
            val tick = snapshotOf(entity)
            val result = converter(tick)

            val mungedTags = Map(Mungers.asLabelName(key) → value,
              KamonCategoryLabel → DualCounter.category,
              KamonNameLabel → name)
            result should have size 2
            all(result.map(_.metrics.loneElement.labels)) shouldBe mungedTags
          }
        }
      }

      "multiple sets of tags" in {
        val name = "dual_counter"
        val commonLabels = Map(KamonCategoryLabel → DualCounter.category, KamonNameLabel → name)
        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1, tags2) ⇒
          whenever(tags1 != tags2 && (tags1 ++ tags2).keys.forall(Metric.isValidLabelName)) {
            val entity1 = dualCounter(name, 1, 2, tags1)
            val entity2 = dualCounter(name, 3, 4, tags2)

            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)

            result should have size 2
            all(result.map(_.metrics.map(_.labels))) should
              contain theSameElementsAs Seq(tags1 ++ commonLabels, tags2 ++ commonLabels)
          }
        }
      }
    }

    "smorgasbord metrics" which _have {
      def smorgasboard(name: String, count: Long, values: Seq[Long], changes: Seq[Long], readings: Seq[Long],
                       tags: Map[String,String] = Map.empty): Entity = {
        val s = Kamon.metrics.entity(Smorgasbord, name, tags)
        s.aCounter.increment(count)
        values.foreach(s.aHistogram.record)
        changes.grouped(5).foreach { changesChunk ⇒
          changesChunk.foreach(s.aMinMaxCounter.increment)
          s.aMinMaxCounter.refreshValues()
        }
        s.setGaugeReadings(readings)
        readings.foreach(_ ⇒ s.aGage.refreshValue())

        Entity(name, Smorgasbord.category, tags)
      }


      "nothing special" in {
        val name = "test_smorgasbord"
        val count = 3L
        val values = Seq(1L, 2L, 3L, 5L, 8L)
        val changes = Seq(1L, 2L, -1L)
        val readings = Seq(10L, 15L, 20L)
        val labels = Map(KamonCategoryLabel → Smorgasbord.category, KamonNameLabel → name)
        val entity = smorgasboard(name, count, values, changes, readings)
        val tick = snapshotOf(entity)
        val result = converter(tick)

        val histogramBuckets = Seq(
          MetricValue.Bucket(1, 1),
          MetricValue.Bucket(2, 2),
          MetricValue.Bucket(3, 3),
          MetricValue.Bucket(5, 4),
          MetricValue.Bucket(8, 5),
          MetricValue.Bucket(Double.PositiveInfinity, 5)
        )

        val minMaxCounterBuckets = Seq(
          MetricValue.Bucket(0, 1),
          MetricValue.Bucket(2, 5),
          MetricValue.Bucket(3, 6),
          MetricValue.Bucket(Double.PositiveInfinity, 6)
        )

        val gaugeBuckets = Seq(
          MetricValue.Bucket(10, 1),
          MetricValue.Bucket(15, 2),
          MetricValue.Bucket(20, 3),
          MetricValue.Bucket(Double.PositiveInfinity, 3)
        )

        result should contain theSameElementsAs
          Seq(
            MetricFamily("sm_rg_sbord_a_counter", PrometheusType.Counter, None,
              Seq(Metric(MetricValue.Counter(count), end, labels))),
            MetricFamily("sm_rg_sbord_a_histogram_milliseconds", PrometheusType.Histogram, None,
              Seq(Metric(MetricValue.Histogram(histogramBuckets, 5, 19), end, labels))),
            MetricFamily("sm_rg_sbord_a_min_max_counter_kilobytes", PrometheusType.Histogram, None,
              Seq(Metric(MetricValue.Histogram(minMaxCounterBuckets, 6, 11), end, labels))),
            MetricFamily("sm_rg_sbord_a_gauge", PrometheusType.Histogram, None,
              Seq(Metric(MetricValue.Histogram(gaugeBuckets, 3, 45), end, labels)))
          )
      }

      "arbitrary values" in {
        val name = "test"
        val labels = Map(KamonCategoryLabel → Smorgasbord.category, KamonNameLabel → name)

        forAll(
          PrometheusGen.count → "counter value",
          PrometheusGen.instrumentLevels → "histogram values",
          PrometheusGen.minMaxCounterChanges → "min-max counter changes",
          PrometheusGen.instrumentLevels → "gauge readings"
        ) { (count, values, changes, readings) ⇒
          val entity = smorgasboard(name, count, values, changes, readings)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          assert(tick.metrics(entity).histogram("a-histogram").isDefined)
          val histogramValue = MetricValue.Histogram(tick.metrics(entity).histogram("a-histogram").get)

          assert(tick.metrics(entity).minMaxCounter("a-min-max-counter").isDefined)
          val minMaxCounterValue = MetricValue.Histogram(tick.metrics(entity).minMaxCounter("a-min-max-counter").get)

          assert(tick.metrics(entity).gauge("a-gauge").isDefined)
          val gaugeValue = MetricValue.Histogram(tick.metrics(entity).gauge("a-gauge").get)

          result should contain theSameElementsAs
            Seq(
              MetricFamily("sm_rg_sbord_a_counter", PrometheusType.Counter, None,
                Seq(Metric(MetricValue.Counter(count), end, labels))),
              MetricFamily("sm_rg_sbord_a_histogram_milliseconds", PrometheusType.Histogram, None,
                Seq(Metric(histogramValue, end, labels))),
              MetricFamily("sm_rg_sbord_a_min_max_counter_kilobytes", PrometheusType.Histogram, None,
                Seq(Metric(minMaxCounterValue, end, labels))),
              MetricFamily("sm_rg_sbord_a_gauge", PrometheusType.Histogram, None,
                Seq(Metric(gaugeValue, end, labels)))
            )
        }
      }

      "non-help tags" in {
        val name = "test"
        val commonLabels = Map(KamonCategoryLabel → Smorgasbord.category, KamonNameLabel → name)
        forAll(PrometheusGen.tags → "tags") { tags ⇒
          val entity = smorgasboard(name, 1, Seq.empty, Seq.empty, Seq.empty, tags)
          val tick = snapshotOf(entity)
          val result = converter(tick)
          result should have size 4
          all(result.map(_.metrics.loneElement.labels)) shouldBe (tags ++ commonLabels)
        }
      }

      "non-help tags which require munging" in {
        val name = "test"
        val commonLabels = Map(KamonCategoryLabel → Smorgasbord.category, KamonNameLabel → name)
        val labelName = PrometheusGen.unicodeString.suchThat(str ⇒ Mungers.asLabelName(str) != str)

        forAll(labelName → "label name", PrometheusGen.unicodeString → "label value") { (key, value) ⇒
          whenever(Mungers.asLabelName(key) != key) {
            val tags = Map(key → value)
            val entity = smorgasboard(name, 1, Seq.empty, Seq.empty, Seq.empty, tags)
            val tick = snapshotOf(entity)
            val result = converter(tick)

            val mungedTags = Map(Mungers.asLabelName(key) → value) ++ commonLabels
            result should have size 4
            all(result.map(_.metrics.loneElement.labels)) shouldBe mungedTags
          }
        }
      }

      "multiple sets of tags" in {
        val name = "test"
        val commonLabels = Map(KamonCategoryLabel → Smorgasbord.category, KamonNameLabel → name)
        forAll(PrometheusGen.tags → "tags1", PrometheusGen.tags → "tags2") { (tags1, tags2) ⇒
          whenever(tags1 != tags2 && (tags1 ++ tags2).keys.forall(Metric.isValidLabelName)) {
            val entity1 = smorgasboard(name, 1, Seq(1L), Seq(1L), Seq(1L), tags1)
            val entity2 = smorgasboard(name, 2, Seq(2L), Seq(2L), Seq(2L), tags2)

            val tick = snapshotOf(entity1, entity2)
            val result = converter(tick)

            result should have size 4
            all(result.map(_.metrics.map(_.labels))) should
              contain theSameElementsAs Seq(tags1 ++ commonLabels, tags2 ++ commonLabels)
          }
        }
      }
    }

    "Akka actor metrics" which _have {
      def akkaActor(actorName: String, tags: Map[String,String] = Map.empty, timeInMailboxValues: Seq[Long] = Seq.empty,
                    processingTimeValues: Seq[Long] = Seq.empty, mailboxSizeChanges: Seq[Long] = Seq.empty,
                    errorsCount: Long = 0): Entity = {
        val m = Kamon.metrics.entity(ActorMetrics, actorName, tags)
        timeInMailboxValues.foreach(m.timeInMailbox.record)
        processingTimeValues.foreach(m.processingTime.record)
        m.errors.increment(errorsCount)
        mailboxSizeChanges.grouped(5).foreach { changesChunk ⇒
          changesChunk.foreach(m.mailboxSize.increment)
          m.mailboxSize.refreshValues()
        }
        Entity(actorName, ActorMetrics.category, tags)
      }

      "a single actor" in {
        val actorName = "actor-system/user/an-actor"
        val labels = Map(KamonCategoryLabel → "akka-actor", KamonNameLabel → actorName, "actor_name" → actorName)

        forAll(
          PrometheusGen.instrumentLevels → "time in mailbox values",
          PrometheusGen.instrumentLevels → "processing time values",
          PrometheusGen.minMaxCounterChanges → "mailbox size changes",
          PrometheusGen.count → "errors count"
        ) { (timeInMailboxValues, processingTimeValues, mailboxSizeChanges, errorsCount) ⇒
          import DefaultPostprocessor._

          val entity = akkaActor(actorName,
            timeInMailboxValues = timeInMailboxValues,
            processingTimeValues = processingTimeValues,
            mailboxSizeChanges = mailboxSizeChanges,
            errorsCount = errorsCount)
          val tick = snapshotOf(entity)
          val result = converter(tick)

          val timeInMailboxValue = MetricValue.Histogram(tick.metrics(entity).histogram("time-in-mailbox").get)
          val processingTimeValue = MetricValue.Histogram(tick.metrics(entity).histogram("processing-time").get)
          val mailboxSizeValue = MetricValue.Histogram(tick.metrics(entity).minMaxCounter("mailbox-size").get)
          val errorsValue = MetricValue.Counter(tick.metrics(entity).counter("errors").get.count)

          result should contain theSameElementsAs Seq(
            MetricFamily("akka_actor_time_in_mailbox_nanoseconds", PrometheusType.Histogram,
              Some(AkkaActorTimeInMailboxHelp), Seq(Metric(timeInMailboxValue, end, labels))),
            MetricFamily("akka_actor_processing_time_nanoseconds", PrometheusType.Histogram,
              Some(AkkaActorProcessingTimeHelp), Seq(Metric(processingTimeValue, end, labels))),
            MetricFamily("akka_actor_mailbox_size", PrometheusType.Histogram, Some(AkkaActorMailboxSizeHelp),
              Seq(Metric(mailboxSizeValue, end, labels))),
            MetricFamily("akka_actor_errors", PrometheusType.Counter, Some(AkkaActorErrorsHelp),
              Seq(Metric(errorsValue, end, labels)))
          )
        }
      }

      "multiple actors" in {
        import MetricValue.{Bucket ⇒ B}
        val actorName1 = "actor-system/user/actor-1"
        val actorName2 = "actor-system/user/actor-2"
        val labels1 = Map(KamonCategoryLabel → "akka-actor", KamonNameLabel → actorName1, "actor_name" → actorName1)
        val labels2 = Map(KamonCategoryLabel → "akka-actor", KamonNameLabel → actorName2, "actor_name" → actorName2)

        val histogram1 = MetricValue.Histogram(Seq(B(1, 1), B(Double.PositiveInfinity, 1)), 1, 1)
        val histogram2 = MetricValue.Histogram(Seq(B(2, 1), B(Double.PositiveInfinity, 1)), 1, 2)
        val minMaxCount1 = MetricValue.Histogram(Seq(B(0, 1), B(1, 6), B(Double.PositiveInfinity, 6)), 6, 5)
        val minMaxCount2 = MetricValue.Histogram(Seq(B(0, 1), B(2, 6), B(Double.PositiveInfinity, 6)), 6, 10)

        val entity1 = akkaActor(actorName1,
          timeInMailboxValues = Seq(1L),
          processingTimeValues = Seq(1L),
          mailboxSizeChanges = Seq(1L),
          errorsCount = 1)
        val entity2 = akkaActor(actorName2,
          timeInMailboxValues = Seq(2L),
          processingTimeValues = Seq(2L),
          mailboxSizeChanges = Seq(2L),
          errorsCount = 2)
        val tick = snapshotOf(entity1, entity2)
        val result = converter(tick)

        val timeInMailboxMetricFamily = result.find(_.name == "akka_actor_time_in_mailbox_nanoseconds")
        timeInMailboxMetricFamily shouldBe defined
        timeInMailboxMetricFamily.get.metrics should contain theSameElementsAs Seq(
          Metric(histogram1, end, labels1),
          Metric(histogram2, end, labels2))

        val processingTimeMetricFamily = result.find(_.name == "akka_actor_processing_time_nanoseconds")
        processingTimeMetricFamily shouldBe defined
        processingTimeMetricFamily.get.metrics should contain theSameElementsAs Seq(
          Metric(histogram1, end, labels1),
          Metric(histogram2, end, labels2))

        val mailboxSizeMetricFamily = result.find(_.name == "akka_actor_mailbox_size")
        mailboxSizeMetricFamily shouldBe defined
        mailboxSizeMetricFamily.get.metrics should contain theSameElementsAs Seq(
          Metric(minMaxCount1, end, labels1),
          Metric(minMaxCount2, end, labels2))

        val errorsMetricFamily = result.find(_.name == "akka_actor_errors")
        errorsMetricFamily shouldBe defined
        errorsMetricFamily.get.metrics should contain theSameElementsAs Seq(
          Metric(MetricValue.Counter(1), end, labels1),
          Metric(MetricValue.Counter(2), end, labels2))
      }
    }

    "Akka dispatcher metrics" which _have {
      import DefaultPostprocessor._

//      "a fork join pool" in {
//        val dispatcherName = "test-system/fork-join-pool"
//        val forkJoinPool =  new ForkJoinPool()
//        val tags: Map[String, String] = Map("dispatcher-type" → "fork-join-pool")
//        Kamon.metrics.entity(ForkJoinPoolMetrics.factory(forkJoinPool, AkkaDispatcherMetrics.Category),
//          dispatcherName, tags)
//        val entity = Entity(dispatcherName, "akka-dispatcher", tags)
//        val tick = snapshotOf(entity)
//        val result = converter(tick)
//        val labels = Map(
//          "dispatcher_type" → "fork-join-pool",
//          KamonCategoryLabel → "akka-dispatcher",
//          KamonNameLabel → dispatcherName,
//          "dispatcher_name" → dispatcherName)
//
//        result should contain theSameElementsAs
//        Seq(
//          MetricFamily(
//            "akka_fork_join_pool_dispatcher_parallelism",
//            PrometheusType.Histogram,
//            Some(AkkaForkJoinPoolDispatcherParallelismHelp),
//            Seq(Metric(MetricValue.Histogram(tick.metrics(entity).minMaxCounter("parallelism").get), end, labels))),
//          MetricFamily(
//            "akka_fork_join_pool_dispatcher_pool_size",
//            PrometheusType.Histogram,
//            Some(AkkaForkJoinPoolDispatcherPoolSizeHelp),
//            Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("pool-size").get), end, labels))),
//          MetricFamily(
//            "akka_fork_join_pool_dispatcher_active_threads",
//            PrometheusType.Histogram,
//            Some(AkkaForkJoinPoolDispatcherActiveThreadsHelp),
//            Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("active-threads").get), end, labels))),
//          MetricFamily(
//            "akka_fork_join_pool_dispatcher_running_threads",
//            PrometheusType.Histogram,
//            Some(AkkaForkJoinPoolDispatcherRunningThreadsHelp),
//            Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("running-threads").get), end, labels))),
//          MetricFamily(
//            "akka_fork_join_pool_dispatcher_queued_task_count",
//            PrometheusType.Histogram,
//            Some(AkkaForkJoinPoolDispatcherQueuedTaskCountHelp),
//            Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("queued-task-count").get), end, labels))))
//      }

      "a thread pool executor" in {
        val dispatcherName = "test-system/thread-pool-executor"
        val threadPoolExecutor = new ThreadPoolExecutor(2, 4, 10, TimeUnit.SECONDS,
          new ArrayBlockingQueue[Runnable](10))

        val tags: Map[String, String] = Map("dispatcher-type" → "thread-pool-executor")
        Kamon.metrics.entity(ThreadPoolExecutorMetrics.factory(threadPoolExecutor, AkkaDispatcherMetrics.Category),
          dispatcherName, tags)
        val entity = Entity(dispatcherName, "akka-dispatcher", tags)
        val tick = snapshotOf(entity)
        val result = converter(tick)
        val labels = Map(
          "dispatcher_type" → "thread-pool-executor",
          KamonCategoryLabel → "akka-dispatcher",
          KamonNameLabel → dispatcherName,
          "dispatcher_name" → dispatcherName)

        result should contain theSameElementsAs
          Seq(
            MetricFamily(
              "akka_thread_pool_executor_dispatcher_core_pool_size",
              PrometheusType.Histogram,
              Some(AkkaThreadPoolExecutorDispatcherCorePoolSizeHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("core-pool-size").get), end, labels))),
            MetricFamily(
              "akka_thread_pool_executor_dispatcher_max_pool_size",
              PrometheusType.Histogram,
              Some(AkkaThreadPoolExecutorDispatcherMaxPoolSizeHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("max-pool-size").get), end, labels))),
            MetricFamily(
              "akka_thread_pool_executor_dispatcher_pool_size",
              PrometheusType.Histogram,
              Some(AkkaThreadPoolExecutorDispatcherPoolSizeHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("pool-size").get), end, labels))),
            MetricFamily(
              "akka_thread_pool_executor_dispatcher_active_threads",
              PrometheusType.Histogram,
              Some(AkkaThreadPoolExecutorDispatcherActiveThreadsHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("active-threads").get), end, labels))),
            MetricFamily(
              "akka_thread_pool_executor_dispatcher_processed_tasks",
              PrometheusType.Histogram,
              Some(AkkaThreadPoolExecutorDispatcherProcessedTasksHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).gauge("processed-tasks").get), end, labels))))
      }
    }

    "Akka router metrics" in {
      val routerName = "test/user/my-router"
      forAll(
        PrometheusGen.instrumentLevels → "routing times",
        PrometheusGen.instrumentLevels → "times in mailbox",
        PrometheusGen.instrumentLevels → "processing times",
        PrometheusGen.count → "errors count"
      )  { (routingTimes, timesInMailbox, processingTimes, errorsCount) ⇒
        import DefaultPostprocessor._

        val routerMetrics = Kamon.metrics.entity(RouterMetrics, routerName)
        routingTimes.foreach(routerMetrics.routingTime.record)
        timesInMailbox.foreach(routerMetrics.timeInMailbox.record)
        processingTimes.foreach(routerMetrics.processingTime.record)
        routerMetrics.errors.increment(errorsCount)
        val entity = Entity(routerName, RouterMetrics.category)
        val tick = snapshotOf(entity)
        val result = converter(tick)

        val labels = Map(
          KamonCategoryLabel → "akka-router",
          KamonNameLabel → routerName,
          "router_name" → routerName)

        result should contain theSameElementsAs
          Seq(
            MetricFamily(
              "akka_router_routing_time_nanoseconds",
              PrometheusType.Histogram,
              Some(AkkaRouterRoutingTimeHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).histogram("routing-time").get), end, labels))),
            MetricFamily(
              "akka_router_time_in_mailbox_nanoseconds",
              PrometheusType.Histogram,
              Some(AkkaRouterTimeInMailboxHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).histogram("time-in-mailbox").get), end, labels))),
            MetricFamily(
              "akka_router_processing_time_nanoseconds",
              PrometheusType.Histogram,
              Some(AkkaRouterProcessingTimeHelp),
              Seq(Metric(MetricValue.Histogram(tick.metrics(entity).histogram("processing-time").get), end, labels))),
            MetricFamily(
              "akka_router_errors",
              PrometheusType.Counter,
              Some(AkkaRouterErrorsHelp),
              Seq(Metric(MetricValue.Counter(tick.metrics(entity).counter("errors").get.count), end, labels))))
      }
    }

    "its settings" when {
      "additional labels are specified" in {
        val name = "counter"
        val count = 42
        val entity = counter(name, count)
        val tick = snapshotOf(entity)
        val config = ConfigFactory.parseString("kamon.prometheus.labels.foo=\"bar\"")
          .withFallback(KamonTestKit.TestConfig).withFallback(ConfigFactory.load())
        val converter = new SnapshotConverter(new PrometheusSettings(config))
        val result = converter(tick)

        result.loneElement shouldBe
          MetricFamily(name, PrometheusType.Counter, None,
            Seq(Metric(MetricValue.Counter(count), end,
              Map("foo" → "bar",
                KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
                KamonNameLabel → name))))
      }

      "additional labels that require munging are specified" in {
        val name = "counter"
        val count = 42
        val entity = counter(name, count)
        val tick = snapshotOf(entity)
        val config = ConfigFactory.parseString("kamon.prometheus.labels.needs-munging=\"bar\"")
          .withFallback(KamonTestKit.TestConfig).withFallback(ConfigFactory.load())
        val converter = new SnapshotConverter(new PrometheusSettings(config))
        val result = converter(tick)

        result.loneElement shouldBe
          MetricFamily(name, PrometheusType.Counter, None,
            Seq(Metric(MetricValue.Counter(count), end,
              Map("needs_munging" → "bar",
                KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
                KamonNameLabel → name))))
      }
    }
  }
}

object SnapshotConverterSpec {
  case object Joules extends UnitOfMeasurement {
    override val name = "energy"
    override val label = "J"
    override protected def canScale(toUnit: UnitOfMeasurement): Boolean = false
  }

  case object Celsius extends UnitOfMeasurement {
    override val name = "temperature"
    override val label = "°C"
    override protected def canScale(toUnit: UnitOfMeasurement): Boolean = false
  }
}
