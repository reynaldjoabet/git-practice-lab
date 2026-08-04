import Dependencies._

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-no-indent",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-explain", // + actionable error messages
  "-source:3.3",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wsafe-init",
  "-language:strictEquality", // + catch nonsensical == (Money vs String, etc.)
  "-Xkind-projector",
  "-Xmax-inlines",
  "64"
)

lazy val root = (project in file("."))
  .settings(
    name := "git-practice-lab",
    libraryDependencies += munit % Test
  )
