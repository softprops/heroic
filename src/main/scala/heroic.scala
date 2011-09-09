package heroic

import sbt._
import Project.Initialize

object Keys {
  // pgk settings
  val prepare = TaskKey[Unit]("prepare", "Prepares project for heroku deployment")
  val procfile = TaskKey[File]("profile", "Writes heroku Procfile to project base directory")
  val main = TaskKey[Option[String]]("main", "Target Main class to run")
  val script = TaskKey[File]("script", "Generates driver script")
  val checkDependencies = TaskKey[Boolean]("check-dependencies", "Checks to see if required dependencies are installed")
  val scriptName = SettingKey[String]("script-name", "Name of the generated driver script")
  val jvmOpts = SettingKey[Seq[String]]("jvm-opts", """Sequence of jvm options, defaults to Seq("-Xmx256m","-Xss2048k")""")
  val pom = TaskKey[File]("pom", "Generates and copies project pom to project base")
  val slugIgnore = TaskKey[File]("slug-ignore", "Generates a Heroku .slugignore file in the base directory")

  // client settings
  val foreman = TaskKey[Int]("foreman", "Start herko foreman env")
  val logs = InputKey[Int]("logs", "Invokes Heroku client logs command")
  val ps = TaskKey[Int]("ps", "Invokes Heroku client ps command")
  val create = TaskKey[Int]("create", "Invokes Heroku client create command")
  val push = TaskKey[Int]("push", "Pushes project to heroku")
  val info = TaskKey[Int]("info", "Displays Heroku deployment info")
  val addons = TaskKey[Int]("addons", "Lists installed Heroku addons")
  val addonsAdd = InputKey[Int]("addons-add", "Install a Heroku addon by name")
  val addonsRm = InputKey[Int]("addons-rm", "Uninstall a Heroku addmon by name")
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
}

/** Provides Heroku deployment capability.
 *  assumes exported env variables
 *  REPO path to m2 maven repository */
object Plugin extends sbt.Plugin {
  import sbt.Keys._
  import heroic.Keys._

  val Hero = config("hero") extend(Runtime)

  def pkgSettings: Seq[Setting[_]] = inConfig(Hero)(Seq(
    (pomPostProcess in Global) <<= (pomPostProcess in Global, baseDirectory,
                                    sourceDirectory, scalaVersion)(
       (pp, base, src, sv) => pp.andThen(
         includingMvnPlugin(Path.relativeTo(base)(src).get, sv)
       )
    ),
    jvmOpts := Seq("-Xmx256m","-Xss2048k"),
    main <<= (mainClass in Runtime).identity,
    scriptName := "hero",
    script <<= scriptTask,
    procfile <<= procfileTask,
    pom <<= pomTask,
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
            // tailing does seem to work in sbt, might take this out
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
            Heroku.config.add(key, value) ! out.log
        }
      }
    },
    confRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(key) =>
            Heroku.config.rm(key) ! out.log
        }
      }
    },
    addons <<= addonsTask,
    addonsAdd <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) => Heroku.addons.add(feature) ! out.log
        }
      }
    },
    addonsRm <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(feature) => Heroku.addons.rm(feature) ! out.log
        }
      }
    },
    maintenanceOn <<= maintenanceOnTask,
    maintenanceOff <<= maintenanceOffTask,
    releases <<= releasesTask,
    releaseInfo <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(rel) =>
            Heroku.releases.info(rel) ! out.log
        }
      }
    },
    rollback <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            Heroku.releases.rollback(to) ! out.log
        }
      }
    },
    open <<= openTask,
    rename <<= inputTask { (argsTask: TaskKey[Seq[String]]) =>
      (argsTask, streams) map { (args, out) =>
        args match {
          case Seq(to) =>
            Heroku.apps.rename(to) ! out.log
        }
      }
    }
  ))

  private def exec(pb: ProcessBuilder): Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        pb ! out.log
    }

  private def openTask: Initialize[Task[Int]] =
    exec(Heroku.apps.open)

  private def releasesTask: Initialize[Task[Int]] =
    exec(Heroku.releases.show)

  private def maintenanceOnTask: Initialize[Task[Int]] =
    exec(Heroku.maintenance.on)

  private def maintenanceOffTask: Initialize[Task[Int]] =
    exec(Heroku.maintenance.off)

  private def foremanTask: Initialize[Task[Int]] =
    (streams) map {
      (out) =>
        val chk = Foreman.check ! out.log
        if(chk == 0) Foreman.start ! out.log
        else chk
    }

  private def psTask: Initialize[Task[Int]] =
    exec(Heroku.ps.show)

  private def infoTask: Initialize[Task[Int]] =
    exec(Heroku.info)

  private def addonsTask: Initialize[Task[Int]] =
    exec(Heroku.addons.ls)

  private def confTask: Initialize[Task[Int]] =
    exec(Heroku.config.show)

  // note you can pass --remote name to overrivde
  // heroku's default remote name for multiple envs
  // stanging, production, ect
  private def createTask: Initialize[Task[Int]] =
   exec(Heroku.create)

  private def pushTask: Initialize[Task[Int]] =
    exec(Git.push("heroku"))

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
   the docs say to ignore everything that isn't required to run an
   application, if we are depending on poms this may mean src
   ignore tests by default */
  private def slugIgnoreTask: Initialize[Task[File]] =
    (baseDirectory, streams) map {
      (base, out) =>
        val f = new java.io.File(base, ".slugignore")
        if (!f.exists) {
          f.createNewFile
          IO.write(f, "src/test")
        }
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
     moduleSettings, scriptName, jvmOpts) map {
      (main, out, sv, cp, base, mod, scriptName, jvmOpts) => main match {
        case Some(mainCls) =>

          val IvyCachedParts = """(.*[.]ivy2/cache)/(\S+)/(\S+)/(\S+)/(\S+)[.]jar""".r
          val IvyCached = """(.*[.]ivy2/cache/\S+/\S+/\S+/\S+[.]jar)""".r

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

          // Hope for a moduleSetting that provides a moduleId
          // https://github.com/harrah/xsbt/blob/0.10/ivy/IvyConfigurations.scala#L51-73
          val mid = mod match {
            case InlineConfiguration(id, _, _, _, _, _, _) => id
            case EmptyConfiguration(id, _, _) => id
            case _ => error("This task requires a module id")
          }

          // https://github.com/harrah/xsbt/blob/0.10/ivy/IvyInterface.scala#L12
          val (org, name, version) = (mid.organization, mid.name, mid.revision)

          val cpElems = cp.map(_.data.getPath).flatMap({
               case IvyCached(jar) => Some(mvnize(jar))
               case r => out.log.info("possibly leaving out cp resource %s." format r); None
            }) ++ Seq(
              "org/scala-lang/scala-library/%s/scala-library-%s.jar".format(sv, sv),
              "%s/%s_%s/%s/%s_%s-%s.jar".format(
                org.replaceAll("[.]","/"),
                name, sv, version, name, sv, version
              )
            )

          cpElems.foreach(e => out.log.debug("incl %s" format e))
          val scriptBody = Script(mainCls, cpElems, jvmOpts)

          out.log.info("Writing script/%s" format scriptName)
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
    new RuleTransformer(AddBuild,EnsureEncoding)(pom)
  }

  val options: Seq[Setting[_]] = pkgSettings ++ clientSettings
}
