package heroic

object Procfile {
  def apply(tasks: Seq[(String, String)]) =
   (tasks.map {
     case (task, cmd) => "%s: %s" format(task, cmd)
   }).mkString("\n")
}

object Script {
  def apply(main: String, cp: Seq[String], jvmOpts: Seq[String]) =
  """#!/bin/sh
  |
  |CLEAR="\033[0m"
  |
  |info (){
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
  |    error "Unknown m2 repo! Export REPO variable to your m2 maven repository path"
  |    exit 1
  |  fi
  |}
  |
  |ensure_repo
  |
  |info "Building application"
  |mvn scala:compile
  |
  |info "Installing application"
  |mvn install -DskipTests=true
  |
  |JAVA=`which java`
  |
  |CLASSPATH=%s
  |
  |info "Booting application (%s)"
  |exec $JAVA %s -classpath "$CLASSPATH" %s "$@"
  |""".stripMargin
      .format(
        cp.map(""""$REPO"/%s""" format _).mkString(":"),
        main,
        jvmOpts.mkString(" "),
        main
      )
}
