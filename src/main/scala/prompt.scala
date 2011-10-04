package heroic

object Prompt {
  val Okays = Seq("y","yes", "yep", "yea")
  val Nos = Seq("n", "no", "nope", "nah")
  def ask[T](txt: String)(f: String => T): T = {
    print(txt)
    f(readLine)
  }
}
