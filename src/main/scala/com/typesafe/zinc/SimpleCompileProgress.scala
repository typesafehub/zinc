package com.typesafe.zinc

import xsbti.compile.{ CompileProgress }
import math.max

/**
 * Created by jmouradian on 4/14/15.
 */
class SimpleCompileProgress extends CompileProgress {
  var lastCurrent: Int = 0  // The last number of steps to have been completed.

  def startUnit(phase: String, unitPath: String) : Unit = {
    // Notify the user of the current compilation phase.
    println(phase + " " + unitPath + "...")
  }

  // Always advance. If there exists progress to report to the user, print to stdout.
  def advance(current: Int, total: Int): Boolean = {
    if (current > lastCurrent) println("Progress: [" + current + "/" + total + "]")
    lastCurrent = math.max(current, lastCurrent)
    true
  }
}
