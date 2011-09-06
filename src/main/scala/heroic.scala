package heroic

import sbt._
import Keys._
import Project.Initialize

object Procfile {
  def apply(tasks: Seq[(String, String)]) =
   (tasks.map {
     case (task, cmd) => "%s: %s" format(task, cmd)
   }).mkString("\n")
}

object Script {
  def apply(main: String, cp: Seq[String], jvmOpts: Seq[String] = Seq("-Xmx256m","-Xss2048k")) =
  """#!/bin/sh
  |
  |CLEAR="\033[0m"
  |
  |log (){
  |  COLOR="\033[0;35m"
  |  echo "$COLOR $1 $CLEAR"
  |}
  |
  |error (){
  |  COLOR="\033[0;31m"
  |  echo "$COLOR $1 $CLEAR"
  |}
  |
  |ensure_repo (){
  |  if [ "$REPO" == "" ]; then
  |    export REPO=$HOME/.m2/repository
  |  fi
  |  if [ ! -d "$REPO" ]; then
  |    error "Unknown repo. Export REPO variable to m2 repository path"
  |    exit 1
  |  fi
  |}
  |
  |ensure_repo
  |
  |log "Building application"
  |mvn scala:compile -q
  |
  |log "Installing application"
  |mvn install -DskipTests=true -q
  |
  |JAVA=`which java`
  |
  |CLASSPATH=%s
  |
  |log "Booting application (%s)"
  |exec $JAVA %s -classpath "$CLASSPATH" %s "$@"
  |""".stripMargin
      .format(
        cp.map(""""$REPO"/%s""" format _).mkString(":"),
        main,
        jvmOpts.mkString(" "),
        main
      )
}

case class Cmd(name: String, help: String) {
  lazy val bin = try {
    val path = Process("which %s" format name).!!
    if(path matches ".*%s\\s+".format(name)) {
       Right(path)
    }
    else Left(new UnsupportedOperationException("%s cmd not installed." format(name)))
  } catch {
     case e => Left(e)
  }
  def onError(t: Throwable) = throw new RuntimeException(
    "Invalid `%s` cmd. %s. %s" format(name,t.getMessage, help), t
  )
  def call[T](cmd: String) = bin.fold(onError, { path =>
    Process("%s %s" format(name, cmd))
  })
}

object Git extends Cmd("git", "download from http://git-scm.com/download") {
  def push(remote: String, branch: String = "master") = call("push %s %s" format(remote, branch))
}

object Heroku extends Cmd("heroku", "try `gem install heroku`") {
  def logs = call("logs")
  def ps = call("ps")
  def create = call("create --stack cedar")
}

object Foreman extends Cmd("foreman", "try `gem install foreman`") {
  def start = call("start")
}

/** Provides Heroku deployment capability. 
 *  assumes exported env variables 
 *  REPO path to m2 maven repository */
object Plugin extends sbt.Plugin {
  val Hero = config("hero") extend(Runtime)

  val prepare = TaskKey[Unit]("prepare", "Prepares project for heroku deployment")
  val procfile = TaskKey[File]("profile", "Writes heroku Procfile to project base directory")
  val main = TaskKey[Option[String]]("main", "Target Main class to run")
  val script = TaskKey[File]("script", "Generates driver script")
  val checkDependencies = TaskKey[Boolean]("check-dependencies", "Checks to see if required dependencies are installed")
  val scriptName = SettingKey[String]("script-name", "Name of the generated driver script")
  val jvmOpts = SettingKey[Seq[String]]("jvm-opts", """Sequence of jvm options, defaults to Seq("-Xmx256m","-Xss2048k")""")
  val pom = TaskKey[File]("pom", "Generates and copies project pom to project base")

