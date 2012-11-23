package heroic

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

  def addRemote(app: String,
                remote: String = "heroku",
                host: String = "heroku.com") =
    if(remotes.isDefinedAt(remote)) 0 // already have one
    else sbt.Process(
      "git remote add %s git@%s:%s.git"
        .format(remote, host, app)) !
}
