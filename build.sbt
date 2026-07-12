ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "dev.ubugeeei"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val verify = taskKey[Unit]("Run the dependency-free test suite")
lazy val verifyEnglish = taskKey[Unit]("Reject Japanese prose from the English handbook")

lazy val root = project
  .in(file("."))
  .settings(
    name := "learn-at",
    Compile / unmanagedSources :=
      ((baseDirectory.value / "src") ** "*.scala").get.filterNot(_.getName.endsWith(".test.scala")),
    Compile / run / mainClass := Some("learnat.LearnAt"),
    Test / unmanagedSources := ((baseDirectory.value / "src") ** "*.test.scala").get,
    Test / unmanagedResourceDirectories := Seq(baseDirectory.value / "src" / "test" / "resources"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Werror",
      "-Wunused:all"
    ),
    Compile / run / fork := true,
    Test / fork := true,
    verifyEnglish := {
      val base = baseDirectory.value
      val files = Seq(base / "README.md") ++
        ((base / "docs") ** "*.md").get ++
        ((base / "src") ** "*.scala").get
      val japanese = (
        "[" + 0x3040.toChar + "-" + 0x30ff.toChar +
          0x3400.toChar + "-" + 0x4dbf.toChar +
          0x4e00.toChar + "-" + 0x9fff.toChar + "]"
      ).r
      val violations = files.flatMap { file =>
        IO.readLines(file).zipWithIndex.collect {
          case (line, index) if japanese.findFirstIn(line).nonEmpty => s"${file.relativeTo(base).get}:${index + 1}"
        }
      }
      if (violations.nonEmpty) sys.error(s"Japanese text found in English repository files: ${violations.mkString(", ")}")
    },
    verify := {
      verifyEnglish.value
      (Test / runMain).toTask(" learnat.tests.AllTests").value
    }
  )
