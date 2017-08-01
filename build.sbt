import java.util.Date
import sbtprotobuf.ProtobufPlugin

val akkaVersion = "2.5.3"
val playVersion = "2.6.2"

lazy val commonSettings = Seq(
  organization := "be.wegenenverkeer",
  licenses := Seq("BSD New" → url("http://opensource.org/licenses/BSD-3-Clause")),
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq("2.11.11", "2.12.3"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked"
  ),
  resolvers ++= Seq(
    "Local Maven" at Path.userHome.asFile.toURI.toURL + ".m2/repository",
    Resolver.typesafeRepo("releases")
  ),
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  apiMappingsScala ++= Map(
    ("com.typesafe.akka", "akka-actor") → "http://doc.akka.io/api/akka/%s",
    ("io.spray", "spray-routing") → "http://spray.io/documentation/1.1-SNAPSHOT/api/"
  ),
  apiMappingsJava ++= Map(
    ("com.typesafe", "config") → "http://typesafehub.github.io/config/latest/api"
  )
)

lazy val publishSettings: Seq[Setting[_]] = Seq(
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  publishTo <<= version { (v: String) =>
    val nexus = "https://collab.mow.vlaanderen.be/artifacts/repository/maven-"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("collab snapshots" at nexus + "snapshots")
    else
      Some("collab releases" at nexus + "releases")
  },
  //    publishMavenStyle := true,
  publishArtifact in Compile := true,
  publishArtifact in Test := true
)

val noPublishing = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val library = (project in file("library"))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .settings(commonSettings: _*)
  .settings(publishSettings: _*)
  .settings(ProtobufPlugin.protobufSettings: _*)
  .settings(
    name := "kamon-prometheus",
    description := "Kamon module to export metrics to Prometheus",
    libraryDependencies ++= Seq(
      "io.kamon"               %% "kamon-core"               % "0.6.7",
      "com.typesafe.play"      %% "play"                     % playVersion,
      "com.typesafe.akka"      %% "akka-actor"               % akkaVersion,
      "com.typesafe"            % "config"                   % "1.3.1",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.6" % "provided",
      // -- testing --
      "com.typesafe.play" %% "play-test"       % playVersion  % "test",
      "org.scalatest"     %% "scalatest"       % "3.0.3"      % "test",
      "com.typesafe.akka" %% "akka-testkit"    % akkaVersion  % "test",
      "org.scalacheck"    %% "scalacheck"      % "1.13.5"     % "test",
      "io.kamon"          %% "kamon-akka-2.5"  % "0.6.7"      % "test"
    ),
    dependencyOverrides ++= Set(
      "org.scala-lang"          % "scala-library" % scalaVersion.value,
      "org.scala-lang"          % "scala-reflect" % scalaVersion.value,
      "org.scala-lang.modules" %% "scala-xml"     % "1.0.6"
    ),
    version in ProtobufPlugin.protobufConfig := "3.3.1",

    // We have to ensure that Kamon starts/stops serially
    parallelExecution in Test := false,
    // Don't count Protobuf-generated code in coverage
    coverageExcludedPackages := "com\\.monsanto\\.arch\\.kamon\\.prometheus\\.metric\\..*"
  )

