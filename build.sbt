name := """some-project-name"""

version := "1.0-SNAPSHOT"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

startYear := Some(2015)

description := "some-project-description"

scalaVersion := "2.11.7"

developers := List(Developer("arturopala","Artur Opala","opala.artur@gmail.com",url("https://pl.linkedin.com/in/arturopala")))

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += Resolver.jcenterRepo

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.3",
  "org.scalaz" %% "scalaz-effect" % "7.1.3",
  "com.chuusai" %% "shapeless" % "2.2.5",
  "org.scalatest" %% "scalatest" % "2.2.5" % Test,
  "org.scalacheck" %% "scalacheck" % "1.12.2" % Test
)

import scalariform.formatter.preferences._

com.typesafe.sbt.SbtScalariform.scalariformSettings

ScalariformKeys.preferences := PreferencesImporterExporter.loadPreferences(baseDirectory.value / "project" / "formatterPreferences.properties" toString)

net.virtualvoid.sbt.graph.Plugin.graphSettings

coverageEnabled := false

fork := true

connectInput in run := true

outputStrategy := Some(StdoutOutput)
