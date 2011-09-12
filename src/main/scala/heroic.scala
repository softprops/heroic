package heroic

import sbt._
import Project.Initialize

object Keys {
  // pgk settings
  val prepare = TaskKey[Unit]("prepare", "Prepares project for Heroku deployment")
  val procfile = TaskKey[File]("profile", "Writes Heroku Procfile to project base directory")
  val main = TaskKey[Option[String]]("main", "Target Main class to run")
  val script = TaskKey[File]("script", "Generates driver script")
  val checkDependencies = TaskKey[Boolean]("check-dependencies", "Checks to see if required dependencies are installed")
  val scriptName = SettingKey[String]("script-name", "Name of the generated driver script")
  val javaOptions = SettingKey[Seq[String]]("java-options", """Sequence of jvm options, defaults to Seq("-Xmx256m","-Xss2048k")""")
  val pom = TaskKey[File]("pom", "Generates and copies project pom to project base")
  val slugIgnored = SettingKey[Seq[String]]("slug-ignored", "List of items to ignore when transfering application")
  val slugIgnore = TaskKey[File]("slug-ignore", "Generates a Heroku .slugignore file in the base directory")

  // client settings

  // foreman gem
  val foreman = TaskKey[Int]("foreman", "Start Heroku foreman env")

  // heroku gem
  val logs = InputKey[Int]("logs", "Invokes Heroku client logs command")
  val ps = TaskKey[Int]("ps", "Invokes Heroku client ps command")
  val create = TaskKey[Int]("create", "Invokes Heroku client create command")
  val info = TaskKey[Int]("info", "Displays Heroku deployment info")
  val addons = TaskKey[Int]("addons", "Lists installed Heroku addons")
  val addonsAdd = InputKey[Int]("addons-add", "Install a Heroku addon by name")
  val addonsRm = InputKey[Int]("addons-rm", "Uninstall a Heroku addon by name")
  // upgrade requires user stdin, not sure how to handle this yet
  //val addonsUpgrade = InputKey[Int]("addons-upgrade", "Upgrade an installed Heroku addon")
  val conf = TaskKey[Int]("conf", "Lists available remote Heroku config properties")
  val confAdd = InputKey[Int]("conf-add", "Adds a Heroku config property")
  val confRm = InputKey[Int]("conf-rm", "Removes a Heroku config property")
  val maintenanceOff = TaskKey[Int]("maint-off", "Turns on Heroku Maintenance mode")
  val maintenanceOn = TaskKey[Int]("maint-on", "Turns off Heroku Maintenance mode")
  val releases = TaskKey[Int]("releases", "Lists all releases")
  val releaseInfo = InputKey[Int]("release-info", "Shows info about a target release")
  val rollback = InputKey[Int]("rollback", "Rolls back to a target release")
  val open = TaskKey[Int]("open", "Opens App in a browser")
  val rename = InputKey[Int]("rename", "Give your app a custom subdomain on heroku")
  val domains = TaskKey[Int]("domains", "List Heroku domains")
  val domainsAdd = InputKey[Int]("domains-add", "Add a Heroku domain")
  val domainsRm = InputKey[Int]("domains-rm", "Removes a Heroku domain")

  // git cmd masquerading around as a heroku cmd
  val push = TaskKey[Int]("push", "Pushes project to Heroku")

  // git settings
  val diff = TaskKey[Int]("git-diff", "Displays a diff of untracked sources")
  val status = TaskKey[Int]("git-status", "Display the status of your git staging area")
  val commit = InputKey[Int]("git-commit", "Commits a staging area with an optional msg")
  val add = InputKey[Int]("git-add", "Adds an optional list of paths to the git index, defaults to '.'")
  val git = InputKey[Int]("exec", "Executes arbitrary git command")
}

/** Provides Heroku deployment capability.
 *  assumes exported env variables
 *  REPO path to m2 maven repository */
