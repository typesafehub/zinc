/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

import sbt._
import sbt.Keys._

object ZincBuild extends Build {
  val sbtVersion = "0.13.12-SNAPSHOT"

  val resolveSbtLocally = settingKey[Boolean]("resolve-sbt-locally")

  lazy val buildSettings = Seq(
    organization := "com.typesafe.zinc",
    version := "0.3.11",
    scalaVersion := "2.10.6",
    crossPaths := false
  )

  lazy val zinc = Project(
    "zinc",
    file("."),
    settings = buildSettings ++ Version.settings ++ Publish.settings ++ Dist.settings ++ Scriptit.settings ++ Seq(
      resolveSbtLocally := false,
      resolvers ++= (if (resolveSbtLocally.value) Seq(Resolver.mavenLocal) else Seq(Opts.resolver.sonatypeReleases, Opts.resolver.sonatypeSnapshots)),
      libraryDependencies ++= Seq(
        "com.typesafe.sbt" % "incremental-compiler" % sbtVersion,
        "com.typesafe.sbt" % "compiler-interface" % sbtVersion classifier "sources",
        "com.martiansoftware" % "nailgun-server" % "0.9.1" % "optional"
      ),
      scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint")
    )
  )
}
