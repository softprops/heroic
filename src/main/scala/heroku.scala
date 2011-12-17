package heroic

case class HerokuClient(user: String, password: String) {
  import java.nio.charset.Charset.{ forName => charset }
  import java.net.URLEncoder.encode
  import com.codahale.jerkson.Json._
  import HerokuClient._
  import dispatch._
  import Http._

  val utf8 = charset("utf-8")

  lazy val api = :/("api.heroku.com").secure <:< AppHeaders

  def authenticated(r: Request) = r as_!(user, password)

  def request(r: Request) = authenticated(r <:< AcceptJson)

  lazy val Hcredentials = Map(
    "user" -> user,
    "password" -> password
  )

  private def escape(raw: String) =
    encode(raw, utf8.name()).replaceAll(
      "[.]", "%2E"
    )

  def features(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)
    def all = request(api /"features" <<? Map("app"-> app))
    def show(name: String) = request(api / "features" / escape(name) <<? Map("app"-> app))
    def enable(name: String) = request(api.POST / "features" / escape(name) <<? Map("app"-> app)) 
    def disable(name: String) = request(api.DELETE / "features" / escape(name) <<? Map("app"-> app))
  }

  def addons(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)

    private def all = request(api / "addons")
    private def mine = request(api / "apps" / app / "addons")
    def available = all
    def show = mine
    def add(name: String) =
      mine.POST / name
    def rm(name: String) = mine.DELETE / name
    def upgrade(name: String) =
      mine.PUT / name
    def downgrade(name: String) =
      upgrade(name)
  }

  // created is inferred from a 201 http status 
  def appStatus(remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      authenticated(api.PUT / "apps" / app / "status" <:< Map("Accept"->"text/plain"))
  }

  def collaborators(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)
    private def collab = request(api / "apps" / app / "collaborators")
    def add(email: String) = collab.POST << Map(
      "collaborator[email]" -> email
    )
    def rm(email: String) = collab.DELETE / email
    def show = collab
  }

   /* more info @ http://devcenter.heroku.com/articles/config-vars */
  def config(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)
    private def c = request(api / "apps" / app / "config_vars")
    def show = c
    def clear = c.DELETE
    def add(key: String, value: String) = c.PUT <<< generate(Map(
      key -> value
    ))
    def rm(key: String) = c.DELETE / key
  }

  def confirmBilling =
    api.POST / "user" / escape(user) / "confirm_billing" <:< AcceptJson as_!(
      user, password
    )

  // todo: can optionally post with a name
  // more info @ http://devcenter.heroku.com/articles/multiple-environments

  def create(stack: String = DefaultStack) =
    request(api.POST / "apps") << Map(
      "app[stack]" -> stack
    )

  def destroy(remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      request(api.DELETE / "apps" / app)
  }

  def domains(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)
    def show = request(api / "apps" / app / "domains")
    def add(dom: String) = request(api.POST / "apps" / app / "domains"  << dom.trim.toLowerCase)
    def rm(dom: String) = request(api.DELETE / "apps" / app / "domains" / dom.trim.toLowerCase)
    def clear = request(api.DELETE / "apps" / app / "domains")
  }

  def info(app: String) =
    request(api / "apps" / app)

   /* more info @ http://devcenter.heroku.com/articles/logging */
  /* todo drains @ http://devcenter.heroku.com/articles/logging#syslog_drains */
  def logs(remote: String = DefaultRemote) = {
    val app = requireApp(remote)
    val base = request(api / "apps" / app  / "logs")
    val uri = Http(base <<? Map(
      "logplex" -> "true"
    ) as_str)
    if("Use old logs" == uri) base
    else dispatch.url(uri)
  }

  def cronLogs(remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      api / "apps" / app / "cron_logs" <:< AcceptJson as_!(user, password)
  }

  def keys = new {
    val ks = request(api / "user" / "keys")
    def show = ks
    def add(key: String) = ks.POST <<(key, "text/ssh-authkey")
    def rm(key: String) = ks.DELETE / escape(key)
    def clear = ks.DELETE
  }

  def maintenance(on: Boolean, remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      request(api.POST / "apps" / app / "server" / "maintenance") << Map(
        "maintenance_mode" -> (if(on) "1" else "0")
      )
  }

  def ps(remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      request(api / "apps" / app / "ps")
  }

  def dynos(n: Int, remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      (api.PUT / "apps" / app / "dynos") <<< generate(Map(
        "dynos" -> n
      )) <:< AcceptJson as_!(user, password)
  }

  def workers(n: Int, remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      (api.PUT / "apps" / app / "workers") <<< generate(Map(
        "dynos" -> n
      )) <:< AcceptJson as_!(user, password)
  }

  def releases(remote: String = DefaultRemote) = new {
    val app = requireApp(remote)
    private def rels = request(api / "apps" / app / "releases")
    def show(rel: String) = rels / rel
    def list = rels
  }

  def rename(name: String, remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      request(api / "apps" / app) <<< Map(
        "app[name]" -> name
      )
  }

  def rollback(rel: String, remote: String = DefaultRemote) = requireApp(remote) match {
    case app =>
      request(api / "apps" / app) << Map(
        "rollback" -> rel
      )
  }
}

object HerokuClient {
  import dispatch._
  import Http._
  import com.codahale.jerkson.Json._

  val DefaultRemote = "heroku"

  val DefaultStack = "cedar"

  lazy val site = :/("heroku.com")

  lazy val AppHeaders = Map(
    "X-Heroku-API-Version" -> "2",
    "User-Agent" -> "heroic/0.1.0"
  )

  lazy val AcceptJson = Map(
    "Accept" -> "application/json"
  )

  def requireApp(remote: String) =
    GitClient.remotes.get(remote) match {
      case Some(app) => app
      case _ => sys.error("No registered Heroku app for remote '%s'" format remote)
    }

  def auth(email: String, password: String) = parse[Map[String, String]](
    Http(:/("api.heroku.com").secure.POST / "login" << Map(
      "username" -> email,
      "password" -> password
    ) <:< AppHeaders ++ AcceptJson as_str)
  )
}


object GitClient {
  val HerokuRemote = """^git@heroku.com:([\w\d-]+)\.git$""".r

  // returns map of remote -> appName
  def remotes =
    (Map.empty[String, String] /: (sbt.Process("git remote -v") !!)
     .split('\n').map(_.split("""\s+""")))(
       (a, e) =>
         e match {
           case Array(name, HerokuRemote(app), _) =>
             a + (name -> app)
           case _ => a
         }
      )

  def remoteRm(remote: String) =
    sbt.Process("git remote rm %s" format remote) !

  def updateRemote(app: String, remote: String = "heroku",
                   host: String = "heroku.com") = {
    if(remotes.isDefinedAt(remote)) {
      remoteRm(remote)
      addRemote(app, remote, host)
    } else {
      addRemote(app, remote, host)
    }
  }

  def addRemote(app: String, remote: String = "heroku",
                host: String = "heroku.com") =
    if(remotes.isDefinedAt(remote)) 0 // already have one
    else sbt.Process(
      "git remote add %s git@%s:%s.git" format(
        remote, host, app
      )) !

}
