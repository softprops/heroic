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
  def push(remote: String, branch: String = "master") =
    call("push %s %s" format(remote, branch))
}

object Heroku extends Cmd("heroku", "try `gem install heroku`") {
  def addons = new {
    def add(addon: String) = call("addons:add %s" format addon)
    def ls = call("addons")
    def rm(addon: String) = call("addons:remove %s" format addon)
  }

  /* more info @ http://devcenter.heroku.com/articles/multiple-environments */
  def create = call("create --stack cedar")
  def apps = new {
    def destroy = call("apps:destroy")
    def open = call("apps:open")
    def info = call("apps:info")
    def rename(name: String) = call("apps:rename %s" format name)
  }

  /* more info @ http://devcenter.heroku.com/articles/config-vars */
  def config = new {
    def show = call("config")
    def add(key: String, `val`: Any) = call("config:add %s=%s" format(key, `val`))
    def rm(key: String) = call("config:remove %s" format key)
  }

  def info = call("info")

  /* more info @ http://devcenter.heroku.com/articles/logging */
  /* todo drains @ http://devcenter.heroku.com/articles/logging#syslog_drains */
  def logs = new {
    def build(ctx: String) = new {
      def show = call(ctx)
      def tail = call("%s %s" format(ctx, "-t"))
      def ps(proc: String) = new {
        def show = call("%s %s" format(ctx, proc))
        def tail = call("%s %s -t" format(ctx, proc))
      }
    }
    def show = call("logs")
    def tail = call("logs -t")
    def heroku = build("logs --source heroku")
    def api = build("logs --source app")
  }

  def ps = new {
    def show = call("ps")
    def scale(proc: String, n: Int) = call("ps:scale %s=%s" format(proc, n))
    def restart(proc: String) = call("ps:restart %s" format proc)
  }

  def releases = new {
    def show = call("releases")
    def info(rel: String) = call("releases:info %s" format rel)
    def rollback(rel: String) = call("rollback %s" format rel)
  }

  def domains = new {
    def show = call("domains")
    def add(dom: String) = call("domains.add %s" format dom)
    def rm(dom: String) = call("domains.remove %s" format dom)
  }

  def maintenance = new {
    def off = call("maintenance:off")
    def on = call("maintenance:on")
  }
}

object Foreman extends Cmd("foreman", "try `gem install foreman`") {
  def check = call("check")
  // recommend checking before starting
  def start = call("start")
}
