package fif.use

import scala.language.postfixOps

trait Container[A, S] extends Serializable {

  type Structure = S

  def empty: Structure

}