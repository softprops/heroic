package heroic

import sbt.Process
import Process._

case class Cmd(name: String, help: String) {
  lazy val bin = try {
    val path = Process("which %s" format name).!!
    if(path matches ".*%s\\s+".format(name)) {
       Right(path)
    }
    else Left(new UnsupportedOperationException("%s cmd not installed." format(name)))
  } catch {
     case e => Left(e)
  }
  def onError(t: Throwable) = throw new RuntimeException(
    "Invalid `%s` cmd. %s. %s" format(name,t.getMessage, help), t
  )
  def call[T](cmd: String) = bin.fold(onError, { path =>
    Process("%s %s" format(name, cmd))
  })
}

object Git extends Cmd("git", "download from http://git-scm.com/download") {
  def push(remote: String, branch: String = "master") = call("push %s %s" format(remote, branch))
}

object Heroku extends Cmd("heroku", "try `gem install heroku`") {
  def addons = new Cmd("%s adddons" format(name), help) {
    def ls = call("")
    def add(addon: String) = call(addon)
    def rm(addon: String) = call(addon)
  }
  def info = call("info")
  def logs(tail: Boolean = false) = call("logs%s" format(if(tail) " --tail" else ""))
  def ps = call("ps")
  def create = call("create --stack cedar")
}

object Foreman extends Cmd("foreman", "try `gem install foreman`") {
  def start = call("start")
  def check = call("check")
}
