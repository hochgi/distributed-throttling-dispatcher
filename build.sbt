lazy val commonSettings = Seq(
  organization := "com.hochgi",
  version := "0.0.1-SNAPSHOT",
  scalaVersion := "2.12.3",
  libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
)

/*
 * MODULES:
 */

lazy val common = (project in file("common"))
.enablePlugins(ContrabandPlugin)
.settings(
  commonSettings,
  name := "common"
)

lazy val jobDispatcher = (project in file("jobs-dispatcher")) //.enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "jobs-dispatcher",
    libraryDependencies += "com.typesafe.akka" %% "akka-stream-kafka" % "0.17"
  )
