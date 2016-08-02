package be.wegenenverkeer

import akka.util.ByteString
import be.wegenenverkeer.rxhttp.RxHttpClient
import be.wegenenverkeer.rxhttp.scala.ImplicitConversions._
import com.monsanto.arch.kamon.prometheus.demo.{DemoSettings, LoadGenerator}
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.play.PlayExtension
import kamon.play.di.KamonAPI
import kamon.trace.Tracer
import kamon.util.SameThreadExecutionContext
import play.api.libs.streams.Accumulator
import play.api.mvc.{EssentialAction, EssentialFilter, RequestHeader, Result}
import play.api.routing.Router
import play.api.routing.sird._
import play.api.{BuiltInComponents, LoggerConfigurator}
import play.core.server._

/** A simple demo application that generates metrics that can be scraped by Prometheus.
  *
  */
object Demo extends App {
  // load settings
  val settings = new DemoSettings(ConfigFactory.load())

  private val filter: EssentialFilter = new EssentialFilter {
    override def apply(next: EssentialAction): EssentialAction = new EssentialAction {
      override def apply(requestHeader: RequestHeader): Accumulator[ByteString, Result] = {

        def onResult(result: Result): Result = {
          Tracer.currentContext.collect { ctx ⇒
            ctx.finish()
            PlayExtension.httpServerMetrics.recordResponse(ctx.name, result.header.status.toString)
            if (PlayExtension.includeTraceToken) result.withHeaders(PlayExtension.traceTokenHeaderName -> ctx.token)
            else result

          } getOrElse result
        }

        //override the current trace name
        Tracer.currentContext.rename(PlayExtension.generateTraceName(requestHeader))
        val nextAccumulator = next.apply(requestHeader)
        nextAccumulator.map(onResult)(SameThreadExecutionContext)
      }
    }
  }

  val application = new NettyServerComponents with BuiltInComponents {

    //configure server settings
    override lazy val serverConfig = ServerConfig(
      address = settings.hostname,
      port = Some(settings.port)
    )

    //configure logging
    LoggerConfigurator(environment.classLoader).foreach { _.configure(environment) }

    //start play Kamon
    new KamonAPI(applicationLifecycle, environment)
    //register Prometheus Play extension
    private val prometheusPlayExtension: PrometheusPlayExtension = actorSystem.registerExtension(PrometheusPlay)

    val demoController = new DemoController()

    lazy val router = Router.from({
      case GET(p"/") => demoController.demo
      case POST(p"/counter/${int(counterNumber)}") => demoController.incrementCounter(counterNumber)
      case POST(p"/min-max-counter") => demoController.minMaxCounter()
      case POST(p"/histogram") => demoController.histogram()
      case GET(p"/timeout") => demoController.timesOut()
      case GET(p"/error") => demoController.error()
      case GET(p"/metrics") => prometheusPlayExtension.controller.metrics
    })

    override lazy val httpFilters = Seq(filter)

  }

  // start the Netty server
  val server = application.server

  // once bound, start tasks
  val demoClient = new RxHttpClient.Builder()
    .setRequestTimeout(1000)
    .setMaxConnections(20)
    .setConnectionTTL(60000)
    .setConnectTimeout(1000)
    .setAccept("*/*")
    .setBaseUrl(s"http://localhost/${settings.port}")
    .build
    .asScala

  application.actorSystem.actorOf(LoadGenerator.props(demoClient).withDispatcher("my-fork-join-dispatcher"), "load-generator-1")
  application.actorSystem.actorOf(LoadGenerator.props(demoClient).withDispatcher("my-thread-pool-dispatcher"), "load-generator-2")
  // start a gauge that measures random values
  Kamon.metrics.gauge("basic-gauge") {
    val gen: () ⇒ Long = {() ⇒
      (math.pow(10.0, math.random * 2.74116) + 98.12 + (math.random * 500 - 250)).toLong.max(0L)
    }
    gen
  }

  scala.io.StdIn.readLine()
  Kamon.shutdown()
  demoClient.close()
  server.stop()

}
