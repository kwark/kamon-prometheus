package com.monsanto.arch.kamon.prometheus.demo

import com.typesafe.config.{Config, ConfigFactory}

case class DemoSettings(config: Config) {
  config.checkValid(ConfigFactory.defaultReference(), "demo")
  val hostname: String = config.getString("demo.interface")
  val port: Int = config.getInt("demo.port")
}
