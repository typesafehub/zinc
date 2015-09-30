/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

val incrementalVersion = "0.1.0-M1-168cb7a4877917e01917e35b9b82a62afe5c2a01"

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
      "org.scala-sbt" % "compiler-bridge_2.10" % incrementalVersion classifier "sources",
      "com.martiansoftware" % "nailgun-server" % "0.9.1" % "optional"
    ),
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint")
  )
)
