ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "dev.ubugeeei"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val verify = taskKey[Unit]("Run the dependency-free test suite")

lazy val root = project
  .in(file("."))
  .settings(
    name := "learn-at",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Werror",
      "-Wunused:all"
    ),
    Compile / run / fork := true,
    Test / fork := true,
    verify := (Test / runMain).toTask(" learnat.tests.AllTests").value
  )

