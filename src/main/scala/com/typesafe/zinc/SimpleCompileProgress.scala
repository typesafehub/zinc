package com.typesafe.zinc

import xsbti.compile.CompileProgress

class SimpleCompileProgress extends CompileProgress {
  var lastCurrent: Int = 0  

  def startUnit(phase: String, unitPath: String): Unit =  println(phase + " " + unitPath + "...")

  def advance(current: Int, total: Int): Boolean = {
    if (current > lastCurrent) {
      println("Progress: [" + current + "/" + total + "]")
      lastCurrent = current 
    }
    true
  }
}