  // heroku client api (not yet usable/until I figure out how to tail the process api :/)
  val foreman = TaskKey[Unit]("foreman", "Start herko foreman env")
  val logs = TaskKey[Unit]("logs", "Invokes Heroku client logs command")
  val ps = TaskKey[Unit]("ps", "Invokes Heroku client ps command")
  val create = TaskKey[Unit]("create", "Invokes Heroku client create command")
  val push = TaskKey[Unit]("push", "Invokes Heroku client push command")

  private def foremanTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) =>
        (Foreman.start ! new ProcessLogger {
           def info(s: => String) = out.log.info(s)
           def error(s: => String) = out.log.info(s)
           def buffer[T](f: => T): T = f
        })
    }

 private def logsTask: Initialize[Task[Unit]] =
   (streams) map {
     (out) =>
      (Heroku.logs ! out.log)
   }

  private def psTask: Initialize[Task[Unit]] =
   (streams) map {
     (out) =>
       (Heroku.ps ! out.log)
   }

  private def createTask: Initialize[Task[Unit]] =
   (streams) map {
     (out) => (Heroku.create ! out.log)
   }

  private def pushTask: Initialize[Task[Unit]] =
    (streams) map {
      (out) => (Git.push("heroku") ! out.log)
    }

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

  private def checkDependenciesTask: Initialize[Task[Boolean]] =
    (streams) map {
      (out) =>
        val installed = (Map.empty[String, Boolean] /:Seq("heroku", "foreman"))((a,e) =>
          try {
            a + (e -> Process("which %s" format(e)).!!.matches(".*%s\\s+".format(e)))
          } catch {
            case _ => a + (e -> false)
          }
       )
       true
    }

  private def scriptTask: Initialize[Task[File]] =
    (main, streams, scalaVersion, fullClasspath in Runtime, baseDirectory, moduleSettings, scriptName, update, jvmOpts) map {
      (main, out, sv, cp, base, mod, scriptName, report, jvmOpts) => main match {
        case Some(mainCls) =>

          val onlyJar: ArtifactFilter = artifactFilter(`type` = "jar")
          val onlyCompile: ConfigurationFilter = Set("compile")

          (report select(onlyCompile)).foreach(println)

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

          val cpElems = cp.map(_.data.getPath)/*(report select(onlyCompile)).map(_.getPath)*/.flatMap({
               case IvyCached(jar) => Some(mvnize(jar))
               case r => out.log.info("possibly leaving out cp resource %s." format r); None
            }) ++ Seq(
              "org/scala-lang/scala-library/%s/scala-library-%s.jar".format(sv, sv),
              "%s/%s_%s/%s/%s_%s-%s.jar".format(
                org.replaceAll("[.]","/"),
                name, sv, version, name, sv, version
              )
            )

          cpElems.foreach(e=>out.log.info("incl %s" format e))
          val scriptBody = Script(mainCls, cpElems, jvmOpts)

          out.log.info("Writing script/%s" format scriptName)
          val sf = new java.io.File(base, "script/%s" format scriptName)
          IO.write(sf, scriptBody)
          sf

        case _ => error("Main class required")
      }
    }

  private def includingMvnPlugin(srcPath: String, scalaVersion: String)(pom: xml.Node):xml.Node = {
    import scala.xml._
    import scala.xml.transform._

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

  val options: Seq[Setting[_]] = inConfig(Hero)(Seq(
    (pomPostProcess in Global) <<= (pomPostProcess in Global, baseDirectory, sourceDirectory, scalaVersion)(
       (pp, base, src, sv) => pp andThen(includingMvnPlugin(Path.relativeTo(base)(src).get, sv))
    ),
    jvmOpts := Seq("-Xmx256m","-Xss2048k"),
    main <<= (mainClass in Runtime).identity,
    scriptName := "hero",
    script <<= scriptTask,
    procfile <<= procfileTask,
    pom <<= pomTask,
    prepare <<= Seq(script, procfile, pom).dependOn,
    foreman <<= foremanTask,
    logs <<= logsTask,
    ps <<= psTask,
    create <<= createTask,
    push <<= pushTask,
    checkDependencies <<= checkDependenciesTask
  ))
}
