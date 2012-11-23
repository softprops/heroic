package heroic

import sbt._
import Project.Initialize

case class Release(env: Map[String, String], pstable: Map[String, String],
                   commit: Option[String], descr: String, addons: Seq[String],
                   created_at: String, user: String, name: String)

case class Proc(ptype: String, cmd: String)

case class Domain(created_at: String, updated_at: String, default: Option[String],
                  domain: String, id: Int, app_id: String, base_domain: String)

case class Feature(kind: String, name: String, enabled:Boolean, docs: String, summary: String)

/** Provides Heroku deployment capability. */
object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import HeroKeys._
  import heroic.{ Git => GitCli }
  import Prompt._
  import dispatch._
  import net.liftweb.json._

  private [this] val DefaultRemote = "heroku"

  val stage = TaskKey[Unit]("stage", "Heroku installation hook")
  def stageTask(script: File, procfile: File, slugfile: File) = { /* noop */ }

  object HeroKeys {

    val hero = TaskKey[Unit]("hero", "Task for scoping all heroic settings")

    // app settings
    val equip = TaskKey[Unit](key("equip"), "Prepares project for Heroku deployment")
    val procfile = TaskKey[File](key("procfile"), "Writes Heroku Procfile to project base directory")
    val procs = TaskKey[Seq[Proc]](key("procs"), "List of procs to include in procfile")
    val scriptName = SettingKey[String](key("script-name"), "Name of script-file")
    val scriptFile = SettingKey[File](key("script-file"), "Target process for for Heroku web procfile key")
    val script = TaskKey[File](key("script"), "Generates script-file")
    val slugIgnored = TaskKey[Seq[String]](key("slug-ignored"), "List of items to ignore when transfering application")
    val slugIgnore = TaskKey[File](key("slug-ignore"), "Generates a Heroku .slugignore file in the base directory")
    
    // client settings

    val checkDependencies = TaskKey[Boolean](key("check-dependencies"), "Checks to see if required dependencies are installed")
    val local = TaskKey[Unit](key("local"), "Runs your web proc as Heroku would")

    // heroku api

    val authenticate= TaskKey[Unit](key("authenticate"), "Get or acquires heroku credentials")
    val deauthenticate = TaskKey[Unit](key("deauthenticate"), "Removes heroku credentials")
    val collaborators = InputKey[Unit](key("collaborators"), "Lists Heroku application collaborators")
    val collaboratorsAdd = InputKey[Unit](key("collaborators-add"), "Adds a Heroku application collaborator by email")
    val collaboratorsRm = InputKey[Unit](key("collaborators-rm"), "Removes a Heroku application collaborator by email")
    val logs = InputKey[Unit](key("logs"), "Invokes Heroku client logs command")
    val ps = InputKey[Unit](key("ps"), "Invokes Heroku client ps command")
    val create = InputKey[Unit](key("create"), "Invokes Heroku client create command")
    val destroy = InputKey[Unit](key("destroy"), "Deletes remote application")
    val info = InputKey[Unit](key("info"), "Displays Heroku deployment info")
    val workers = InputKey[Unit](key("workers"), "Scale the number of your apps worker processes")
    val dynos = InputKey[Unit](key("dynos"), "Scale the number or your apps dynos")
    val addons = InputKey[Unit](key("addons"), "Lists installed Heroku addons")
    val addonsAvailable = InputKey[Unit](key("addons-available"), "Lists available Heroku addons")
    val addonsInstall = InputKey[Unit](key("addons-install"), "Install a Heroku addon by name")
    val addonsUninstall = InputKey[Unit](key("addons-uninstall"), "Uninstall a Heroku addon by name")
    val config = InputKey[Unit](key("config"), "Lists available remote Heroku config properties")
    val configSet = InputKey[Unit](key("config-set"), "Adds a Heroku config property")
    val configDelete = InputKey[Unit](key("config-delete"), "Removes a Heroku config property")
    val maintenanceOff = InputKey[Unit](key("maint-off"), "Turns on Heroku Maintenance mode")
    val maintenanceOn = InputKey[Unit](key("maint-on"), "Turns off Heroku Maintenance mode")
    val releases = InputKey[Unit](key("releases"), "Lists all releases")
    val releaseInfo = InputKey[Unit](key("release-info"), "Shows info about a target release")
    val rollback = InputKey[Unit](key("rollback"), "Rolls back to a target release")
    val rename = InputKey[Unit](key("rename"), "Give your app a custom subdomain on heroku")
    val domains = InputKey[Unit](key("domains"), "List Heroku domains")
    val domainsAdd = InputKey[Unit](key("domains-add"), "Add a Heroku domain")
    val domainsRm = InputKey[Unit](key("domains-rm"), "Removes a Heroku domain")
    val keys = TaskKey[Unit](key("keys"), "Lists Heroku registered keys")
    val keysAdd = InputKey[Unit](key("keys-add"), "Adds a registed key with heroku")
    val keysRm = InputKey[Unit](key("keys-rm"), "Removes a registed key with heroku")

    // git settings TODO migrate these to use joshes new git plugin keys
    // also rm whats not really referenced below in the direct context of heroic
    val push = InputKey[Int](key("push"), "Pushes project to Heroku")

    private def key(name: String) = "hero-%s" format name

  }

  private def remoteOption(args: Seq[String]) =
    args match {
      case Nil => DefaultRemote
      case remote :: _ => remote
    }

  private def requireApp(remote: String ="heroku") =
    GitClient.remotes.get(remote) match {
      case Some(app) => app
      case _ => sys.error("No registered Heroku app for remote '%s'" format remote)
    }

  private def rootDir(state: State) =
    file(Project.extract(state).structure.root.toURL.getFile)

  private def relativeToRoot(state: State, f: File) =
    IO.relativize(rootDir(state), f)

  private def client[T](f: Client => T): T =
    Auth.credentials.map(k => f(new Client(BasicAuth(k))))
      .getOrElse(sys.error("Not authenticated. Try hero:auth"))

  def coreSettings: Seq[Setting[_]] = Seq(
    hero <<= (streams) map {
      (out) => out.log.info("Heroic is an interface for Heroku")
    }
  )

  def appSettings: Seq[Setting[_]] = Seq(
    (javaOptions in hero) <<= (javaOptions in run) map {
      case Nil => Seq("-Xmx256m","-Xss2048k")
      case provided => provided
    },
    mainClass in hero <<= (streams, mainClass in Runtime, discoveredMainClasses in Compile).map {
      (out, main, mains) => (main, mains) match {
        case (Some(m), _) => Some(m)
        case (_, ms) if(!ms.isEmpty) =>
          out.log.warn("No explict main class specified. Using first of %s" format ms)
          ms.headOption
        case _ =>
          out.log.warn("No main classes discovered")
          None
      }
    },
    scriptName := "hero",
    script <<= scriptTask,
    procs <<= (state, target, scriptName) map {
      (state, t, s) => {
        Seq(Proc("web", "sh %s/%s" format(relativeToRoot(state, t).get, s)))
      }
    },
    procfile <<= procfileTask,
    slugIgnored <<= (state, sourceDirectory in Test) map {
      (state, sd) =>
        Seq(relativeToRoot(state, sd).get)
    },
    slugIgnore <<= slugIgnoreTask,
    stage in Compile <<= (script, procfile, slugIgnore) map stageTask,
    equip <<= stage in Compile
  )

  def clientSettings: Seq[Setting[_]] = Seq(
    checkDependencies <<= checkDependenciesTask,
    collaborators <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        collaboratorsTask(out.log, remoteOption(args))
      }
    },

    collaboratorsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, email) = args match {
          case Seq(email) => ("heroku", email)
          case Seq(remote, email) => (remote, email)
          case _ => sys.error("usage hero:collaborators-add <email>")
        }
        client { cli =>
          out.log.info(
            cli.collaborators(requireApp(remote)).add(email)(as.String)()
          )
        }
      }
    },

    collaboratorsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, email) = args match {
          case Seq(email) => ("heroku", email)
          case Seq(remote, email) => (remote, email)
          case _ => sys.error("usage hero:collaborators-rm <email>")
        }
        client { cli =>
          out.log.info(
            cli.collaborators(requireApp(remote)).remove(email)(as.String)()
          )
        }
      }
    },

    authenticate <<= authenticateTask,
    deauthenticate <<= deauthenticateTask,
    local <<= localTask dependsOn(compile in Compile),

    logs <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
         val remote = remoteOption(args)
         client { cli =>
           out.log.info("Fetching recent remote logs")
           out.log.info(cli.logs.lines(requireApp(remote))(as.String)())
           //http(cli.logs(remote) >~ { src =>
           //   src.getLines().foreach(l => out.log.info(l))
           // })
         }
      }
    },

    ps <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        psTask(out.log, remoteOption(args))
      }
    },

    create <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        createTask(out.log, remoteOption(args))
      }
    },

    destroy <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        destroyTask(out.log, remoteOption(args))
      }
    },

    push <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        pushTask(out.log, remoteOption(args))
      }
    },

    info <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        infoTask(out.log, remoteOption(args))
      }
    },

    workers <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, n) = args match {
          case Seq(remote, n) =>
            (remote, n.toInt)
          case Seq(n) =>
            ("heroku", n.toInt)
          case _ => sys.error("usage: workers <n>")
        }
        workersTask(out.log, remote, n)
      }
    },

    dynos <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, n) = args match {
          case Seq(remote, n) =>
            (remote, n.toInt)
          case Seq(n) =>
            ("heroku", n.toInt)
          case _ => sys.error("usage: dynos <n>")
        }
        dynosTask(out.log, remote, n)
      }
    },

    config <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        configTask(out.log, remoteOption(args))
      }
    },

    configSet <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, key, value) = args match {
          case Seq(key, value) => (DefaultRemote, key, value)
          case Seq(remote, key, value) => (remote, key, value)
          case _ => sys.error("usage: hero:conf-add <key> <val>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("assigning %s config var %s to %s" format(app, key, value))
          val req = cli.config(app).set((key, value))(as.lift.Json)
          val resp = for {
            JObject(fields)             <- req()
            JField(key, JString(value)) <- fields
          } yield (key, value)
          out.log.info("Updated config")
          printMap(resp.toMap, out.log)
        }
      }
    },

    configDelete <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, key) = args match {
          case Seq(key) => (DefaultRemote, key)
          case Seq(remote, key) => (remote, key)
          case _ => sys.error("usage: hero:conf-rm <key>");
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("removing %s config var %s" format(app, key))
          val req = cli.config(app).delete(key)(as.lift.Json)
          val resp = for {
            JObject(fields)             <- req()
            JField(key, JString(value)) <- fields
          } yield (key, value)
          out.log.info("Updated config")
          printMap(resp.toMap, out.log)
        }
      }
    },

    addons <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        addonsTask(out.log, remoteOption(args))
      }
    },

    addonsAvailable <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        addonsAvailableTask(out.log, remoteOption(args))
      }
    },

    addonsInstall <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, addon) = args match {
          case Seq(addon) => (DefaultRemote, addon)
          case Seq(remote, addon) => (remote, addon)
          case _ => sys.error("usage hero:addons-add <feature>")
        }

        client { cli =>
          val app = requireApp(remote)
          out.log.info("Requesting addon for %s" format app)
          val req = cli.addons.install(app, addon)(as.lift.Json)
          val resp = for {
            JObject(fields) <- req()
            JField("status", JString(status)) <- fields
            JField("price", JString(price)) <- fields
          } yield (status, price)
          if (resp.isEmpty) {
            val errs = for {
              JObject(fields)               <- req()
              JField("error", JString(err)) <- fields
            } yield err
            if (errs.isEmpty) out.log.warn("Error installing addon")
            else out.log.warn(errs(0))
          } else {
            val (status, price) = resp(0)
            out.log.info("Addon status %s | price %s" format(status, price))
          }
        }
      }
    },

    addonsUninstall <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, feature) = args match {
          case Seq(feature) => ("heroku", feature)
          case Seq(remote, feature) => (remote, feature)
          case _ => sys.error("usage hero:addons-rm <feature>")
        }
        client { cli =>
          out.log.info("Requesting addon removal")
          out.log.info(cli.addons.uninstall(requireApp(remote), feature)(as.String)())
          /*try {
            http(cli.addons(remote).rm(feature) >|)
            out.log.info("Removed addon %s" format feature)
          } catch {
            case dispatch.StatusCode(422, msg) =>
              val resp = parse[Map[String, String]](msg)
              sys.error(
                "Error removing addon %s" format resp("error")
              )
          }*/
        }
      }
    },
    
    maintenanceOn <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        maintenanceOnTask(out.log, remoteOption(args))
      }
    },

    maintenanceOff <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        maintenanceOffTask(out.log, remoteOption(args))
      }
    },

    releases <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        releasesTask(out.log, remoteOption(args))
      }
    },
    releaseInfo <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, rel) = args match {
          case Seq(rel) => ("heroku", rel)
          case Seq(remote, rel) => (remote, rel)
          case _ => sys.error("usage: hero:release-info <rel>")
        }
        out.log.info("Fetching release listing")
        client { cli =>
          out.log.info(cli.releases(requireApp(remote)).info(rel)(as.String)())
        }
      }
    },

    rollback <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, to) = args match {
          case Seq(to) => ("heroku", to)
          case Seq(remote, to) => (remote, to)
          case _ => sys.error("usage: hero:rollback <to>")
        }
        client { cli =>
          out.log.info("Rolling back release")
          out.log.info(
            cli.releases(requireApp(remote)).rollback(to)(as.String)()
          )
        }
      }
    },

    rename <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, name) = args match {
          case Seq(name) => (DefaultRemote, name)
          case Seq(remote, name) => (remote, name)
          case _ => sys.error("usage: hero:rename <subdomain>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Requesting to rename subdomain %s to %s" format(app, name))
          try {
            val req = cli.apps.rename(app, name)(as.lift.Json)
            val resp = for {
              JObject(fields)                  <- req()
              JField("name", JString(newname)) <- fields
            } yield newname
            resp match {
              case Nil =>
                out.log.warn("failed to rename %s to %s" format(app, name))
              case newname :: _ =>
                out.log.info("renamed app to %s" format newname)
                GitClient.updateRemote(newname, remote)
                out.log.info("updated git remote")
            }
          } catch {
            case dispatch.StatusCode(406) =>
              out.log.warn("Failed to rename app.")
            case dispatch.StatusCode(404) =>
              out.log.warn("Failed to rename app.")
          }
        }
      }
    },

    domains <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        domainsTask(out.log, remoteOption(args))
      }
    },

    domainsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, dom) =
          args match {
            case dom :: Nil => ("heroku", dom)
            case remote :: dom :: Nil => (remote, dom)
            case _ => sys.error(
              "usage: hero:domains-add <domain>"
            )
          }
        domainsAddTask(out.log, remote, dom)
      }
    },

    domainsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, dom) =
          args match {
            case dom :: Nil => ("heroku", dom)
            case remote :: dom :: Nil => (remote, dom)
            case _ => sys.error(
              "usage: hero:domains-rm <domain>"
            )
          }
        domainsRmTask(out.log, remote, dom)
      }
    },

    keys <<= keysTask,

    keysAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        client { cli =>
          args match {
            case Seq(kf) =>
              file(kf) match {
                case f if(f.exists) =>
                  out.log.info(cli.keys.add(IO.read(f))(as.String)())
                  out.log.info("Registered key")
                case f => sys.error("%s does not exist" format f)
              }
            case _ => sys.error("usage: hero:keys-add <path-to-key>")
          }
        }
      }
    },
    keysRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        client { cli =>
          args match {
            case Seq() =>
              val yn = ask("Are you sure you want to deregister all app keys? [Y/N] ") {
                _.trim.toLowerCase
              }
              if(Prompt.Okays contains yn) {
                out.log.info("Deregistering keys")
                /*try {
                  out.log.info(http(cli.keys.clear as_str))
                  out.log.info("Deregistered keys")
                } catch {
                  case dispatch.StatusCode(404, msg) =>
                    out.log.warn(msg)
                }*/
              } else if(Prompt.Nos contains yn) {
                out.log.info("Canceling request")
              } else sys.error("Unexpected response %s" format yn)
            case kf =>
              file(kf mkString(" ")) match {
                case f if(f.exists) =>
                  val yn = ask("Are you sure you want to deregister this key? [Y/N] ") {
                    _.trim.toLowerCase
                  }
                  if(Prompt.Okays contains yn) {
                    out.log.info("Deregistering key")
                    try {
                      val contents = IO.read(f)
                      out.log.debug(contents)
                      out.log.info(cli.keys.remove(contents)(as.String)())
                      out.log.info("Deregistered key")
                    } catch {
                      case dispatch.StatusCode(404) =>
                        out.log.warn("not found")
                    }
                  } else if(Prompt.Nos contains yn) {
                    out.log.info("Canceling request")
                  } else sys.error("Unexpected response %s" format yn)
                case f => sys.error("%s does not exist" format f)
              }
            //case _ => sys.error("usage: hero:keys-rm <path-to-key>")
          }
        }
      }
    }
  )

  def withLog[T](f: Logger => T): Initialize[Task[T]] =
    (streams) map { o => f(o.log) }

  def authenticateTask: Initialize[Task[Unit]] =
    withLog(l => Auth.acquireCredentials(l))

  def deauthenticateTask: Initialize[Task[Unit]] =
    withLog(l => Auth.removeCredentials(l))

  private def exec(pb: => ProcessBuilder, msg: String = "", onSuccess: String = ""): Initialize[Task[Int]] =
    withLog { l =>
        if(!msg.isEmpty) l.info(msg)
        val stat = pb ! l
        if(stat == 0 && !onSuccess.isEmpty) l.info(onSuccess)
        stat
    }

  private def keysTask: Initialize[Task[Unit]] =
    withLog { l =>
      client { cli =>
        val keys = for {
          JObject(fields)                      <- cli.keys.list(as.lift.Json)()
          JField("contents", JString(content)) <- fields
          JField("email", JString(email))      <- fields
        } yield (email, content)
        if (keys.isEmpty) l.warn("no registered keys")
        keys map {
          case (e, c) =>
            l.info("=== %s\n%s" format(e,c))
        }
      }
    }

  private def collaboratorsTask(l: Logger, remote: String) =
    client { cli =>
      l.info(cli.collaborators(requireApp(remote)).list(as.String)())
    }

  private def domainsTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Fetching Heroku domains for %s..." format app)
      val req = cli.domains(app).list(as.lift.Json)
      val domains = for {
        JObject(fields)                   <- req()
        JField("domain", JString(domain)) <- fields
      } yield domain
      if (domains.isEmpty) l.info("This app has no registered domains")
      else {
        l.info("Domains")
        domains.foreach(d => l.info("- %s" format d))
      }
    }

  private def domainsAddTask(l: Logger, remote: String, domain: String) =
    client { cli =>
      val app = requireApp(remote)
      val req = cli.domains(app).add(domain)(as.lift.Json)
      val domains = for {
        JObject(fields)                <- req()
        JField("domain", JString(dom)) <- fields
      } yield dom
      if (domains.isEmpty) l.warn("domain %s was already taken" format domain)
      else l.info("Added Heroku domain %s" format domains(0))
    }

  private def domainsRmTask(l: Logger, remote: String, domain: String) =
    client { cli =>
      val app = requireApp(remote)
      val req = cli.domains(app).remove(domain)(as.lift.Json)
      val domains = for {
        JObject(fields)                <- req()
        JField("domain", JString(dom)) <- fields
      } yield dom
      if (domains.isEmpty) l.warn("failed to remove domain %s" format domain)
      else l.info("Removed domain %s" format domains(0))
    }

  private def printRelease(r: Release, log: Logger, details: Boolean = false) = {
    log.info("=== %s" format r.name)
    log.info("created %s by %s %s" format(r.created_at, r.user, 
      if(r.commit isDefined) "(%s)" format(r.commit.get) else ""
    ))
    if(details) {
      log.info("ps table:")
      printMap(r.pstable, log)
      log.info("env:")
      printMap(r.env, log)
      log.info("addons:")
      r.addons.foreach(log.info(_))
    }
  }

  private def releasesTask(l: Logger, remote: String) =
    client { cli =>
      l.info(cli.releases(requireApp(remote)).list(as.String)())
    }

  private def maintenanceOnTask(l: Logger, remote: String) =
    client { cli =>
      l.info(
        cli.apps.maintenance(requireApp(remote),true)(as.String)()
      )
      l.info("Maintenance mode enabled.")
    }

  private def maintenanceOffTask(l: Logger, remote: String) =
    client { cli =>
      l.info(
        cli.apps.maintenance(requireApp(remote), false)(as.String)()
      )
      l.info("Maintenance mode disabled.")
    }

  private def localTask: Initialize[Task[Unit]] =
    (state, streams) map {
      (state, out) =>
        out.log.info("Running Procfile process(es). Press any key to stop.")
        val bd = file(Project.extract(state).structure.root.toURL.getFile)
        val p = Procman.start(new File(bd, "ProcFile"), out.log)
        def detectInput() {
          try { Thread.sleep(1000) } catch { case _: InterruptedException => }
          if(System.in.available() <= 0) detectInput()
        }
        detectInput()
        p.foreach(_.destroy())
        out.log.info("Process complete")
    }

  private def psTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Fetching process info for %s" format app)      
      println(cli.ps(app).list(as.String)())
      /*val px = parse[Seq[Map[String, String]]](
        http(cli.ps(remote) as_str)
      )
      px.foreach { p =>
        l.info(
          "%s %s %s" format(
            p("process"), p("pretty_state"), p("command")
          )
        )
      }*/
    }

  private def infoTask(l: Logger, remote: String) =
    client { cli =>
      l.info("Fetching App info")
      l.info(cli.apps.info(requireApp(remote))(as.String)())
    }

  private def workersTask(l: Logger, remote: String, n: Int) =
    client { cli =>
      l.info("Scaling App Workers")
      //l.info(http(cli.workers(n, HerokuClient.requireApp(remote)) as_str))
    }

  private def dynosTask(l: Logger, remote: String, n: Int) =
    client { cli =>
      l.info("Scaling App Dynos")
      //l.info(http(cli.dynos(n, HerokuClient.requireApp(remote)) as_str))
    }

  private def addonsTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      val req = cli.addons.installed(app)(as.lift.Json)
      val addons = for {
        JArray(ax) <- req()
        JObject(fields) <- ax
        JField("name", JString(name))        <- fields
        JField("description", JString(desc)) <- fields
      } yield (name, url, desc)
      if (addons.isEmpty) {
       l.warn(""+req())
        l.warn("No addons installed for %s. Install with hero-addon-install <name>" format app)
      } else {
        l.info("Addons installed")
        addons.foreach {
          case (name, url, desc) =>
            l.info("%s (%s)" format(desc, name))
        }
      }
    }

  private def addonsAvailableTask(l: Logger, remote: String) =
    client { cli =>
      val req = cli.addons.list(as.lift.Json)
      val addons = for {
        JArray(ax)                           <- req()
        JObject(fields)                      <- ax
        JField("name", JString(name))        <- fields
        JField("url", JString(url))          <- fields
        JField("description", JString(desc)) <- fields
      } yield (name, url, desc)
      if (addons.isEmpty) l.warn("No addons available")
      else {
        l.info("Addons")
        addons.foreach {
          case (name, url, desc) =>
            l.info("%s (%s) - %s" format(desc, name, url))
        }
      }
    }

  private def printMap(m: Map[String, String], log: Logger) =
    if(!m.isEmpty) {
      val disp =
        "%-" +
        (m.keys.toSeq.sortWith(_.size > _.size).head.size) +
        "s -> %s"
      m.foreach { case (k, v) =>
        log.info(disp format(k, v))
      }
    }

  private def configTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Fetching remote configuration for %s" format app)
      try {
        val req = cli.config(app).list(as.lift.Json)
        val resp = for {
          JObject(fields)             <- req()
          JField(key, JString(value)) <- fields
        } yield (key, value)
        val config = resp.toMap
        if (config.isEmpty) l.info("empty config. add configuration with hero-config-set <key> <value>.")
        else printMap(resp.toMap, l)
      } catch {
        case _ => l.info("Empty config")
      }
    }

  private def createTask(l: Logger,
                         remote: String = "heroku",
                         name: Option[String] = None,
                         stack: Option[String] = None) =
    client { cli =>
      // todo: check for git support before attempting to create a remote instance
      l.info("Creating remote Heroku application. (remote name '%s')" format remote)
      val req = cli.apps.create(name, stack)(as.lift.Json)
      val response = for { JObject(fields)       <- req()
        JField("name", JString(name))            <- fields
        JField("create_status", JString(status)) <- fields
      } yield (name, status)
      response.headOption.map {
        case (name, status) =>
          if ("complete" == status) {
            l.info("Created app %s" format name)
            l.info("git: git@heroku.com:%s.git | http: %s.herokuapp.com" format(name, name))
            if (GitClient.addRemote(name, remote) > 0) l.error("Error adding git remote: %s" format remote)
            else l.info("Added git remote: %s" format remote)
          } else l.warn("create status of %s is %s" format(name, status))
      }.getOrElse(
        l.warn("failed to create remote application: %s" format req())
      )
    }

  private def destroyTask(l: Logger, remote: String) = {
    val confirm = ask(
      "Are you sure you want to destory this application: [Y/N] ") {
      _.trim.toLowerCase
    }
    if(Prompt.Nos contains confirm) l.info("Cancelled request")
    else if(Prompt.Okays contains confirm) client { cli =>
      l.info("Destroying remote application")
      try {
        l.info(cli.apps.destory(requireApp(remote))(as.String)())
        l.info("Remote application destroyed")
        GitClient.remoteRm(remote)
        l.info("Removed heroku git remote '%s'" format remote)
      } catch {
        case dispatch.StatusCode(404) =>
          l.warn("Remote app did not exist")
      }
    } else sys.error("unexpected answer %s" format confirm)
  }

  // todo: check for local changes...
  private def pushTask(l: Logger, remote: String) = {
    l.info("Updating application (this may take a few seconds)")
    val stat = GitCli.push(remote) ! l
    if(stat == 0) l.info("Check the status of your application with `hero:ps` or `hero:logs`")
    stat
  }

  private def procfileTask: Initialize[Task[File]] =
    (state, procs, streams) map {
      (state, procs, out) =>
        new File(rootDir(state), "Procfile") match {
          case pf if(pf.exists) => pf
          case pf =>
            out.log.info("Writing Procfile")
            IO.write(pf, Procfile(procs))
            pf
        }
    }

  /* http://devcenter.heroku.com/articles/slug-compiler
     http://devcenter.heroku.com/articles/slug-size
   the docs say to ignore everything that isn't required to run an
   application.  */
  private def slugIgnoreTask: Initialize[Task[File]] =
    (state, slugIgnored, streams) map {
      (state, ignores, out) =>
        new java.io.File(rootDir(state), ".slugignore") match {
          case si if(si.exists) => si
          case si =>
            out.log.info("Writing .slugignore")
            IO.write(si, ignores.mkString("\n"))
            si
        }
    }

  private def checkDependenciesTask: Initialize[Task[Boolean]] =
    withLog { l =>
      val install = (Map.empty[String, Boolean] /: Seq("git"))(
        (a,e) =>
          try {
            a + (e -> Process("which %s" format(e)).!!.matches(".*%s\\s+".format(e)))
          } catch {
            case _ => a + (e -> false)
          }
      )
      install.foreach(_ match {
        case (cmd, inst) =>
          if(inst) l.info("\033[0;32minstalled\033[0m %s" format cmd)
          else l.warn("\033[0;31mmissing  \033[0m %s" format cmd)
      })
      install.filter(!_._2).isEmpty
    }

  private def scriptTask: Initialize[Task[File]] =
    (mainClass in hero, streams, fullClasspath in Runtime, state,
      target, scriptName, javaOptions in hero) map {
      (main, out, cp, state, target, sn, jvmOpts) => main match {
        case Some(mainCls) =>
          val scriptBody = Script(mainCls, cp.files map { f =>
            relativeToRoot(state, f) match {
              case Some(rel) => rel
              case _ => f.getAbsolutePath
            }
          }, jvmOpts)
          val sf = new File(target, sn)
          out.log.info("Writing hero file, %s" format sf)
          IO.write(sf, scriptBody)
          sf.setExecutable(true)
          sf

        case _ => sys.error("Main class required")
      }
    }

  def heroicSettings: Seq[Setting[_]] = coreSettings ++ appSettings ++ clientSettings
}
