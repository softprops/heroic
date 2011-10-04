package heroic

import sbt._
import Project.Initialize

case class Release(env: Map[String, String], pstable: Map[String, String],
                   commit: Option[String], descr: String, addons: Seq[String],
                   created_at: String, user: String, name: String)

/** Provides Heroku deployment capability.
 *  assumes exported env variables
 *  REPO path to m2 maven repository */
object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import HeroKeys._
  import heroic.{Git => GitCli}
  import com.codahale.jerkson.Json._

  val stage = TaskKey[Unit]("stage", "Heroku installation hook")
  def stageTask(script: File, procfile: File, slugfile: File) = { /* noop */ }

  object HeroKeys {
    // app settings
    val equip = TaskKey[Unit]("equip", "Prepares project for Heroku deployment")

    val procfile = TaskKey[File]("profile", "Writes Heroku Procfile to project base directory")

    val scriptName = SettingKey[String]("script-name", "Name of script-file")
    val scriptFile = SettingKey[File]("script-file", "Target process for for Heroku web procfile key")
    val script = TaskKey[File]("script", "Generates script-file")

    val slugIgnored = SettingKey[Seq[String]]("slug-ignored", "List of items to ignore when transfering application")
    val slugIgnore = TaskKey[File]("slug-ignore", "Generates a Heroku .slugignore file in the base directory")
    
    // client settings

    val checkDependencies = TaskKey[Boolean]("check-dependencies", "Checks to see if required dependencies are installed")

    val localHero = TaskKey[Unit]("local-hero", "Starts a local emulated Heroku env")

    // heroku api

    val auth = TaskKey[Unit]("auth", "Get or acquires heroku credentials")

    val collaborators = TaskKey[Unit]("collaborators", "Lists Heroku application collaborators")
    val collaboratorsAdd = InputKey[Unit]("collaborators-add", "Adds a Heroku application collaborator by email")
    val collaboratorsRm = InputKey[Unit]("collaborators-rm", "Removes a Heroku application collaborator by email")

    val logs = InputKey[Unit]("logs", "Invokes Heroku client logs command")
    val ps = TaskKey[Unit]("ps", "Invokes Heroku client ps command")
    val create = TaskKey[Unit]("create", "Invokes Heroku client create command")
    val destroy = TaskKey[Unit]("destroy", "Deletes remote application")
    val info = TaskKey[Unit]("info", "Displays Heroku deployment info")

    val addons = TaskKey[Unit]("addons", "Lists installed Heroku addons")
    val addonsAvailable = TaskKey[Unit]("addons-available", "Lists available Heroku addons")
    val addonsAdd = InputKey[Unit]("addons-add", "Install a Heroku addon by name")
    val addonsRm = InputKey[Unit]("addons-rm", "Uninstall a Heroku addon by name")
    // punt for now
    //val addonsUpgrade = InputKey[Int]("addons-upgrade", "Upgrade an installed Heroku addon")

    val conf = TaskKey[Unit]("conf", "Lists available remote Heroku config properties")
    val confAdd = InputKey[Unit]("conf-add", "Adds a Heroku config property")
    val confRm = InputKey[Unit]("conf-rm", "Removes a Heroku config property")

    val maintenanceOff = TaskKey[Unit]("maint-off", "Turns on Heroku Maintenance mode")
    val maintenanceOn = TaskKey[Unit]("maint-on", "Turns off Heroku Maintenance mode")

    val releases = TaskKey[Unit]("releases", "Lists all releases")
    val releaseInfo = InputKey[Unit]("release-info", "Shows info about a target release")
    val rollback = InputKey[Unit]("rollback", "Rolls back to a target release")

    val rename = InputKey[Unit]("rename", "Give your app a custom subdomain on heroku")
    val domains = TaskKey[Unit]("domains", "List Heroku domains")
    val domainsAdd = InputKey[Int]("domains-add", "Add a Heroku domain")
    val domainsRm = InputKey[Int]("domains-rm", "Removes a Heroku domain")

    val keys = TaskKey[Unit]("keys", "Lists Heroku registered keys")
    val keysAdd = InputKey[Unit]("keys-add", "Adds a registed key with heroku")
    val keysRm = InputKey[Unit]("keys-rm", "Removes a registed key with heroku")

    // git settings
    val push = TaskKey[Int]("push", "Pushes project to Heroku")
    val diff = TaskKey[Int]("git-diff", "Displays a diff of untracked sources")
    val status = TaskKey[Int]("git-status", "Display the status of your git staging area")
    val commit = InputKey[Int]("git-commit", "Commits a staging area with an optional msg")
    val add = InputKey[Int]("git-add", "Adds an optional list of paths to the git index, defaults to '.'")
    val git = InputKey[Int]("exec", "Executes arbitrary git command")

  }

  val Hero = config("hero")
  val Git = config("git")

  private def client[T](f: HerokuClient => T): T =
    Auth.credentials match {
      case Some((user, key)) => f(HerokuClient(user,key))
      case _ => sys.error("Not authenticated. Try hero:auth")
    }

  def gitSettings: Seq[Setting[_]] = inConfig(Git)(Seq(
    diff <<= diffTask,
    status <<= statusTask,
    commit <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case msg =>
            out.log.info("commiting with msg '%s'" format msg.mkString(" "))
            GitCli.commit(msg.mkString(" ")) ! out.log
        }
      }
    },
    add <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq() =>
            out.log.info("add everything")
            GitCli.add() ! out.log
          case paths =>
            out.log.info("adding paths %s" format paths.mkString(" "))
            GitCli.add(paths) ! out.log
        }
      }
    },
    git <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        GitCli(args) ! out.log
      }
    }
  ))

  def appSettings: Seq[Setting[_]] = inConfig(Hero)(Seq(
    (javaOptions in Hero) <<= (javaOptions in run)(_ match {
      case Nil => Seq("-Xmx256m","-Xss2048k")
      case provided => provided
    }),
    mainClass in Hero <<= (mainClass in Runtime).identity,
    scriptName := "hero",
    script <<= scriptTask,

    procfile <<= procfileTask,
    
    slugIgnored := Seq("src/test"),
    slugIgnore <<= slugIgnoreTask,
    
    stage in Compile <<= (script, procfile, slugIgnore) map stageTask,

    equip <<= (stage in Compile).identity
  ))


  def authTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) => Auth.acquireCredentials(out.log)
    }

  def clientSettings: Seq[Setting[_]] = inConfig(Hero)(Seq(
    checkDependencies <<= checkDependenciesTask,
    collaborators <<= collaboratorsTask,
    collaboratorsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(email) =>
            client { cli =>
              out.log.info(
                dispatch.Http(cli.collaborators().add(email) as_str)
              )
           }
          case _ => sys.error("usage hero:collaborators-add <email>")
        }
      }
    },
    collaboratorsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(email) =>
            client { cli =>
              out.log.info(
                dispatch.Http(cli.collaborators().rm(email) as_str)
              )
           }
          case _ => sys.error("usage hero:collaborators-rm <email>")
        }
      }
    },
    auth <<= authTask,
    localHero <<= localHeroTask,
    logs <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
         client { cli =>
           out.log.info("Fetching recent remote logs")
           val http = new dispatch.Http with dispatch.HttpsLeniency
           http(cli.logs() >~ { src =>
              src.getLines().foreach(l => out.log.info(l))
            })
            0
         }
      }
    },
    ps <<= psTask,
    create <<= createTask,
    destroy <<= destroyTask,
    push <<= pushTask,
    info <<= infoTask,
    conf <<= confTask,
    confAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(key, value) =>
            client { cli =>
              out.log.info("assigning config var %s to %s" format(key, value))
              val updated = parse[Map[String, String]](
                dispatch.Http(cli.config().add(key, value) as_str)
              )
              out.log.info("Updated config")
              printMap(updated, out.log)
              0
            }
          case _ => sys.error("usage: hero:conf-add <key> <val>")
        }
      }
    },
    confRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(key) =>
            client { cli =>
              out.log.info("removing config var %s" format key)
              val updated = parse[Map[String, String]](
                dispatch.Http(cli.config().rm(key) as_str)
              )
              out.log.info("Updated config")
              printMap(updated, out.log)
              0
            }
          case _ => sys.error("usage: hero:conf-rm <key>");
        }
      }
    },
    addons <<= addonsTask,
    addonsAvailable <<= addonsAvailableTask,
    addonsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) =>
            client { cli =>
              out.log.info("Requesting addon")
              try {
                val ao = parse[Map[String, String]](
                  dispatch.Http(cli.addons().add(feature) as_str)
                )
                if(ao("status").equals("Installed")) {
                  out.log.info("addon %s installed" format feature)
                  out.log.info("price: %s" format ao("price"))
                  if(!ao("message").equals("null")) "message %s" format(
                    ao("message")
                  )
                } else {
                  sys.error("Addon was not added. response %s." format(ao))
                }
              } catch {
                case dispatch.StatusCode(422, msg) => // error
                  val resp = parse[Map[String, String]](msg)
                  sys.error(
                    "Addon was not added. %s" format resp("error")
                  )
                case dispatch.StatusCode(402, msg) => // billing?
                  val resp = parse[Map[String, String]](msg)
                  out.log.warn(resp("error"))
                  if(confirmBilling(out.log, cli)) out.log.info(
                    "You request to confirm billing was accepted."
                  ) else out.log.info("Addon was not installed")
              }
            }
          case _ => sys.error("usage hero:addons-add <feature>")
        }
      }
    },
    addonsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) =>
            client { cli =>
              out.log.info("Requesting addon removal")
              try {
                dispatch.Http(cli.addons().rm(feature) >|)
                out.log.info("Removed addon %s" format feature)
              } catch {
                case dispatch.StatusCode(422, msg) =>
                  val resp = parse[Map[String, String]](msg)
                  sys.error(
                    "Error removing addon %s" format resp("error")
                  )
              }
            }
          case _ =>  sys.error("usage hero:addons-rm <feature>")
        }
      }
    },
    /* addonsUpgrade <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) =>
            out.log.info("Requesting addon upgrade")
            Heroku.addons.upgrade(feature) ! out.log
        }
      }
    }, */
    
    maintenanceOn <<= maintenanceOnTask,
    maintenanceOff <<= maintenanceOffTask,
    releases <<= releasesTask,
    releaseInfo <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(rel) =>
            out.log.info("Fetching release listing")
            client { cli =>
              out.log.info(
                dispatch.Http(cli.releases().show(rel) as_str)
              )
              0
            }
        }
      }
    },
    rollback <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            client { cli =>
              out.log.info("Rolling back release")
              out.log.info(
                dispatch.Http(cli.rollback(to) as_str)
              )
             0
            }
          case _ => sys.error("usage: hero:rollback <release>")
        }
      }
    },
    // fixme
    rename <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            client { cli =>
              out.log.info("Requesting subdomain")
              try {
                out.log.info(
                  dispatch.Http(cli.rename(to) as_str)
                )
                // todo. need to rename remote if successfull
              } catch {
                case dispatch.StatusCode(406, msg) =>
                  out.log.warn("Fail to rename app. %s" format msg)
              }
            }
          case _ => sys.error("usage: hero:rename <new-subdomain>")
        }
      }
    },
    domains <<= domainsTask,
    domainsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(dom) =>
            out.log.info("Adding domain %s" format dom)
            Heroku.domains.add(dom) ! out.log
        }
      }
    },
    domainsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(dom) =>
            out.log.info("Removing domain %s" format dom)
            Heroku.domains.rm(dom) ! out.log
        }
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
                  out.log.info(dispatch.Http(cli.keys.add(IO.read(f)) as_str))
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
            case Seq(kf) =>
              file(kf) match {
                case f if(f.exists) =>
                  out.log.info(dispatch.Http(cli.keys.rm(IO.read(f)) as_str))
                case f => sys.error("%s does not exist" format f)
              }
            case _ => sys.error("usage: hero:keys-rm <path-to-key>")
          }
        }
      }
    }
  ))

  private def confirmBilling(log: Logger, client: HerokuClient) = {
    log.warn(
      "This action will cause your account to be billed at the end of the month"
    )
    log.warn(
      "For more information, see http://devcenter.heroku.com/articles/billing"
    )
    val confirm = ask("Are you sure you want to do this? (y/n) ") {
      _.trim.toLowerCase
    }
    if(Seq("y", "yes", "yep", "yea") contains confirm) {
      log.info(dispatch.Http(client.confirmBilling as_str))
      true
    } else if (Seq("n", "no", "nope", "nah") contains confirm) false
    else sys.error("unexpected answer %s" format confirm)
  }

  private def statusTask: Initialize[Task[Int]] =
    exec(GitCli.status())

  private def diffTask: Initialize[Task[Int]] =
    exec(GitCli.diff())

  private def exec(pb: => ProcessBuilder, msg: String = "", onSuccess: String = ""): Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        if(!msg.isEmpty) out.log.info(msg)
        val stat = pb ! out.log
        if(stat == 0 && !onSuccess.isEmpty) out.log.info(onSuccess)
        stat
    }

  private def keysTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          dispatch.Http(cli.keys.show <> { xml =>
            (xml \\ "keys" \\ "key").map(_ \ "contents" text).foreach(
              out.log.info(_)
            )
          })
        }
    }

  private def collaboratorsTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info(dispatch.Http(cli.collaborators().show as_str))
        }
    }

  private def domainsTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info(dispatch.Http(cli.domains().show as_str))
        }
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

  private def releasesTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          val rs = parse[Seq[Release]](
            dispatch.Http(cli.releases().list as_str)
          )
          rs.foreach(printRelease(_, out.log))
        }
    }

  private def maintenanceOnTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info(
            dispatch.Http(cli.maintenance(true) as_str)
          )
          out.log.info("Maintenance mode enabled.")
        }
    }

  private def maintenanceOffTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info(
            dispatch.Http(cli.maintenance(false) as_str)
          )
          out.log.info("Maintenance mode disabled.")
        }
    }

  private def localHeroTask: Initialize[Task[Unit]] =
    (baseDirectory, streams) map {
      (bd, out) =>
        out.log.info("Running Procfile process(es). Press any key to stop.")
        val p = Procman.start(new File(bd, "ProcFile"), out.log)
        def detectInput() {
          try { Thread.sleep(1000) } catch { case _: InterruptedException => }
          if(System.in.available() <= 0) detectInput()
        }
        detectInput()
        p.foreach(_.destroy())
        out.log.info("Process complete")
    }

  private def psTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info("Fetching process info")
          val px = parse[Seq[Map[String, String]]](
            dispatch.Http(cli.ps() as_str)
          )
          px.foreach { p =>
            out.log.info(
              "%s %s %s" format(
                p("process"), p("pretty_state"), p("command")
              )
            )
          }
        }
    }

  private def infoTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info("Fetching App info")
          dispatch.Http(cli.info() <> { xml =>
            def attr(name: String) = (xml \\ "app" \ name).text
            out.log.info("=== %s" format attr("name"))
            out.log.info("owner: %s" format attr("owner"))
            out.log.info("web url: %s" format attr("web_url"))
            out.log.info("git url: %s" format attr("git_url"))
          })
          0
        }
    }

  // todo: make this an input task with a query filter (its a long list!)
  private def addonsTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          printAddons(parse[List[Map[String, String]]](
            dispatch.Http(cli.addons().show as_str)
          ), out.log)
          0
        }
    }

  private def addonsAvailableTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          printAddons(parse[List[Map[String, String]]](
            dispatch.Http(cli.addons().available as_str)
          ), out.log)
          0
        }
    }

  private def printMap(m: Map[String, String], log: Logger) = {
    val disp =
      "%-" +
      (m.keys.toSeq.sortWith(_.size > _.size).head.size) +
      "s -> %s"
    m.foreach { case (k, v) =>
      log.info(disp format(k, v))
    }
  }

  private def printAddons(aos: List[Map[String, String]], log: Logger) = {
    val disp = "%-"+aos.map(_("name").size).sortWith(_ > _).head +"s %s %s"
    (aos map { ao =>
      disp format(
        ao("name"), ao("description"),
        if(ao("url") == "null") "" else ao("url")
      )
    }).sortWith(_.compareTo(_) < 0).foreach(log.info(_))
  }

  private def confTask: Initialize[Task[Unit]] = (streams) map {
    (out) =>
      client { cli =>
        import com.codahale.jerkson.Json._
        out.log.info("Fetching remote configuration")
        try {
          printMap(parse[Map[String, String]](
            dispatch.Http(cli.config().show as_str)
          ), out.log)
        } catch {
          case _ => out.log.info("Empty config")
        }
      }
  }

  // note you can pass --remote name to overrivde
  // heroku's default remote name for multiple envs
  // stanging, production, ect
  // 
  private def createTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        client { cli =>
          out.log.info("Creating remote Heroku application")
          val app = parse[Map[String, String]](
            dispatch.Http(cli.create() as_str)
          )
          val (webUrl, gitUrl) = dispatch.Http(cli.info(app("name")) <> { xml =>
            def attr(name: String) = (xml \\ "app" \ name).text
            (attr("web_url"), attr("git_url"))
          })

          // fixme: only rec 406?
          def checkStatus: Unit = {
            dispatch.Http.x(cli.appStatus(app("name"))) {
              case (201, _, _) =>
                out.log.info(
                  "Created remote Heroku application %s" format app("name")
                )
                out.log.info(
                  "Stack: %s\nCreate Status: %s\nDynos: %s\nWorkers: %s" format(
                    app("stack"),
                    app("create_status"),
                    app("dynos"),
                    app("workers")))
             case (code, h, _) =>
               println("code %s headers %s" format(code, h))
               Thread.sleep(1000)
               checkStatus
            }
          }

          //checkStatus
          out.log.info("Created app %s" format app("name"))
          out.log.info("%s | %s" format(webUrl, gitUrl))
          val stat = GitClient.addRemote(app("name"))
          if(stat > 0) out.log.error("Error adding git remote heroku")
          else out.log.info("Added git remote heroku")
        }
    }

  // fixme: refactor
  private def ask[T](question: String)(f: String => T): T = {
    print(question)
    f(readLine)
  }

  private def destroyTask: Initialize[Task[Unit]] = 
    (streams) map {
      (out) =>
        val confirm = ask(
          "Are you sure you want to destory this application: [Y/N] ") {
          _.trim.toLowerCase
        }
        if(Seq("n", "no", "nope", "nah") contains confirm) out.log.info(
          "Cancelled request"
        ) else if(Seq("y", "yes", "yep", "yea") contains confirm) client { cli =>
          out.log.info("Destroying remote application")
          try {
            dispatch.Http(cli.destroy() as_str)
            out.log.info("Remote application destroyed")
            val stat = GitClient.remoteRm("heroku")
            if(stat > 0) out.log.error("Error removing git remote heroku")
            else out.log.info("Removed git remote heroku")
          } catch {
            case dispatch.StatusCode(404, _) =>
              out.log.warn("Remote app did not exist")
          }
        } else sys.error("unexpected answer %s" format confirm)
    }

  // todo: check for local changes...
  private def pushTask: Initialize[Task[Int]] =
    exec(GitCli.push("heroku"),
      "Updating application (this may take a few seconds)",
       "Check the status of your application with `hero:ps` or `hero:logs`"
    )

  private def procfileTask: Initialize[Task[File]] =
    (baseDirectory, scriptName, streams) map {
      (base, scrpt, out) =>    
        new File(base, "Procfile") match {
          case pf if(pf.exists) => pf
          case pf =>
            out.log.info("Writing Procfile")
            IO.write(pf, Procfile(Seq(("web", "sh target/%s" format scrpt))))
            pf
        }
    }

  /* http://devcenter.heroku.com/articles/slug-compiler
     http://devcenter.heroku.com/articles/slug-size
   the docs say to ignore everything that isn't required to run an
   application.  */
  private def slugIgnoreTask: Initialize[Task[File]] =
    (baseDirectory, slugIgnored, streams) map {
      (base, ignores, out) =>
        new java.io.File(base, ".slugignore") match {
          case si if(si.exists) => si
          case si =>
            out.log.info("Writing .slugignore")
            IO.write(si, ignores.mkString("\n"))
            si
        }
    }

  private def checkDependenciesTask: Initialize[Task[Boolean]] =
    (streams) map {
      (out) =>
        val install = (Map.empty[String, Boolean] /: Seq("heroku", "mvn", "git"))(
          (a,e) =>
            try {
              a + (e -> Process("which %s" format(e)).!!.matches(".*%s\\s+".format(e)))
            } catch {
              case _ => a + (e -> false)
            }
        )
       install.foreach(_ match {
         case (cmd, inst) =>
           if(inst) out.log.info("\033[0;32minstalled\033[0m %s" format cmd)
           else out.log.warn("\033[0;31mmissing  \033[0m %s" format cmd)
       })
       install.filter(!_._2).isEmpty
    }

  private def scriptTask: Initialize[Task[File]] =
    (mainClass in Hero, streams, fullClasspath in Runtime, baseDirectory,
      target, scriptName, javaOptions in Hero) map {
      (main, out, cp, bd, target, sn, jvmOpts) => main match {
        case Some(mainCls) =>

          val scriptBody = Script(mainCls, cp.files map { f =>
            IO.relativize(bd, f) match {
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

  def heroicSettings: Seq[Setting[_]] = appSettings ++ clientSettings ++ gitSettings
}
