addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3")

addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-aspectj" % "0.10.4")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.2")

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.4.0")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.3.5")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.8.2")

addSbtPlugin("com.github.gseitz" % "sbt-protobuf" % "0.5.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.4")

addSbtPlugin("org.scoverage" % "sbt-coveralls" % "1.0.3")

dependencyOverrides ++= Set(
  "com.typesafe.sbt" % "sbt-site" % "0.8.2"
)
