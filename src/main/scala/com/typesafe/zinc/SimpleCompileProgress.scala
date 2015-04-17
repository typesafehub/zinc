package com.typesafe.zinc

import xsbti.compile.CompileProgress

/**
 * SimpleCompileProgress implements CompileProgress to add output to zinc scala compilations, but
 * does not implement the capability to cancel compilations via the `advance` method.
 */
class SimpleCompileProgress extends CompileProgress {
  /**
   * lastCurrent tracks the latest reported number of currently completed compilation units.
   */
  var lastCurrent: Int = 0  

  /** 
   * startUnit is called when SBT begins a compilation phase of a given file, and reports to stdout.
   */
  def startUnit(phase: String, unitPath: String): Unit =  println(phase + " " + unitPath + "...")

  /**
   * advance is periodically called during compilation, indicating the total number of compilation 
   * steps completed (`current`) out of the total number of steps necessary. The method returns 
   * false if the user wishes to cancel compilation, or true otherwise. Currently, Zinc never 
   * requests to cancel compilation.
   */
  def advance(current: Int, total: Int): Boolean = {
    if (current > lastCurrent) {
      println("Progress: [" + current + "/" + total + "]")
      lastCurrent = current 
    }
    true
  }
}
