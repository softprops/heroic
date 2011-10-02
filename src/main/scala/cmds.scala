package heroic

import sbt.Process
import Process._

case class Cmd(name: String, help: String) {
  lazy val bin = try {
    val path = Process("which %s" format name).!!
    if(path matches ".*%s\\s+".format(name)) {
       Right(path)
    }
    else Left(new UnsupportedOperationException(
      "%s cmd not installed." format(name)
    ))
  } catch {
     case e => Left(e)
  }
  def onError(t: Throwable) = throw new RuntimeException(
    "Invalid `%s` cmd. %s. %s" format(name,t.getMessage, help), t
  )
  def call[T](cmd: String) = bin.fold(onError, { path =>
    Process("%s %s".format(name, cmd).trim)
  })
}

object Git extends Cmd("git", "download from http://git-scm.com/download") {
  def apply(args: Seq[String]) =
    call(args.mkString(" "))
  def add(paths: Seq[String] = Seq(".")) =
    call("add %s" format paths.mkString(" "))
  def commit(msg: String) =
    call( """commit -m '%s'""" format msg)
  def diff(path: String = "") =
    call("diff %s" format path)
  def push(remote: String, branch: String = "master") =
    call("push %s %s" format(remote, branch))
  def status(opts: String = "--porcelain") =
    call("status %s" format opts)
}

object Heroku extends Cmd("heroku", "try `gem install heroku`") {

  /* more info @ http://devcenter.heroku.com/articles/multiple-environments */
  def create = call("create --stack cedar")
  def apps = new {
    def destroy = call("apps:destroy")
    def open = call("apps:open")
  }

  def ps = new {
    def scale(proc: String, n: Int) = call("ps:scale %s=%s" format(proc, n))
    def restart(proc: String) = call("ps:restart %s" format proc)
  }

  def domains = new {
    def add(dom: String) = call("domains:add %s" format dom)
    def rm(dom: String) = call("domains:remove %s" format dom)
  }
}
