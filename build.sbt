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

lazy val commonSettings = Seq(
  libraryDependencies += "org.scalatest" %% "scalatest" % scalatestVersion % Test
)

// Dependency rule (ADR-0002): compiler and sim depend only on isa, never on each other.
// Cross-checks between them belong to a separate test project added in #10.
lazy val isa = (project in file("isa"))
  .settings(commonSettings)

lazy val compiler = (project in file("compiler"))
  .dependsOn(isa)
  .settings(commonSettings)

lazy val sim = (project in file("sim"))
  .dependsOn(isa)
  .settings(commonSettings)

lazy val root = (project in file("."))
  .aggregate(isa, compiler, sim)
  .settings(
    name := "deterministic-vliw-chisel",
    publish / skip := true
  )
