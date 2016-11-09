val sbtPatchVersion = "13-SNAPSHOT"
val sbtVersionToUse = s"0.13.$sbtPatchVersion"
val zincVersion = s"0.3.$sbtPatchVersion"

val resolveSbtLocally = settingKey[Boolean]("resolve-sbt-locally")

lazy val zinc = (project in file(".")).
  settings(inThisBuild(List(
      organization := "com.typesafe.zinc",
      version := zincVersion,
      scalaVersion := "2.10.6"
    )),
    Version.settings,
    Publish.settings,
    Dist.settings,
    Scriptit.settings,
    crossPaths := false,
    resolveSbtLocally := false,
    libraryDependencies ++= Seq(
      "com.typesafe.sbt" % "incremental-compiler" % sbtVersionToUse,
      "com.typesafe.sbt" % "compiler-interface" % sbtVersionToUse classifier "sources",
      "com.martiansoftware" % "nailgun-server" % "0.9.1" % "optional"
    ),
    resolvers ++= (if (resolveSbtLocally.value) Seq(Resolver.mavenLocal) else Seq(Opts.resolver.sonatypeReleases, Opts.resolver.sonatypeSnapshots)),
    scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint")
  )
