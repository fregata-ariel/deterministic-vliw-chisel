// Versions are fixed in docs/DECISIONS.md (ADR-0004). Change them together with that record.
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / organization := "vliw"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Werror"
)

val scalatestVersion = "3.2.20"
val ujsonVersion = "4.4.3"

// Tests and `run` fork with the repository root as working directory, so paths such as
// config/machine.json resolve the same way as in the CLI.
lazy val commonSettings = Seq(
  libraryDependencies += "org.scalatest" %% "scalatest" % scalatestVersion % Test,
  Test / fork := true,
  Compile / run / fork := true,
  Compile / run / javaOptions ++= Seq("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"),
  Compile / run / outputStrategy := Some(StdoutOutput),
  Test / javaOptions ++= Seq("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"),
  Test / forkOptions := (Test / forkOptions).value
    .withWorkingDirectory((LocalRootProject / baseDirectory).value),
  Compile / run / forkOptions := (Compile / run / forkOptions).value
    .withWorkingDirectory((LocalRootProject / baseDirectory).value)
)

// Dependency rule (ADR-0002): compiler and sim depend only on isa, never on each other.
// Cross-checks between them live in the crosscheck test project.
lazy val isa = (project in file("isa"))
  .settings(commonSettings)
  .settings(libraryDependencies += "com.lihaoyi" %% "ujson" % ujsonVersion)

lazy val compiler = (project in file("compiler"))
  .dependsOn(isa)
  .settings(commonSettings)

lazy val sim = (project in file("sim"))
  .dependsOn(isa)
  .settings(commonSettings)

// Differential tests between the verifier and the reference model (ADR-0002). Test code only.
lazy val crosscheck = (project in file("crosscheck"))
  .dependsOn(compiler, sim)
  .settings(commonSettings)
  .settings(publish / skip := true)

lazy val root = (project in file("."))
  .aggregate(isa, compiler, sim, crosscheck)
  .settings(
    name := "deterministic-vliw-chisel",
    publish / skip := true
  )
