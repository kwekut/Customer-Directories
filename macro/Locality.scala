package models
//https://gist.github.com/drexin/5540251
//http://www.bbartosz.com/blog/2016/01/23/scala-macros-part-1/
import java.io.File
import scala.reflect.macros.blackbox.Context
import scala.language.experimental.macros
//How to use
//import Locality._
//s"line $LINE of file $FILE"

object Locality {
  // def LINE: Int = 5
  // def FILE: String = "file path"
  def LINE: Int = macro lineImpl
  def FILE: String = macro fileImpl

  def lineImpl(c: Context): c.Expr[Int] = {
    import c.universe._
    val line = Literal(Constant(c.enclosingPosition.line))
    c.Expr[Int](line)
  }
  def fileImpl(c: Context): c.Expr[String] = {
    import c.universe._
    val absolute = c.enclosingPosition.source.file.file.toURI
    val base = new File(".").toURI
    val path = Literal(Constant(base.relativize(absolute).getPath))
    c.Expr[String](path)
  }

}