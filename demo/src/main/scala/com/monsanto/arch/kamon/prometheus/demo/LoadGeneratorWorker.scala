package com.monsanto.arch.kamon.prometheus.demo

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import be.wegenenverkeer.rxhttp.{ClientRequest, RxHttpClient, scala}
import be.wegenenverkeer.rxhttp.scala.ImplicitConversions._

/** Does the real work of generating load.
  *
  * @param demoClient the base URI where the server is listening
  */
class LoadGeneratorWorker(demoClient: scala.RxHttpClient, master: ActorRef) extends Actor {
  import LoadGenerator.LoadType._
  import context.dispatcher

  private case object Done

  private def handleRequest(request: ClientRequest) = {
    demoClient.execute(request, _ => Done) pipeTo self
  }

  override def receive = {
    case t: LoadGenerator.LoadType ⇒
      t match {
        case IncrementCounter(x) ⇒
          handleRequest(demoClient.requestBuilder().setMethod("POST").setUrlRelativetoBase(s"/counter/${x.toString}").build())
        case GetRejection ⇒
          handleRequest(demoClient.requestBuilder().setMethod("POST").setUrlRelativetoBase("/counter/2000").build())
        case GetTimeout ⇒
          handleRequest(demoClient.requestBuilder().setUrlRelativetoBase("/timeout").build())
        case GetError ⇒
          handleRequest(demoClient.requestBuilder().setUrlRelativetoBase("/error").build())
        case UpdateHistogram ⇒
          handleRequest(demoClient.requestBuilder().setMethod("POST").setUrlRelativetoBase("/histogram").build())
        case UpdateMinMaxCounter ⇒
          handleRequest(demoClient.requestBuilder().setMethod("POST").setUrlRelativetoBase("/min-max-counter").build())
      }
    case Done ⇒
      LoadGenerator.bomb()
      master ! LoadGenerator.Message.LoadFinished
  }

}

object LoadGeneratorWorker {
  def props(demoClient: scala.RxHttpClient, master: ActorRef): Props = Props(new LoadGeneratorWorker(demoClient, master))
}
