/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.zinc

import java.io.File
import java.net.URLClassLoader


import sbt.internal.inc.{ AnalyzingCompiler, CompilerInterfaceProvider, RawCompiler, IncrementalCompilerImpl }
import sbt.internal.inc.javac.IncrementalCompilerJavaTools
import sbt.internal.inc.{ Analysis, AnalysisStore, ClasspathOptions, CompileOutput, CompilerCache, FileBasedStore, IC, LoggerReporter, ScalaInstance }
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.internal.inc.javac.{ ForkedJavaCompiler, JavaCompiler, JavaTools }
import sbt.io.Path._
import xsbti.compile.{ CompileProgress, Compilers, DefinesClass, GlobalsCache, IncOptions, Output, MiniSetup, MiniOptions, F1, CompileAnalysis, CompileResult, PreviousResult }
import xsbti.{ Logger, Maybe, Reporter }

object Compiler {
  val impl = new IncrementalCompilerImpl
  val CompilerInterfaceId = "compiler-interface"
  val JavaClassVersion = System.getProperty("java.class.version")

  /**
   * Static cache for zinc compilers.
   */
  val compilerCache = Cache[Setup, Compiler](Setup.Defaults.compilerCacheLimit)

  /**
   * Static cache for resident scala compilers.
   */
  val residentCache: GlobalsCache = createResidentCache(Setup.Defaults.residentCacheLimit)

  /**
   * Static cache for compile analyses.  Values must be Options because in get() we don't yet know if, on
   * a cache miss, the underlying file will yield a valid Analysis.
   */
  val analysisCache = Cache[FileFPrint, Option[(Analysis, MiniSetup)]](Setup.Defaults.analysisCacheLimit)

  /**
   * Get or create a zinc compiler based on compiler setup.
   */
  def apply(setup: Setup, log: Logger): Compiler = {
    compilerCache.get(setup)(create(setup, log))
  }

  /**
   * Java API for creating compiler.
   */
  def getOrCreate(setup: Setup, log: Logger): Compiler = apply(setup, log)

  /**
   * Create a new zinc compiler based on compiler setup.
   */
  def create(setup: Setup, log: Logger): Compiler = {
    val instance     = scalaInstance(setup)
    val bridgeJar    = compilerInterface(setup, instance, log)
    val scalac       = newScalaCompiler(instance, bridgeJar)
    val javac        = newJavaCompiler(instance, setup.javaHome)
    new Compiler(scalac, javac)
  }

  /**
   * Create a new scala compiler.
   */
  def newScalaCompiler(instance: ScalaInstance, bridgeJar: File): AnalyzingCompiler =
    new AnalyzingCompiler(instance, CompilerInterfaceProvider.constant(bridgeJar), ClasspathOptions.boot)

  /**
   * Create a new java compiler.
   */
  def newJavaCompiler(instance: ScalaInstance, javaHome: Option[File]): IncrementalCompilerJavaTools = {
    val cpOptions = ClasspathOptions.javac(false)
    JavaTools.directOrFork(instance, cpOptions, javaHome)
  }

  /**
   * Create new globals cache.
   */
  def createResidentCache(maxCompilers: Int): GlobalsCache = {
    if (maxCompilers <= 0) CompilerCache.fresh else CompilerCache(maxCompilers)
  }

  /**
   * Create an analysis store backed by analysisCache.
   */
  def analysisStore(cacheFile: File): AnalysisStore = {
    val fileStore = AnalysisStore.cached(FileBasedStore(cacheFile))

    val fprintStore = new AnalysisStore {
      override def set(analysis: Analysis, setup: MiniSetup) {
        fileStore.set(analysis, setup)
        FileFPrint.fprint(cacheFile) foreach { analysisCache.put(_, Some((analysis, setup))) }
      }
      override def get(): Option[(Analysis, MiniSetup)] = {
        FileFPrint.fprint(cacheFile) flatMap { fprint => analysisCache.get(fprint)(fileStore.get) }
      }
    }

    AnalysisStore.sync(AnalysisStore.cached(fprintStore))
  }

  /**
   * Get an analysis, lookup by cache file.
   */
  def analysis(cacheFile: File): Analysis = {
    analysisStore(cacheFile).get map (_._1) getOrElse Analysis.Empty
  }

  /**
   * Check whether an analysis is empty.
   */
  def analysisIsEmpty(cacheFile: File): Boolean = {
    analysis(cacheFile) eq Analysis.Empty
  }

  /**
   * Create the scala instance for the compiler. Includes creating the classloader.
   */
  def scalaInstance(setup: Setup): ScalaInstance = {
    import setup.{ scalaCompiler, scalaLibrary, scalaExtra}
    val loader = scalaLoader(scalaLibrary +: scalaCompiler +: scalaExtra)
    val version = scalaVersion(loader)
    val allJars = (scalaLibrary +: scalaCompiler +: scalaExtra).toArray
    new ScalaInstance(version.getOrElse("unknown"), loader, scalaLibrary, scalaCompiler, allJars, version)
  }

  /**
   * Create a new classloader with the root loader as parent (to avoid zinc itself being included).
   */
  def scalaLoader(jars: Seq[File]) = new URLClassLoader(toURLs(jars), ClasspathUtilities.rootLoader)

  /**
   * Get the actual scala version from the compiler.properties in a classloader.
   * The classloader should only contain one version of scala.
   */
  def scalaVersion(scalaLoader: ClassLoader): Option[String] = {
    Util.propertyFromResource("compiler.properties", "version.number", scalaLoader)
  }

