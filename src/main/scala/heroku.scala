package heroic

case class HerokuClient(user: String, password: String) {
  import com.codahale.jerkson.Json._
  import HerokuClient._
  import dispatch._
  import Http._

  lazy val api = :/("api.heroku.com").secure <:< AppHeaders

  lazy val Hcredentials = Map(
    "user" -> user,
    "password" -> password
  )

  private def escape(raw: String) =
    java.net.URLEncoder.encode(raw).replaceAll(
      "[.]", "%2E"
    )

  def addons(app: String = GitClient.remotes("heroku")) = new {
    private def all = api / "addons" <:< AcceptJson as_!(user, password)
    private def mine = api / "apps" / app / "addons" <:< AcceptJson as_!(
      user, password
    )
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
  def appStatus(app: String = GitClient.remotes("heroku")) =
    api.PUT / "apps" / app / "status" <:< Map("Accept"->"text/plain") as_!(user, password)

  def collaborators(app: String = GitClient.remotes("heroku")) = new {
    private def collab = api / "apps" / app / "collaborators" <:< AcceptJson as_!(
      user, password
    )
    def add(email: String) = collab.POST << Map(
      "collaborator[email]" -> email
    )
    def rm(email: String) = collab.DELETE / email
    def show = collab
  }

   /* more info @ http://devcenter.heroku.com/articles/config-vars */
  def config(app: String = GitClient.remotes("heroku")) = new {
    private def c = api / "apps" / app / "config_vars" <:< AcceptJson as_!(user, password)
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
  def create(remote: String = "heroku", stack: String = "cedar") =
    (api.POST / "apps" <:< AcceptJson as_!(user, password)) << Map(
      "app[stack]" -> stack
    )

  def destroy(app: String = GitClient.remotes("heroku")) =
    api.DELETE / "apps" / app <:< AcceptJson as_!(user, password)

  def domains(app: String = GitClient.remotes("heroku")) = new {
    def show = api / "apps" / app / "domains" <:< AcceptJson as_!(user, password)
    // def add
    // def rm
  }

  def info(app: String = GitClient.remotes("heroku")) =
    api / "apps" / app <:< AcceptJson as_!(user, password)

   /* more info @ http://devcenter.heroku.com/articles/logging */
  /* todo drains @ http://devcenter.heroku.com/articles/logging#syslog_drains */
  def logs(app: String = GitClient.remotes("heroku")) = {
    val base = api / "apps" / app  / "logs" <:< AcceptJson as_!(user, password)
    val uri = Http(base <<? Map(
      "logplex" -> "true"
    ) as_str)
    if("Use old logs" == uri) base
    else dispatch.url(uri)
  }

  def cronLogs(app: String = GitClient.remotes("heroku")) = 
    api / "apps" / app / "cron_logs" <:< AcceptJson as_!(user, password)

  def keys = new {
    val ks = api / "user" / "keys" <:< AcceptJson as_!(user, password)
    def show = ks
    def add(key: String) = ks.POST <<(key, "text/ssh-authkey")
    def rm(key: String) = ks.DELETE / key
  }

  def maintenance(on: Boolean, app: String = GitClient.remotes("heroku")) =
    (api.POST / "apps" / app / "server" / "maintenance" <:< AcceptJson as_!(
      user, password)) << Map(
      "maintenance_mode" -> (if(on) "1" else "0")
    )

  def ps(app: String = GitClient.remotes("heroku")) =
    api / "apps" / app / "ps" <:< AcceptJson as_!(user, password)

  def releases(app: String = GitClient.remotes("heroku")) = new {
    private def rels = api / "apps" / app / "releases" <:< AcceptJson as_!(
      user, password
    )
    def show(rel: String) = rels / rel
    def list = rels
  }

  def rename(name: String, app: String = GitClient.remotes("heroku")) =
    (api / "apps" / app <:< AcceptJson as_!(user, password)) <<< Map(
      "app[name]" -> name
    )

  def rollback(rel: String, app: String = GitClient.remotes("heroku")) =
    (api / "apps" / app <:< AcceptJson as_!(user, password)) << Map(
      "rollback" -> rel
    )

}

object HerokuClient {
  import dispatch._
  import Http._
  import com.codahale.jerkson.Json._

  lazy val site = :/("heroku.com")

  lazy val AppHeaders = Map(
    "X-Heroku-API-Version" -> "2",
    "User-Agent" -> "heroic/0.1.0"
  )

  lazy val AcceptJson = Map(
    "Accept" -> "application/json"
  )

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
