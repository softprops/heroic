package heroic

import sbt._
import Project.Initialize

case class Proc(typ: String, cmd: String)

/** Heroku interface for sbt */
object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import HeroKeys._
  import Prompt._
  import dispatch._
  import net.liftweb.json._

  private val DefaultRemote = "heroku"

  val stage = TaskKey[Unit]("stage", "Heroku installation hook")

  def stageTask(script: File, procfile: File, slugfile: File) = { /* noop */ }

  object HeroKeys {

    val hero = TaskKey[Unit]("hero", "Task for scoping all heroic settings")

    // app settings
    val equip = TaskKey[Unit]("equip", "Prepares project for Heroku deployment")
    val procfile = TaskKey[File]("procfile", "Writes Heroku Procfile to project base directory")
    val procs = TaskKey[Seq[Proc]]("procs", "List of procs to include in procfile")
    val scriptName = SettingKey[String]("script-name", "Name of script-file")
    val scriptFile = SettingKey[File]("script-file", "Target process for for Heroku web procfile key")
    val script = TaskKey[File]("script", "Generates script-file")
    val slugIgnored = TaskKey[Seq[String]]("slug-ignored", "List of items to ignore when transfering application")
    val slugIgnore = TaskKey[File]("slug-ignore", "Generates a Heroku .slugignore file in the base directory")
    
    // client settings

    val checkDependencies = TaskKey[Boolean]("check-dependencies", "Checks to see if required dependencies are installed")
    val local = TaskKey[Unit]("local", "Runs your web proc as Heroku would")

    // heroku api

    val authenticate = TaskKey[Unit]("authenticate", "Get or acquires heroku credentials")
    val deauthenticate = TaskKey[Unit]("deauthenticate", "Removes heroku credentials")
    val collaborators = InputKey[Unit]("collaborators", "Lists Heroku application collaborators")
    val collaboratorsAdd = InputKey[Unit]("collaborators-add", "Adds a Heroku application collaborator by email")
    val collaboratorsRm = InputKey[Unit]("collaborators-rm", "Removes a Heroku application collaborator by email")
    val logs = InputKey[Unit]("logs", "Invokes Heroku client logs command")
    val ps = InputKey[Unit]("ps", "Invokes Heroku client ps command")
    val create = InputKey[Unit]("create", "Invokes Heroku client create command")
    val destroy = InputKey[Unit]("destroy", "Deletes remote application")
    val info = InputKey[Unit]("info", "Displays Heroku deployment info")
    val scale = InputKey[Unit]("scale", "Scale the number of processes for a given process type")
    val addonsInstalled = InputKey[Unit]("addons-installed", "Lists installed Heroku addons")
    val addons = InputKey[Unit]("addons", "Lists available Heroku addons")
    val addonsInstall = InputKey[Unit]("addons-install", "Install a Heroku addon by name")
    val addonsUninstall = InputKey[Unit]("addons-uninstall", "Uninstall a Heroku addon by name")
    val config = InputKey[Unit]("config", "Lists available remote Heroku config properties")
    val configSet = InputKey[Unit]("config-set", "Adds a Heroku config property")
    val configDelete = InputKey[Unit]("config-delete", "Removes a Heroku config property")
    val maintenanceOff = InputKey[Unit]("maint-off", "Turns on Heroku Maintenance mode")
    val maintenanceOn = InputKey[Unit]("maint-on", "Turns off Heroku Maintenance mode")
    val releases = InputKey[Unit]("releases", "Lists all releases")
    val releaseInfo = InputKey[Unit]("release-info", "Shows info about a target release")
    val rollback = InputKey[Unit]("rollback", "Rolls back to a target release")
    val rename = InputKey[Unit]("rename", "Give your app a custom subdomain on heroku")
    val domains = InputKey[Unit]("domains", "List Heroku domains")
    val domainsAdd = InputKey[Unit]("domains-add", "Add a Heroku domain")
    val domainsRm = InputKey[Unit]("domains-rm", "Removes a Heroku domain")
    val keys = TaskKey[Unit]("keys", "Lists Heroku registered keys")
    val keysAdd = InputKey[Unit]("keys-add", "Adds a registed key with heroku")
    val keysRm = InputKey[Unit]("keys-rm", "Removes a registed key with heroku")

    // git settings TODO migrate these to use joshes new git plugin keys
    // also rm whats not really referenced below in the direct context of heroic
    val deploy = InputKey[Int]("deploy", "Deploys project to Heroku")
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
      .getOrElse(sys.error("Not authenticated. Try hero::authenticate"))

  def coreSettings: Seq[Setting[_]] = Seq(
    hero <<= (streams) map {
      (out) => out.log.info("Heroic is an interface for Heroku")
    }
  )

  /** settings used for application deployment and staging */
  def appSettings: Seq[Setting[_]] = Seq(
    javaOptions in hero <<= (javaOptions in run) map {
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
          out.log.warn("No main classes resolved")
          None
      }
    },
    scriptName in hero := "hero",
    script in hero <<= scriptTask,
    procs in hero <<= (state, target, scriptName in hero) map {
      (state, t, s) => {
        Seq(Proc("web", "sh %s/%s" format(relativeToRoot(state, t).get, s)))
      }
    },
    procfile in hero <<= procfileTask,
    slugIgnored in hero <<= (state, sourceDirectory in Test) map {
      (state, sd) =>
        Seq(relativeToRoot(state, sd).get)
    },
    slugIgnore in hero <<= slugIgnoreTask,
    stage in Compile <<= (script in hero, procfile in hero, slugIgnore in hero) map stageTask,
    equip in hero <<= stage in Compile
  )

  /** settings used for interacting with the heroku api */
  def clientSettings: Seq[Setting[_]] = Seq(
    checkDependencies <<= checkDependenciesTask,

    collaborators in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        collaboratorsTask(out.log, remoteOption(args))
      }
    },

    collaboratorsAdd in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, email) = args match {
          case Seq(email) => (DefaultRemote, email)
          case Seq(remote, email) => (remote, email)
          case _ => sys.error("usage hero::collaborators-add <email>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Adding collaborator %s for %s" format(email, app))
          out.log.info(cli.collaborators(app).add(email)(as.String)())
        }
      }
    },

    collaboratorsRm in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, email) = args match {
          case Seq(email) => ("heroku", email)
          case Seq(remote, email) => (remote, email)
          case _ => sys.error("usage hero::collaborators-rm <email>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Removing collaborator %s for %s" format(email, app))
          out.log.info(cli.collaborators(app).remove(email)(as.String)())
        }
      }
    },

    authenticate in hero <<= authenticateTask,
    deauthenticate in hero <<= deauthenticateTask,
    local in hero <<= localTask dependsOn(compile in Compile),

    logs in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
         val remote = remoteOption(args)
         client { cli =>
           val app = requireApp(remote)
           out.log.info("Fetching recent remote logs for %s" format app)
           // fixme: in a future version, figure out why tail + as.stream.Lines 
           //        does not work :/
           // todo: the client supports a lines(num) option
           //       let's enable that
           val loglines = for {
             lurl    <- cli.logs(app)(as.String)
             lines   <- Http(url(lurl) > as.String)
           } yield lines
           out.log.info(loglines())
         }
      }
    },

    ps in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        psTask(out.log, remoteOption(args))
      }
    },

    create in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        createTask(out.log, remoteOption(args))
      }
    },

    destroy in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        destroyTask(out.log, remoteOption(args))
      }
    },

    // potential for removal (its just git push heroku master)
    deploy in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        pushTask(out.log, remoteOption(args))
      }
    },

    info in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        infoTask(out.log, remoteOption(args))
      }
    },

    scale in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, typ, n) = args match {
          case Seq(remote, typ, n) =>
            (remote, typ, n.toInt)
          case Seq(typ, n) =>
            (DefaultRemote, typ, n.toInt)
          case _ => sys.error("usage: hero::scale <type> <n>")
        }
        scaleTask(out.log, remote, typ, n)
      }
    },

    config in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        configTask(out.log, remoteOption(args))
      }
    },

    configSet in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, key, value) = args match {
          case Seq(key, value) => (DefaultRemote, key, value)
          case Seq(remote, key, value) => (remote, key, value)
          case _ => sys.error("usage: hero::config-set <key> <val>")
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

    configDelete in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, key) = args match {
          case Seq(key) => (DefaultRemote, key)
          case Seq(remote, key) => (remote, key)
          case _ => sys.error("usage: hero::config-rm <key>");
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

    addonsInstalled in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        addonsInstalledTask(out.log, remoteOption(args))
      }
    },

    addons in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        addonsTask(out.log)
      }
    },

    addonsInstall in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, addon) = args match {
          case Seq(addon) => (DefaultRemote, addon)
          case Seq(remote, addon) => (remote, addon)
          case _ => sys.error("usage hero::addons-install <feature>")
        }

        client { cli =>
          val app = requireApp(remote)
          out.log.info("Requesting addon for %s" format app)
          val req = cli.addons.install(app, addon)(as.lift.Json)
          val resp = for {
            JObject(fields)                   <- req()
            JField("status", JString(status)) <- fields
            JField("price", JString(price))   <- fields
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

    addonsUninstall in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, feature) = args match {
          case Seq(feature) => (DefaultRemote, feature)
          case Seq(remote, feature) => (remote, feature)
          case _ => sys.error("usage hero::addons-uninstall <feature>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Requesting addon removal for %s" format app)
          val req = cli.addons.uninstall(app, feature)(as.lift.Json)
          val status = for {
            JField("status", JString(status)) <- req()
          } yield status
          if  (status.isEmpty) out.log.warn("Addon removal failed")
          else out.log.info("Add on removal status: %s" format status(0))
        }
      }
    },
    
    maintenanceOn in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        maintenanceOnTask(out.log, remoteOption(args))
      }
    },

    maintenanceOff in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        maintenanceOffTask(out.log, remoteOption(args))
      }
    },

    releases in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        releasesTask(out.log, remoteOption(args))
      }
    },

    releaseInfo in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, rel) = args match {
          case Seq(rel) => (DefaultRemote, rel)
          case Seq(remote, rel) => (remote, rel)
          case _ => sys.error("usage: hero::release-info <rel>")
        }
        val app = requireApp(remote)
        out.log.info("Fetching release %s info for %s" format(rel, app))
        client { cli =>
          val req = cli.releases(app).info(rel)(as.lift.Json)
          val release = for {
            JObject(fields)                      <- req()
            JField("name", JString(name))        <- fields
            JField("descr", JString(desc))       <- fields
            JField("user", JString(user))        <- fields
            JField("commit", JString(commit))    <- fields
            JField("env", JObject(envfields))    <- fields
            JField("pstable", JObject(psfields)) <- fields
          } yield {
            def it(ary: List[JField]): Iterable[(String, String)] = for {
              JField(key, JString(value)) <- ary
            } yield (key, value)
            (name, desc, user, commit,
             it(envfields),
             it(psfields))
          }
          if (release.isEmpty) out.log.info("Release %s for %s not found" format(rel, app))
          else release(0) match {
            case (name, desc, user, commit, env, processes) =>
              out.log.info(
                """== %s release %s
                | * Commit %s by %s
                | * "%s"
                |= Processes
                |%s
                |= Env
                |%s
                """.stripMargin.format(
                  app, name,
                  user, commit,
                  desc,
                  processes.map { case (k, v) => "%s : %s".format(k, v) } mkString("\n"),
                  env.map       { case (k, v) => "%s : %s".format(k, v) } mkString("\n")))
          }
        }
      }
    },

    rollback in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, to) = args match {
          case Seq(to) => (DefaultRemote, to)
          case Seq(remote, to) => (remote, to)
          case _ => sys.error("usage: hero::rollback <to>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Rolling back %s release" format app)
          out.log.info(
            cli.releases(app).rollback(to)(as.String)()
          )
        }
      }
    },

    rename in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, name) = args match {
          case Seq(name) => (DefaultRemote, name)
          case Seq(remote, name) => (remote, name)
          case _ => sys.error("usage: hero::rename <subdomain>")
        }
        client { cli =>
          val app = requireApp(remote)
          out.log.info("Requesting to rename subdomain %s to %s" format(app, name))
          val req = cli.apps.rename(app, name)(as.lift.Json)
          val resp = for {
            JObject(fields)                  <- req()
            JField("name", JString(newname)) <- fields
          } yield newname
          resp match {
            case Nil =>
              out.log.warn("failed to rename %s to %s" format(app, name))
            case newname :: _ =>
              out.log.info("Renamed app to %s" format newname)
            GitClient.updateRemote(newname, remote)
            out.log.info("Updated git remote")
          }
        }
      }
    },

    domains in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        domainsTask(out.log, remoteOption(args))
      }
    },

    domainsAdd in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, dom) =
          args match {
            case dom :: Nil => (DefaultRemote, dom)
            case remote :: dom :: Nil => (remote, dom)
            case _ => sys.error(
              "usage: hero::domains-add <domain>"
            )
          }
        domainsAddTask(out.log, remote, dom)
      }
    },

    domainsRm in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val (remote, dom) =
          args match {
            case dom :: Nil => (DefaultRemote, dom)
            case remote :: dom :: Nil => (remote, dom)
            case _ => sys.error(
              "usage: hero::domains-rm <domain>"
            )
          }
        domainsRmTask(out.log, remote, dom)
      }
    },

    keys in hero <<= keysTask,

    keysAdd in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
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
            case _ => sys.error("usage: hero::keys-add <path-to-key>")
          }
        }
      }
    },

    keysRm in hero <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        client { cli =>
          args match {
            case Seq() =>
              val yn = ask("Are you sure you want to deregister all app keys? [Y/N] ") {
                _.trim.toLowerCase
              }
              if (Prompt.Okays contains yn) {
                out.log.info("Deregistering keys")
                cli.keys.clear(as.String)
              } else if(Prompt.Nos contains yn) {
                out.log.info("Canceling request")
              } else sys.error("Unexpected response %s" format yn)
            case Seq(userathost) =>
              val yn = ask("Are you sure you want to deregister this key? [Y/N] ") {
                _.trim.toLowerCase.replaceAll("[.]","%2E")
              }
              if (Prompt.Okays contains yn) {
                out.log.info("Deregistering key %s" format userathost)
                out.log.info(cli.keys.remove(userathost)(as.String)())
                out.log.info("Deregistered key %s" format userathost)
              }
              else if (Prompt.Nos contains yn) out.log.info("Canceling request")
              else sys.error("Unexpected response %s" format yn)
            case _ => sys.error("usage: hero::keys-rm <user@host>")
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
      val app = requireApp(remote)
      val req = cli.collaborators(app).list(as.lift.Json)
      val collabs = for {
        JArray(cx)                        <- req()
        JObject(fields)                   <- cx
        JField("email", JString(email))   <- fields
        JField("access", JString(access)) <- fields
      } yield (email, access)
      l.info("Collaborators on %s" format app)
      if (collabs.isEmpty) l.info("No collaborators")
      else collabs.foreach {
        case (e,a) =>
          l.info("%s (%s)" format(e, a))
      }
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
      if (domains.isEmpty) l.warn("Domain %s was already taken" format domain)
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
      if (domains.isEmpty) l.warn("Failed to remove domain %s" format domain)
      else l.info("Removed domain %s" format domains(0))
    }

  private def releasesTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Fetching releases for %s" format app)
      val req = cli.releases(app).list(as.lift.Json)
      val releases = for {
        JArray(rx)                        <- req()
        JObject(fields)                   <- rx
        JField("name", JString(name))     <- fields
        JField("commit", JString(commit)) <- fields
      } yield (name, commit)
      if (releases.isEmpty) l.info("No releases")
      else releases.foreach {
        case (name, commit) => l.info("%s (%s)\n".format(name, commit))
      }
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
      val req = cli.ps(app).list(as.lift.Json)
      val processes = for {
        JArray(px)                             <- req()
        JObject(fields)                        <- px
        JField("process", JString(process))    <- fields
        JField("pretty_state", JString(state)) <- fields
        JField("command", JString(cmd))        <- fields
      } yield (process, state, cmd)
      if (processes.isEmpty) l.warn("No live processes %s" format req())
      else processes.foreach {
        case (p, s, c) => l.info("%s %s %s" format (p,s,c))
      }
    }

  private def infoTask(l: Logger, remote: String) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Fetching app info for %s" format app)
      val req = cli.apps.info(app)(as.lift.Json)
      val inf = for {
        JObject(fields)                     <- req()
        JField("name", JString(name))       <- fields
        JField("stack", JString(stack))     <- fields
        JField("git_url", JString(giturl))  <- fields
        JField("web_url", JString(weburl))  <- fields
        JField("dynos", JInt(dynos))        <- fields
        JField("workers", JInt(workers))    <- fields
        JField("slug_size", slug)           <- fields
        JField("repo_size", repo)           <- fields
      } yield (name, stack, giturl, weburl, dynos, workers, slug, repo)
      if (inf.isEmpty) l.warn("Info for %s could not be retrieved %s" format(app, req()))
      else inf(0) match {
        case (name, stack, giturl, weburl,
              dynos, workers, slug, repo) => l.info(
          """
          | %s (using %s)
          | git: %s
          | http: %s
          | dynos: %s
          | workers: %s
          """.stripMargin.format(
            name, stack,
            giturl,
            weburl,
            dynos,
            workers
          )
        )
      }
    }

  private def scaleTask(l: Logger, remote: String, typ: String, n: Int) =
    client { cli =>
      val app = requireApp(remote)
      l.info("Scaling %s %s processes" format(app, typ))
      val req = cli.ps(app).scale(typ, n)(as.String)
      l.info(req())
    }

  private def addonsInstalledTask(l: Logger, remote: String) =
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
        addons.sortBy(_._1).foreach {
          case (name, url, desc) =>
            l.info("%s (%s)" format(desc, name))
        }
      }
    }

  private def addonsTask(l: Logger) =
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
        l.info("Addons available")
        addons.sortBy(_._1).foreach {
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
        if (config.isEmpty) l.info("Empty config. Add configuration with hero-config-set <key> <value>.")
        else printMap(resp.toMap, l)
      } catch {
        case _ => l.info("Empty config")
      }
    }

  private def createTask(l: Logger,
                         remote: String = DefaultRemote,
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
        cli.apps.destory(requireApp(remote))(as.String)()
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
    val stat = GitClient.push(remote, l)
    if(stat == 0) l.info("Check the status of your application with `hero:ps` or `hero:logs`")
    stat
  }

  private def procfileTask: Initialize[Task[File]] =
    (state, procs in hero, streams) map {
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
     The docs say to ignore everything that isn't required to run an
     application.  */
  private def slugIgnoreTask: Initialize[Task[File]] =
    (state, slugIgnored in hero, streams) map {
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
      target, scriptName in hero, javaOptions in hero) map {
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
