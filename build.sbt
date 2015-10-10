/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

val incrementalVersion = "0.1.0-M1-826f7c3944195f978063af84a414eb9ecede16c6"

val resolveSbtLocally = settingKey[Boolean]("resolve-sbt-locally")

lazy val buildSettings = Seq(
  organization := "com.typesafe.zinc",
  version := "0.3.10-SNAPSHOT",
  scalaVersion := "2.10.5",
  crossPaths := false
)

lazy val zinc = Project(
  "zinc",
  file("."),
  settings = buildSettings ++ Version.settings ++ Publish.settings ++ Dist.settings ++ Scriptit.settings ++ Seq(
    resolveSbtLocally := false,
    resolvers += (if (resolveSbtLocally.value) Resolver.mavenLocal else Opts.resolver.sonatypeSnapshots),
    libraryDependencies ++= Seq(
      "org.scala-sbt" %% "incrementalcompiler" % incrementalVersion,
      "org.scala-sbt" % "compiler-interface" % incrementalVersion,
      "org.scala-sbt" % "compiler-bridge_2.10" % incrementalVersion sources(),
      "com.martiansoftware" % "nailgun-server" % "0.9.1" % "optional"
    ),
    scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint"),
    classpathTypes += "src"
  )
)
