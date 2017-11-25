name := """some-project-name"""

version := "1.0-SNAPSHOT"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

startYear := Some(2016)

description := "some-project-description"

scalaVersion := "2.12.1"

developers := List(Developer("arturopala","Artur Opala","opala.artur@gmail.com",url("https://pl.linkedin.com/in/arturopala")))

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

val akkaVersion = "2.4.14"
val akkaHttpVersion = "10.0.0"

val funcDependencies = Seq(
  "org.typelevel" %% "cats" % "0.8.1",
  "com.chuusai" %% "shapeless" % "2.3.2"
)

val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion
)

val akkaHttpDependecies = Seq(
  "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-jackson" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion
)

val testDependencies = Seq(
   "org.scalatest" %% "scalatest" % "3.0.1" % Test,
   "org.scalacheck" %% "scalacheck" % "1.13.4" % Test
)

libraryDependencies ++= testDependencies ++ funcDependencies ++ akkaDependencies ++ akkaHttpDependecies

import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

ScalariformKeys.preferences := PreferencesImporterExporter.loadPreferences(baseDirectory.value / "project" / "formatterPreferences.properties" toString)

coverageEnabled := false

fork := true

connectInput in run := true

outputStrategy := Some(StdoutOutput)

import de.heikoseeberger.sbtheader.license.Apache2_0

headers := Map(
  "scala" -> Apache2_0("2016", "Artur Opala"),
  "conf"  -> Apache2_0("2016", "Artur Opala", "#")
)