object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import heroic.Keys._
  import heroic.{Git => GitCli}

  val Hero = config("hero")
  val Git = config("git")

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

  def pkgSettings: Seq[Setting[_]] = inConfig(Hero)(Seq(
    (pomPostProcess in Global) <<= (pomPostProcess in Global, baseDirectory,
                                    sourceDirectory, scalaVersion)(
       (pp, base, src, sv) => pp.andThen(
         includingMvnPlugin(Path.relativeTo(base)(src).get, sv)
       )
    ),
    // should maybe default to (javaOptions in Compile) here?
    (sbt.Keys.javaOptions in Hero) <<= (heroic.Keys.javaOptions in run)(_ match {
      case Nil => Seq("-Xmx256m","-Xss2048k")
      case provided => provided
    }),
    main <<= (mainClass in Runtime).identity,
    scriptName := "hero",
    script <<= scriptTask,
    procfile <<= procfileTask,
    pom <<= pomTask,
    slugIgnored := Seq("project", "src/test", "target"),
    slugIgnore <<= slugIgnoreTask,
    prepare <<= Seq(script, procfile, pom, slugIgnore).dependOn,
    checkDependencies <<= checkDependenciesTask
  ))

  def clientSettings: Seq[Setting[_]] = inConfig(Hero)(Seq(
    foreman <<= foremanTask,
    logs <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        val p =
          args match {
            case Seq() => Heroku.logs.show
            // tailing doesn't seem to work in sbt, might take this out
            case Seq("-t") => Heroku.logs.tail
          }
        p ! out.log
      }
    },
    ps <<= psTask,
    create <<= createTask,
    push <<= pushTask,
    info <<= infoTask,
    conf <<= confTask,
    confAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(key, value) =>
            out.log.info("assigning config var %s" format key)
            Heroku.config.add(key, value) ! out.log
        }
      }
    },
    confRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(key) =>
            out.log.info("removing config var %s" format key)
            Heroku.config.rm(key) ! out.log
        }
      }
    },
    addons <<= addonsTask,
    addonsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) =>
            out.log.info("requesting addon")
            Heroku.addons.add(feature) ! out.log
        }
      }
    },
    addonsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) =>
            out.log.info("Requesting addon removal")
            Heroku.addons.rm(feature) ! out.log
        }
      }
    },
    /*addonsUpgrade <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
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
            Heroku.releases.info(rel) ! out.log
        }
      }
    },
    rollback <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            out.log.info("Rolling back release")
            Heroku.releases.rollback(to) ! out.log
        }
      }
    },
    open <<= openTask,
    rename <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            out.log.info("Requesting subdomain")
            Heroku.apps.rename(to) ! out.log
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
    }
  ))

  private def statusTask: Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        // todo: parse output, and render nicely
        GitCli.status() ! out.log
    }

  private def diffTask: Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        GitCli.diff() ! out.log
    }

  private def exec(pb: => ProcessBuilder, msg: String = "", onSuccess: String = ""): Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        if(!msg.isEmpty) out.log.info(msg)
        val stat = pb ! out.log
        if(stat == 0 && !onSuccess.isEmpty) out.log.info(onSuccess)
        stat
    }

  private def domainsTask: Initialize[Task[Int]] =
    exec(Heroku.domains.show, "Fetching domains")

  private def openTask: Initialize[Task[Int]] =
    exec(Heroku.apps.open, "Launching application")

  private def releasesTask: Initialize[Task[Int]] =
    exec(Heroku.releases.show, "Fetching release listing")

  private def maintenanceOnTask: Initialize[Task[Int]] =
    exec(Heroku.maintenance.on, "Enabling maintenance mode")

  private def maintenanceOffTask: Initialize[Task[Int]] =
    exec(Heroku.maintenance.off, "Disabling maintenance mode")

  private def foremanTask: Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        val chk = Foreman.check ! out.log
        if(chk == 0) {
          out.log.info("Starting foreman")
          Foreman.start ! out.log
        }
        else chk
    }

  private def psTask: Initialize[Task[Int]] =
    exec(Heroku.ps.show, "Fetching process info")

  private def infoTask: Initialize[Task[Int]] =
    exec(Heroku.info, "Fetching application info")

  private def addonsTask: Initialize[Task[Int]] =
    exec(Heroku.addons.show, "Fetching addons")

  private def confTask: Initialize[Task[Int]] =
   exec(Heroku.config.show, "Fetching application configuration")

  // note you can pass --remote name to overrivde
  // heroku's default remote name for multiple envs
  // stanging, production, ect
  private def createTask: Initialize[Task[Int]] =
    exec(Heroku.create, "Creating application")

  private def pushTask: Initialize[Task[Int]] =
    exec(GitCli.push("heroku"), "Updating application (this may take a few seconds)",
         "Check the status of your application with `hero:ps` or `hero:logs`"
    )

  private def procfileTask: Initialize[Task[File]] =
    (baseDirectory, scriptName, streams) map {
      (base, scrpt, out) =>
        out.log.info("Writing Procfile")
        val pf = new java.io.File(base, "Procfile")
        IO.write(pf, Procfile(Seq(("web", "sh script/%s" format scrpt))))
        pf
    }

  private def pomTask: Initialize[Task[File]] =
    (baseDirectory, makePom, pomPostProcess, streams) map {
      (base, pom, postPom, out) =>
        out.log.info("Copying %s to pom.xml" format pom)
        val pf = new java.io.File(base, "pom.xml")
        IO.copyFile(pom, pf)
        pf
    }

  /* http://devcenter.heroku.com/articles/slug-compiler
     http://devcenter.heroku.com/articles/slug-size
   the docs say to ignore everything that isn't required to run an
   application.  */
  private def slugIgnoreTask: Initialize[Task[File]] =
    (baseDirectory, slugIgnored, streams) map {
      (base, ignores, out) =>
        val f = new java.io.File(base, ".slugignore")
        if (!f.exists) {
          f.createNewFile
        }
        IO.write(f, ignores.mkString("\n"))
        f
    }

  private def checkDependenciesTask: Initialize[Task[Boolean]] =
    (streams) map {
      (out) =>
        val install = (Map.empty[String, Boolean] /: Seq("heroku", "foreman", "mvn", "git"))(
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
    (main, streams, scalaVersion, fullClasspath in Runtime, baseDirectory,
     moduleSettings, scriptName, heroic.Keys.javaOptions in Hero, scalaInstance) map {
      (main, out, sv, cp, base, mod, scriptName, jvmOpts, si) => main match {
        case Some(mainCls) =>

          val IvyCachedParts = """(.*[.]ivy2/cache)/(\S+)/(\S+)/(\S+)/(\S+)[.]jar""".r
          val IvyCached = """(.*[.]ivy2/cache/\S+/\S+/\S+/\S+[.]jar)""".r
          val ScalaStdLib = """.*/boot/scala-(\S+)/lib/scala-library.jar""".r
          val TargetClasses = """.*/target/scala-(\S+)/classes""".r
          val MvnScalaStdLib = """org/scala-lang/scala-library/%s/scala-library-%s.jar"""

          def mvnize(ivy: String) = ivy match {
             case IvyCachedParts(_, org, name, pkging, artifact) =>
               "%s/%s/%s/%s.jar" format(
                 org.replaceAll("[.]", java.io.File.separator),
                 name,
                 artifact.split("-").last,
                 artifact
             )
             case notInIvy => error("not supported %s" format notInIvy)
          }

          /* Hope for a moduleSetting that provides a moduleId
            https://github.com/harrah/xsbt/blob/0.10/ivy/IvyConfigurations.scala#L51-73 */
          val mid = mod match {
            case InlineConfiguration(id, _, _, _, _, _, _) => id
            case EmptyConfiguration(id, _, _) => id
            case _ => error("This task requires a module id")
          }

          /* https://github.com/harrah/xsbt/blob/0.10/ivy/IvyInterface.scala#L12 */
          val (org, name, version) = (mid.organization, mid.name, mid.revision)
          val projectJar = "%s/%s_%s/%s/%s_%s-%s.jar".format(
            org.replaceAll("[.]","/"),
            name, sv, version, name, sv, version
          )
          val cpElems = cp.map(_.data.getPath).flatMap({
              case IvyCached(jar) => Some(mvnize(jar))
              case ScalaStdLib(vers) if(vers == sv) => None
              case TargetClasses(scvers) if(scvers == si.actualVersion) => None
              case r =>
                out.log.info("possibly leaving out cp resource %s." format r)
                None
            }) ++ Seq(
              MvnScalaStdLib.format(sv, sv),
              projectJar
            )

          cpElems.foreach(e => out.log.debug("incl %s" format e))
          val scriptBody = Script(mainCls, cpElems, jvmOpts)

          out.log.info("Writing process runner, script/%s" format scriptName)
          val sf = new java.io.File(base, "script/%s" format scriptName)
          IO.write(sf, scriptBody)
          sf

        case _ => error("Main class required")
      }
    }

  private def includingMvnPlugin(srcPath: String, scalaVersion: String)(pom: xml.Node) = {
    import scala.xml._
    import transform._

    def adopt(parent: Node, kid: Node) = parent match {
      case Elem(prefix, label, attrs, scope, kids @ _*) =>
        Elem(prefix, label, attrs, scope, kids ++ kid : _*)
      case _ => error("Only elements can adopt")
    }

    object EnsureEncoding extends RewriteRule {
      override def transform(n: Node) = n match {
        case proj@Elem(_, "project", _, _, _*) if((proj \ "properties") isEmpty) =>
          adopt(
            proj,
            <properties>
              <project.build.sourceEncoding>
                UTF-8
              </project.build.sourceEncoding>
              <project.reporting.outputEncoding>
                UTF-8
              </project.reporting.outputEncoding>
            </properties>
          )
        case node => node
      }
    }

    object AddBuild extends RewriteRule {
      override def transform(n: Node) = n match {
        case proj@Elem(_, "project", _, _, _*) if((proj \ "build") isEmpty) =>
          adopt(
            proj,
            <build>
              <sourceDirectory>{srcPath}</sourceDirectory>
                <plugins>
                  <plugin>
                   <groupId>org.scala-tools</groupId>
                   <artifactId>maven-scala-plugin</artifactId>
                   <version>2.14.1</version>
                   <configuration>
                    <scalaVersion>{scalaVersion}</scalaVersion>
                   </configuration>
                  </plugin>
              </plugins>
            </build>
          )
        case node => node
      }
    }

    // todo: my test build was sparse
    // handling of other cases would make this
    // much more robust :)
    new RuleTransformer(AddBuild, EnsureEncoding)(pom)
  }

  def options: Seq[Setting[_]] = pkgSettings ++ clientSettings ++ gitSettings
}