  /**
   * Get the compiler interface for this compiler setup. Compile it if not already cached.
   */
  def compilerInterface(setup: Setup, scalaInstance: ScalaInstance, log: Logger): File = {
    val dir = setup.cacheDir / interfaceId(scalaInstance.actualVersion)
    val interfaceJar = dir / (CompilerInterfaceId + ".jar")
    if (!interfaceJar.exists) {
      dir.mkdirs()
      compileBridgeJar(CompilerInterfaceId, setup.compilerInterfaceSrc, interfaceJar, setup.sbtInterfaces, scalaInstance, log)
    }
    interfaceJar
  }

  private def compileBridgeJar(label: String, sourceJar: File, targetJar: File, xsbtiJars: Iterable[File], instance: ScalaInstance, log: Logger): Unit = {
    val raw = new RawCompiler(instance, ClasspathOptions.auto, log)
    AnalyzingCompiler.compileSources(sourceJar :: Nil, targetJar, xsbtiJars, label, raw, log)
  }

  def interfaceId(scalaVersion: String) = CompilerInterfaceId + "-" + scalaVersion + "-" + JavaClassVersion
}

/**
 * A zinc compiler for incremental recompilation.
 */
class Compiler(scalac: AnalyzingCompiler, javac: IncrementalCompilerJavaTools) {
  self =>

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *  Note:  This variant automatically contructs an error-reporter.
   */
  def compile(inputs: Inputs)(log: Logger): CompileResult = compile(inputs, None)(log)

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *
   *  Note:  This variant automatically contructs an error-reporter.
   */
  def compile(inputs: Inputs, cwd: Option[File])(log: Logger): CompileResult = {
    val maxErrors     = 100
    compile(inputs, cwd, new LoggerReporter(maxErrors, log, identity))(log)
  }

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *
   *  Note: This variant does not report progress updates
   */
  def compile(inputs: Inputs, cwd: Option[File], reporter: Reporter)(log: Logger): CompileResult = {
    compile(inputs, cwd, reporter, progress = None)(log)
  }

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   */
  def compile(inputs: Inputs, cwd: Option[File], reporter: Reporter, progress: Option[CompileProgress])(log: Logger): CompileResult = {
    import sbt.util.Logger.m2o
    import inputs._
    if (forceClean && Compiler.analysisIsEmpty(cacheFile)) Util.cleanAllClasses(classesDirectory)

    val prevOpt = Compiler.analysisStore(cacheFile).get
    val prev = prevOpt match {
      case Some((a, s)) => new PreviousResult(Maybe.just(a), Maybe.just(s))
      case _            => Compiler.impl.emptyPreviousResult
    }
    val in: xsbti.compile.Inputs = makeInputs(inputs, cwd, reporter, prev, log)
    val result = Compiler.impl.compile(in, log)
    val analysis = result.analysis match {
      case a: Analysis => a
    }
    val setup = result.setup
    if (mirrorAnalysis) {
      SbtAnalysis.printRelations(result.analysis, Some(new File(cacheFile.getPath() + ".relations")), cwd)
    }
    SbtAnalysis.printOutputs(result.analysis, outputRelations, outputProducts, cwd, classesDirectory)
    Compiler.analysisStore(cacheFile).set(analysis, setup)
    result
  }

  /**
   * Automatically add the output directory and scala library to the classpath.
   */
  def autoClasspath(classesDirectory: File, allScalaJars: Seq[File], javaOnly: Boolean, classpath: Seq[File]): Seq[File] = {
    if (javaOnly) classesDirectory +: classpath
    else Setup.splitScala(allScalaJars) match {
      case Some(scalaJars) => classesDirectory +: scalaJars.library +: classpath
      case None            => classesDirectory +: classpath
    }
  }

  override def toString = "Compiler(Scala %s)" format scalac.scalaInstance.actualVersion

  private[this] def f1[A, B](f: A => B): F1[A, B] =
    new F1[A, B] {
      def apply(a: A): B = f(a)
    }
  private[this] def makeInputs(inputs: Inputs, cwd: Option[File], compileReporter: Reporter, prev: PreviousResult, log: Logger): xsbti.compile.Inputs = {
    def o2m[T](opt: Option[T]): Maybe[T] = opt map (Maybe.just(_)) getOrElse Maybe.nothing()
    val maxErrors = 100
    import inputs._
    val cs = Compiler.impl.compilers(self.javac, self.scalac)
    val analysisMap = f1[File, Maybe[CompileAnalysis]](f => o2m(inputs.analysisMap get f))
    val dc = f1[File, DefinesClass](f => new DefinesClass {
      override def apply(className: String): Boolean =
        inputs.analysisMap get f match {
          case Some(a) => a.relations.definesClass(className).nonEmpty
          case _       => false
        }
    })
    val setup = Compiler.impl.setup(analysisMap, dc, false, inputs.cacheFile, Compiler.residentCache,
      inputs.incOptions, compileReporter)
    Compiler.impl.inputs(autoClasspath(classesDirectory, scalac.scalaInstance.allJars, javaOnly, inputs.classpath).toArray,
      inputs.sources.toArray, inputs.classesDirectory, inputs.scalacOptions.toArray, inputs.javacOptions.toArray,
      maxErrors, Array(), inputs.compileOrder,
      cs, setup, prev)
  }
}
