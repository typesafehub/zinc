package com.typesafe.zinc

import xsbti.compile.CompileProgress

/**
 * SimpleCompileProgress implements CompileProgress to add output to zinc scala compilations, but
 * does not implement the capability to cancel compilations via the `advance` method.
 */
class SimpleCompileProgress (printUnits: Boolean, printProgress: Boolean) extends CompileProgress {
  var lastCurrent: Int = 0  

  /** 
   * startUnit Optionally reports to stdout when a phase of compilation has begun for a file.
   */
  def startUnit(phase: String, unitPath: String): Unit =  {
    if (printUnits) {
      println(phase + " " + unitPath + "...")
    }
  }

  /**
   * advance Optionally reports the number of compilation units completed out of the total.
   * 
   * advance is periodically called during compilation, indicating the total number of compilation 
   * steps completed (`current`) out of the total number of steps necessary. The method returns 
   * false if the user wishes to cancel compilation, or true otherwise. Currently, Zinc never 
   * requests to cancel compilation.
   */
  def advance(current: Int, total: Int): Boolean = {
    if (printProgress) {
      if (current > lastCurrent) {
        println("Progress: [" + current + "/" + total + "]")
        lastCurrent = current 
      }
    }
    true
  }
}
