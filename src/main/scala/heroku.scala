package heroic

import dispatch._
import com.ning.http.client.{ RequestBuilder, Response }

trait Hosts {
  def api: RequestBuilder
}

trait DefaultHosts extends Hosts {
  override def api = :/("api.heroku.com").secure
}

trait Credentials {
  def sign(req: RequestBuilder): RequestBuilder
}

case class BasicAuth(key: String) extends Credentials {
  override def sign(req: RequestBuilder) =
    req.as_!("", key)
}

object Client {
  val DefaultHeaders = Map("Accept" -> "application/json",
                           "User-Agent" -> "heroic/0.1.0")
  type Handler[T] = (Response => T)
  trait Completion {
    def apply[T](handler: Client.Handler[T]): Promise[T]
  }
}

class Client(credentials: Credentials, http: Http = Http)
  extends DefaultHosts
     with Methods {
   import Client._

  def request[T](req: RequestBuilder)(handler: Client.Handler[T]): Promise[T] =
    http(credentials.sign(req) <:< DefaultHeaders > handler)

  def complete(req: RequestBuilder): Client.Completion =
    new Client.Completion {
      override def apply[T](handler: Client.Handler[T]) =
        request(req)(handler)
    }
}

/** a dispatch interface for https://api-docs.heroku.com/ */
trait Methods { self: Client =>

  def apps = new {
    private [this] def base = api / "apps"
    /*[{
    "id": 000000,
    "name": "example",
    "create_status": "complete",
    "created_at": "2011/01/01 00:00:00 -0700",
    "stack": "bamboo-ree-1.8.7",
    "requested_stack": null,
    "repo_migrate_status": "complete",
    "slug_size": 1000000,
    "repo_size": 1000000,
    "dynos": 1,
    "workers": 0
    }]*/
    def list = complete(base)

    def info(name: String) =
      complete(base / name)

    def create(name: Option[String] = None, stack: Option[String] = None) =
      complete(
          base.POST << Map.empty[String, String] ++
                       name.map("app[name]" -> _) ++
                       stack.map("app[stack]" -> _))
      

    /* { "name":"newname"}*/
    def rename(oldname: String, newname: String) =
      complete((base / oldname << Map("app[name]" -> newname)).PUT)

    /* {"name":"name" } */
    def transfer(name: String, owner: String) =
      complete((base / name << Map("app[transfer_owner]" -> owner)).PUT)

    // {}
    def destory(name: String) =
      complete(base.DELETE / name)
    // empty response
    def maintenance(name: String, on: Boolean) =
      complete(base.POST / name / "server" / "maintenance"<< Map(
        "maintenance_mode" -> (if (on) "1" else "0")))
  }

  def config(name: String) = new {
    private [this] def base = api / "apps" / name / "config_vars"

    def list =
     complete(base)

    def set(vars: (String, String)*) = {
      import net.liftweb.json._
      import net.liftweb.json.JsonDSL._
      var map = vars.toMap
      complete(base.PUT.setBody(compact(render(map))))
    }

    def delete(key: String) =
      complete(base.DELETE / key)
  }

  def collaborators(app: String) = new {

    def list =
      complete(api / "apps" / app / "collaborators")

    def add(email: String) =
      complete(api / "apps" / app / "collaborators" << Map(
        "collaborator[email]" -> email
      )) 

    def remove(email: String) =
      complete(api / "apps" / app / "collaborators" / email)
  }

  def addons = new {
/*
[
  {
    "name": "example:basic",
    "description": "Example Basic",
    "url": "http://devcenter.heroku.com/articles/example-basic",
    "state": "public",
    "beta": false,
  }
]*/
    def list = complete(api / "addons")
/*[
  {
    "name": "example:basic",
    "description": "Example Basic",
    "url": "http://devcenter.heroku.com/articles/example-basic",
    "state": "public",
    "beta": false,
    "configured": true
  }
]*/  
    def list(name: String) =
      complete(api / "apps" / name / "addons")
    def installed(name: String) =
      complete(api / "apps" / name / "addons")
    def install(name: String, addon: String) =
      complete(api.POST / "apps" / name / "addons" / addon)
    def upgrade(name: String, addon: String) =
      complete(api.PUT / "apps" / name / "addons" / addon)
    def uninstall(name: String, addon: String)=
      complete(api.DELETE / "apps" / name / "addons" / addon)
  }

  def domains(app: String) = new {
    def list = 
      complete(api / "apps" / app / "domains")
    def add(domain: String) =
      complete(api.POST / "apps" / app / "domains" << Map(
        "domain_name[domain]" -> domain
      ))
    def remove(domain: String) =
      complete(api.DELETE / "apps" / app / "domains" / domain)
    
  }

  def releases(app: String) = new {
    def list =
      complete(api / "apps" / app / "releases")
    def info(release: String) =
      complete(api / "apps" / app / "release" / release)
    def rollback(release: String) =
      complete(api.POST / "apps"/ app / "releases" << Map(
        "rollback" -> release
      ))
  }

  def keys = new {
    def list =
      complete(api / "user" / "keys")
    def add(key: String) =
      complete(api.POST / "user" / "keys" << key)
    def remove(userathost: String) =
      complete(api.DELETE / "user" / "keys" / userathost)
    def clear =
      complete(api.DELETE / "user" / "keys")
  }

  def logs = new {
    def lines(name: String, num: Int = 20, ps: Option[String] = None) =
      complete(api / "apps" / name / "logs" << Map("logplex" -> "true"))
  }

  def ps(app: String) = new {
    def list =
      complete(api / "apps" / app / "ps")
    def run(cmd: String) =  // todo support `attach`
      complete(api.POST / "apps" / app  << Map("commmand" -> cmd))
    def restart =
      complete(api.POST / "apps" / app / "ps" / "restart")
    def stop =
      complete(api.POST / "apps" / app / "ps" / "stop")
    def scale(typ: String, n: Int) =
      complete(api.POST / "apps" / app / "ps" / "scale" << Map("type" -> typ, "qty" -> n.toString))
  }
}
