lazy val commonSettings = Seq(
  organization := "com.hochgi",
  version := "0.0.1-SNAPSHOT",
  scalaVersion := "2.12.3",
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2")
)

/*
 * MODULES:
 */

lazy val common = (project in file("common"))
.enablePlugins(ContrabandPlugin,JsonCodecPlugin)
.settings(
  commonSettings,
  name := "common",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % Versions.akka,
    "com.eed3si9n" %% "sjson-new-scalajson" % "0.8.1",
    "org.lz4" % "lz4-java" % "1.4.0")
  // we can use a nice trick to automatically set `Job` versions according to git with sbt-buildinfo plugin
  // buildInfoKeys := Seq[BuildInfoKey](
  //   BuildInfoKey.action("gitCommitTimestampVersion") {
  //     Process("git show -s --format=%ct-%H").lines.head
  //   }
  // )
)

lazy val jobDispatcher = (project in file("job-dispatcher"))
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "job-dispatcher",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-kafka" % Versions.akkaStreamKafka,
      "com.typesafe.akka" %% "akka-stream" % Versions.akka,
      "com.typesafe.akka" %% "akka-remote" % Versions.akka)
  )

lazy val worker = (project in file("worker"))
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "worker",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream-kafka" % Versions.akkaStreamKafka,
      "com.typesafe.akka" %% "akka-stream" % Versions.akka,
      "com.typesafe.akka" %% "akka-remote" % Versions.akka)
  )

lazy val throttlingService = (project in file("throttling-service"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(common)
  .settings(
    commonSettings,
    name := "throttling-service",
    mainClass in Compile := Some("hochgi.assignment.pp.ThrottlingService"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-remote" % Versions.akka,
      "com.typesafe.akka" %% "akka-stream" % Versions.akka)
  )

/*
 * EXAMPLE IMPLEMENTATIONS
 */

lazy val exampleJobDispatcher = (project in file("example-job-dispatcher"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(jobDispatcher)
  .settings(
    commonSettings,
    name := "example-job-dispatcher",
    mainClass in Compile := Some("hochgi.assignment.pp.example.ExampleJobDispatcher")
  )

lazy val exampleWorkerIgnorer = (project in file("example-worker-ignorer"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(worker)
  .settings(
    commonSettings,
    name := "example-worker-ignorer",
    mainClass in Compile := Some("hochgi.assignment.pp.example.ExampleWorkerIgnorer")
  )

lazy val exampleWorkerPrinter = (project in file("example-worker-printer"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(worker)
  .settings(
    commonSettings,
    name := "example-worker-printer",
    mainClass in Compile := Some("hochgi.assignment.pp.example.ExampleWorkerPrinter")
  )