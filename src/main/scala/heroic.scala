package heroic

import sbt._
import Keys._
import Project.Initialize

object Procfile {
  def apply(tasks: Seq[(String, String)]) =
   (tasks.map {
     case (task, cmd) => "%s : %s" format(task, cmd)
   }).mkString("\n")
}

object Script {
  def apply(main: String, cp: Seq[String], jvmOpts: Option[String] = None) =
  """#!/bin/sh
  |
  |function log() {
  |  COLOR="\033[0;35m"
  |  CLEAR="\033[0m"
  |  echo "$COLOR $1 $CLEAR"
  |}
  |
  |log "Building application"
  |
  |mvn scala:compile
  |
  |JAVA=`which java`
  |
  |CLASSPATH=%s
  |
  |log "Booting application"
  |
  |exec $JAVA %s -classpath "$CLASSPATH" %s "$@"
  |""".stripMargin
      .format(
        cp.map(""""$REPO"/%s""" format _).mkString(":"),
        jvmOpts.getOrElse(""),
        main
      )
}

/*object Foreman {
  lazy val bin = try {
    Right(Process("which foreman"))
  } catch {
     case e => Left(e)
  }
  def onError(t: Throwable) = throw t
  def start = bin.fold(onError, path
    Process("%s start" format path)
  }
}*/

object Plugin extends sbt.Plugin {
  val Hero = config("hero") extend(Runtime)

  val prepare = TaskKey[Unit]("prepare", "Prepares project for heroku deployment")
  val procfile = TaskKey[File]("profile", "Writes heroku procfile a projects base directory")
  val main = TaskKey[Option[String]]("main", "Main class to run on Heroku")
  val script = TaskKey[File]("script", "Generates driver script")
  val scriptName = SettingKey[String]("script-name", "Name of the generated driver script")
  val pom = TaskKey[File]("pom", "generates and copies project pom to project base")

  //val foreman = TaskKey[Unit]("foreman", "...")

  val IvyCached = """\S+/[.]ivy2/cache/(\S+.jar)""".r

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

  private def scriptTask: Initialize[Task[File]] =
    (main, streams, scalaVersion, fullClasspath in Runtime, baseDirectory, moduleSettings, scriptName) map {
      (main, out, sv, cp, base, mod, scriptName) => main match {
        case Some(mainCls) =>

          // hope for a moduleSetting that provides a moduleId
          // https://github.com/harrah/xsbt/blob/0.10/ivy/IvyConfigurations.scala#L51-73
          val mid = mod match {
            case InlineConfiguration(id, _, _, _, _, _, _) => id
            case EmptyConfiguration(id, _, _) => id
            case _ => error("This task requires a module id")
          }

          // https://github.com/harrah/xsbt/blob/0.10/ivy/IvyInterface.scala#L12
          val (org, name, version) = (mid.organization, mid.name, mid.revision)

          def mvnize(path: String) = path.replace("/jars", "").replace("[.]","/").replace("[.]","/")

          val cpElems = cp.map(_.data.getPath).flatMap({
               case IvyCached(jar) => Some(mvnize(jar))
               case r => out.log.info("possibly leaving out cp resource %s" format r); None
            }) ++ Seq(
              "org/scala-lang/scala-library/%s/scala-library-%s.jar".format(sv, sv),
              "%s/%s_%s/%s/%s_%s-%s.jar".format(
                org.replaceAll("[.]","/"),
                name, sv, version, name, sv, version
              ),
              "target/scala-%s/classes".format(sv)
            )

          cpElems.foreach(e=>out.log.info("incl %s" format e))
          val scriptBody =
            Script(mainCls, cpElems)

          out.log.info("Writing script/%s" format scriptName)
          val sf = new java.io.File(base, "script/%s" format scriptName)
          IO.write(sf, scriptBody)
          sf

        case _ => error("Main class required")
      }
    }

  private def includingMvnPlugin(srcPath: String)(pom: xml.Node):xml.Node = {
    import scala.xml._
    import scala.xml.transform._

    def adopt(parent: Node, kid: Node) = parent match {
      case Elem(prefix, label, attrs, scope, kids @ _*) =>
        Elem(prefix, label, attrs, scope, kids ++ kid : _*)
      case _ => error("Can only add children to elements!")
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
                  </plugin>
              </plugins>
            </build>
          )
        case node => node
      }
    }

    // todo: my test build was sparse
    // handling of other cases would make this
    // much more robust
    new RuleTransformer(AddBuild)(pom)
  }

  val options: Seq[Setting[_]] = inConfig(Hero)(Seq(
    (pomPostProcess in Global) <<= (pomPostProcess in Global, baseDirectory, sourceDirectory)(
       (pp, base, src) => pp andThen(includingMvnPlugin(Path.relativeTo(base)(src).get))
    ),
    main <<= (mainClass in Runtime).identity,
    scriptName := "hero",
    script <<= scriptTask,
    procfile <<= procfileTask,
    pom <<= pomTask,
    prepare <<= Seq(script, procfile, pom).dependOn
  ))
}
