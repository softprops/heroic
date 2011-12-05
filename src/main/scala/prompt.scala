package heroic

// todo factor this into its own plugin
object Prompt {
  import jline._
  val Okays = Seq("y","yes", "yep", "yea")
  val Nos = Seq("n", "no", "nope", "nah")
  def askDiscretely[T](txt: String)(f: String => T): T =
    f(sbt.SimpleReader.readLine(txt, Some('*')).get)
  def ask[T](txt: String)(f: String => T): T =
    f(sbt.SimpleReader.readLine(txt).get)
}
