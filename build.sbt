ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "3.2.0"

lazy val root = (project in file("."))
  .settings(
    name := "Assignment 03"
  )

val AKKA_VERSION = "2.7.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AKKA_VERSION,
  "org.slf4j" % "slf4j-simple" % "2.0.3"
)
