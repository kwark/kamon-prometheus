package be.wegenenverkeer

import be.wegenenverkeer.DemoController._
import kamon.Kamon
import play.api.mvc.{Action, Controller}

import scala.concurrent.Future
import scala.concurrent.duration._

/** A simple spray service that contains a simple endpoint and a metrics endpoint that Prometheus can consume.
  *
  * @author Daniel Solano Gómez
  */
class DemoController extends Controller {

  var minMaxCount = 0L

  /** A simple endpoint for `/` which also gives all traces that end up here the name ‘demo-endpoint’. */
  def demo = Action(Ok("Welcome to the demo for Kamon/Prometheus."))

  /** A route used to increment a counter. */
  def incrementCounter(counterNumber: Int) = Action {
    if (counterNumber >= 0 && counterNumber < CounterNames.size) {
      val name = CounterNames(counterNumber)
      Kamon.metrics.counter("basic-counter", Map("name" → name)).increment()
      Ok(s"Incremented counter #$name")
    } else {
      BadRequest(s"Invalid counter number: $counterNumber")
    }
  }

  def minMaxCounter() = Action {
      val mmc = Kamon.metrics.minMaxCounter("basic-min-max-counter", refreshInterval = 5.seconds)
      val change = ((math.random * 50.0).toLong - 25L).max(-minMaxCount)
      minMaxCount += change
      assert(minMaxCount >= 0)

      mmc.increment(change)

      Ok(minMaxCount.toString)
  }

  def histogram() = Action {
    val h = Kamon.metrics.histogram("basic-histogram")
    val value = System.currentTimeMillis() % 1000L
    h.record(value)

    Ok(value.toString)
  }

  import play.api.libs.concurrent.Execution.Implicits._

  def timesOut() = Action.async {
    Future {
      Thread.sleep(1100.milliseconds.toMillis)
      "This should time out."
    }.map(Ok(_))
  }

  def error() = Action {
    InternalServerError("error")
  }

}

object DemoController {
  /** The valid counter names. */
  val CounterNames = IndexedSeq("α", "β", "γ", "δ", "ε")
}