lazy val demo = (project in file("demo"))
  .dependsOn(library)
  .enablePlugins(DockerPlugin)
  .settings(commonSettings: _*)
  .settings(aspectjSettings: _*)
  .settings(noPublishing: _*)
  .settings(
    name := "kamon-prometheus-demo",
    description := "Docker image containing a demonstration of kamon-prometheus in action.",
    libraryDependencies ++= Seq(
      "io.kamon"          %% "kamon-system-metrics" % "0.6.7",
      ("io.kamon"          %% "kamon-play-2.6"      % "0.6.8").exclude("org.asynchttpclient", "async-http-client").exclude("commons-logging", "commons-logging"),
      "io.kamon"          %% "kamon-autoweave"      % "0.6.5",
      "be.wegenenverkeer" %% "rxhttpclient-scala"   % "0.5.2",
      "com.typesafe.play" %% "play-netty-server"    % playVersion,
      "com.typesafe.play" %% "play-logback"         % playVersion
    ),
    fork in run := true,
    javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj,
    javaOptions in reStart <++= AspectjKeys.weaverOptions in Aspectj,
    assemblyMergeStrategy in assembly := {
      case PathList("org", "aspectj", xs @ _*)         => MergeStrategy.last
      case PathList(ps @ _*) if ps.last == "io.netty.versions.properties"                => MergeStrategy.first
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    assemblyJarName in assembly <<= (name, version) map { (name, version) ⇒ s"$name-$version.jar" },
    docker <<= docker.dependsOn(assembly),
    imageName in docker := ImageName(
      namespace = Some("monsantoco"),
      repository = "kamon-prometheus-demo",
      tag = Some("latest")
    ),
    dockerfile in docker := {
      import sbtdocker.Instructions._

      val prometheusVersion = "0.15.1"
      val grafanaVersion = "2.1.3"

      val dockerSources = (sourceDirectory in Compile).value / "docker"
      val supervisordConf = dockerSources / "supervisord.conf"
      val prometheusYaml = dockerSources / "prometheus.yml"
      val grafanaRules = dockerSources / "grafana.rules"
      val grafanaIni = dockerSources / "grafana.ini"
      val grafanaDb = dockerSources / "grafana.db"
      val demoAssembly = (assemblyOutputPath in assembly).value
      val weaverAgent = (AspectjKeys.weaver in Aspectj).value.get
      val grafanaPluginsHash = "27f1398b497650f5b10b983ab9507665095a71b3"

      val instructions = Seq(
        From("java:8-jdk"),
        WorkDir("/tmp"),
        Raw("RUN", Seq(
          // install supervisor
          "apt-get update && apt-get -y install supervisor",
          // install Prometheus
          s"curl -L https://github.com/prometheus/prometheus/releases/download/$prometheusVersion/prometheus-$prometheusVersion.linux-amd64.tar.gz | tar xz",
          "mv prometheus /usr/bin",
          "mkdir -p /etc/prometheus",
          "mv ./consoles ./console_libraries /etc/prometheus",
          "mkdir -p /var/lib/prometheus",
          // install Grafana
          "apt-get install -y adduser libfontconfig",
          s"curl -L -o grafana.deb https://grafanarel.s3.amazonaws.com/builds/grafana_${grafanaVersion}_amd64.deb",
          "dpkg -i grafana.deb",
          s"curl -L https://github.com/grafana/grafana-plugins/archive/$grafanaPluginsHash.tar.gz | tar xz",
          s"mv grafana-plugins-$grafanaPluginsHash/datasources/prometheus /usr/share/grafana/public/app/plugins/datasource",
          // clean up
          "rm -rf /tmp/* /var/lib/apt/lists/*"
        ).mkString(" && ")),
        // configure and use supervisor
        Copy(CopyFile(supervisordConf), "/etc/supervisor/conf.d/prometheus-demo.conf"),
        EntryPoint.exec(Seq("/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf")),
        // install the demo application
        Copy(CopyFile(demoAssembly), "/usr/share/kamon-prometheus-demo/demo.jar"),
        Copy(CopyFile(weaverAgent), "/usr/share/kamon-prometheus-demo/weaverAgent.jar"),
        // configure Prometheus
        Copy(Seq(CopyFile(prometheusYaml), CopyFile(grafanaRules)), "/etc/prometheus/"),
        // configure Grafana
        Copy(CopyFile(grafanaIni), "/etc/grafana/grafana.ini"),
        Copy(CopyFile(grafanaDb), "/var/lib/grafana/grafana.db"),
        // expose ports
        Expose(Seq(9000, 3000, 9090))
      )
      sbtdocker.immutable.Dockerfile(instructions)
    },
    // Don't count demo code in coverage
    coverageExcludedPackages := "com\\.monsanto\\.arch\\.kamon\\.prometheus\\.demo\\..*"
  )

lazy val ghPagesSettings =
  ghpages.settings ++
  Seq(
    git.remoteRepo := "git@github.com:MonsantoCo/kamon-prometheus.git"
  )

lazy val siteSettings =
  site.settings ++
  site.addMappingsToSiteDir(mappings in packageDoc in Compile in library, "api/snapshot") ++
  site.asciidoctorSupport()

lazy val `kamon-prometheus` = (project in file("."))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .aggregate(library, demo)
  .settings(commonSettings: _*)
  .settings(noPublishing: _*)
