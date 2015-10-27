/* sbt
 * Copyright 2009-2015 Typesafe, Inc, Mark Harrah, and others
 */
package com.typesafe.zinc

import java.io.{ Closeable, BufferedWriter, File, FileOutputStream, OutputStreamWriter }
import java.nio.charset.Charset

import sbt.io.IO
import Using._

private[zinc] abstract class Using[Source, T] {
  protected def open(src: Source): T
  def apply[R](src: Source)(f: T => R): R =
    {
      val resource = open(src)
      try { f(resource) }
      finally { close(resource) }
    }
  protected def close(out: T): Unit
}
private[zinc] trait OpenFile[T] extends Using[File, T] {
  protected def openImpl(file: File): T
  protected final def open(file: File): T =
    {
      val parent = file.getParentFile
      if (parent != null)
        IO.createDirectory(parent)
      openImpl(file)
    }
}
private[zinc] object Using {
  def file[T <: Closeable](openF: File => T): OpenFile[T] = file(openF, closeCloseable)
  def file[T](openF: File => T, closeF: T => Unit): OpenFile[T] =
    new OpenFile[T] {
      def openImpl(file: File) = openF(file)
      def close(t: T) = closeF(t)
    }
  private def closeCloseable[T <: Closeable]: T => Unit = _.close()

  def fileWriter(charset: Charset = IO.utf8, append: Boolean = false) =
    file(f => new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, append), charset)))
}
