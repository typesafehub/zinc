/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.zinc

import java.io.File
import java.net.URLClassLoader
import sbt.internal.inc.{ Analysis, AnalysisStore, AnalyzingCompiler, ClasspathOptions, CompileOptions, CompileOutput, CompileSetup, CompilerCache, FileBasedStore, IC, LoggerReporter, ScalaInstance }
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.internal.inc.javac.{ ForkedJavaCompiler, JavaCompiler }
import sbt.io.Path._
import xsbti.compile.{ CompileProgress, Compilers, DefinesClass, GlobalsCache, IncOptions, Output }
import xsbti.{ Logger, Maybe, Reporter }

object Compiler {
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
  val analysisCache = Cache[FileFPrint, Option[(Analysis, CompileSetup)]](Setup.Defaults.analysisCacheLimit)

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
    val interfaceJar = compilerInterface(setup, instance, log)
    val scalac       = newScalaCompiler(instance, interfaceJar)
    val javac        = newJavaCompiler(instance, setup.javaHome, setup.forkJava)
    new Compiler(scalac, javac)
  }

  /**
   * Create a new scala compiler.
   */
  def newScalaCompiler(instance: ScalaInstance, interfaceJar: File): AnalyzingCompiler = {
    IC.newScalaCompiler(instance, interfaceJar, ClasspathOptions.boot)
  }

  /**
   * Create a new java compiler.
   */
  def newJavaCompiler(instance: ScalaInstance, javaHome: Option[File], fork: Boolean): JavaCompiler = {
    val options = ClasspathOptions.javac(false)
    lazy val forked = new ForkedJavaCompiler(javaHome)

    if (fork || javaHome.isDefined)
      forked
    else
      JavaCompiler.local getOrElse forked
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
      def set(analysis: Analysis, setup: CompileSetup) {
        fileStore.set(analysis, setup)
        FileFPrint.fprint(cacheFile) foreach { analysisCache.put(_, Some((analysis, setup))) }
      }
      def get(): Option[(Analysis, CompileSetup)] = {
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
      IC.compileInterfaceJar(CompilerInterfaceId, setup.compilerInterfaceSrc, interfaceJar, setup.sbtInterface, scalaInstance, log)
    }
    interfaceJar
  }

  def interfaceId(scalaVersion: String) = CompilerInterfaceId + "-" + scalaVersion + "-" + JavaClassVersion
}

/**
 * A zinc compiler for incremental recompilation.
 */
class Compiler(scalac: AnalyzingCompiler, javac: JavaCompiler) {
  self =>

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *  Note:  This variant automatically contructs an error-reporter.
   */
  def compile(inputs: Inputs)(log: Logger): Analysis = compile(inputs, None)(log)

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *
   *  Note:  This variant automatically contructs an error-reporter.
   */
  def compile(inputs: Inputs, cwd: Option[File])(log: Logger): Analysis = {
    val maxErrors     = 100
    compile(inputs, cwd, new LoggerReporter(maxErrors, log, identity))(log)
  }

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   *
   *  Note: This variant does not report progress updates
   */
  def compile(inputs: Inputs, cwd: Option[File], reporter: Reporter)(log: Logger): Analysis = {
    compile(inputs, cwd, reporter, progress = None)(log)
  }

  /**
   * Run a compile. The resulting analysis is also cached in memory.
   */
  def compile(inputs: Inputs, cwd: Option[File], reporter: Reporter, progress: Option[CompileProgress])(log: Logger): Analysis = {
    import inputs._
    if (forceClean && Compiler.analysisIsEmpty(cacheFile)) Util.cleanAllClasses(classesDirectory)

    val in: xsbti.compile.Inputs[Analysis, AnalyzingCompiler] = makeInputs(inputs, cwd, reporter, progress, log)

    val analysis = IC.compile(in, log)
    if (mirrorAnalysis) {
      SbtAnalysis.printRelations(analysis, Some(new File(cacheFile.getPath() + ".relations")), cwd)
    }
    SbtAnalysis.printOutputs(analysis, outputRelations, outputProducts, cwd, classesDirectory)

    val compileSetup = new CompileSetup(
      in.options.output,
      new CompileOptions(in.options.options, in.options.javacOptions),
      in.compilers.scalac.scalaInstance.version,
      in.options.order,
      inputs.incOptions.nameHashing
    )
    Compiler.analysisStore(cacheFile).set(analysis, compileSetup)

    analysis
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

  private[this] def makeInputs(inputs: Inputs, cwd: Option[File], compileReporter: Reporter, compileProgress: Option[CompileProgress], log: Logger): xsbti.compile.Inputs[Analysis, AnalyzingCompiler] = {
    def o2m[T](opt: Option[T]): Maybe[T] = opt map (Maybe.just(_)) getOrElse Maybe.nothing()

    import inputs._
    new xsbti.compile.Inputs[Analysis, AnalyzingCompiler] {
      override val compilers = new Compilers[AnalyzingCompiler] {
        override val scalac = self.scalac
        override val javac = new xsbti.compile.JavaCompiler {
          override def compileWithReporter(sources: Array[File], classpath: Array[File], output: Output, options: Array[String], reporter: Reporter, log: Logger): Unit = {
            // FIXME: Classpath???
            self.javac.run(sources, options)(log, reporter)
          }
        }
      }
      override val options = new xsbti.compile.Options {
        override val classpath = autoClasspath(classesDirectory, scalac.scalaInstance.allJars, javaOnly, inputs.classpath).toArray
        override val sources = inputs.sources.toArray
        override val output = CompileOutput(inputs.classesDirectory)
        override val options = inputs.scalacOptions.toArray
        override val javacOptions = inputs.javacOptions.toArray
        override val order = inputs.compileOrder
      }
      override val setup = new xsbti.compile.Setup[Analysis] {
        override def analysisMap(file: File): Maybe[Analysis] =
          o2m(inputs.analysisMap get file)
        override val cache: GlobalsCache = Compiler.residentCache
        override val cacheFile = inputs.cacheFile
        override def definesClass(f: File) = new DefinesClass {
          override def apply(className: String): Boolean =
            inputs.analysisMap get f match {
              case Some(a) => a.relations.definesClass(className).nonEmpty
              case _       => false
            }
        }
        override val incrementalCompilerOptions: IncOptions = inputs.incOptions
        override val progress: Maybe[CompileProgress] = o2m(compileProgress)
        override val reporter: Reporter = compileReporter
        override val skip: Boolean = false
      }
    }
  }
}
