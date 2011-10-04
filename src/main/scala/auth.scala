package heroic

object Auth {
  import java.io.{File, PrintWriter}
  import com.codahale.jerkson.Json._

  def credentials: Option[(String, String)] = store match {
    case f if(f.exists) => io.Source.fromFile(f).getLines.toSeq match {
      case Seq(user, key) => Some((user, key))
      case _ => None
    }
    case _ => None
  }

  def acquireCredentials(log: sbt.Logger, tries: Int = 0): Unit = {
    log.info("Authenticate with Heroku")
    val email = ask("Email: ") { _.trim }
    val password = ask("Password: ") { _.trim }
    
    if((email.isEmpty || password.isEmpty) && tries > 2) sys.error("Failed to authenticate")
    else if(email.isEmpty || password.isEmpty) {
      log.warn("Empty email or password")
      acquireCredentials(log, tries + 1)
    } else try {
      (email, HerokuClient.auth(email, password)("api_key")) match {
        case (email, key) =>
          sbt.IO.write(store, "%s\n%s".format(email, key))
          log.info("Wrote credentials to %s" format store.getPath)
          // check key
          val cli = HerokuClient(email, key)
          dispatch.Http(cli.keys.show <> { xml =>
             if((xml \\ "keys" \\ "key").map(_ \ "contents" text).isEmpty) {
               associateOrGenPublicKey(log, cli)
             }
          })
      }
    } catch {
      case dispatch.StatusCode(406, msg) =>
        if(tries > 2) sys.error("Failed to authenticate. %s" format msg)
        else {
          log.warn("Invalid credentials %s" format msg)
          acquireCredentials(log, tries + 1)
        }
      case dispatch.StatusCode(404, msg) =>
        if(tries > 2) sys.error("Failed to authenticate. %s" format msg)
        else {
          log.warn("Invalid credentials %s" format msg)
          acquireCredentials(log, tries + 1)
        }
      case e =>
        e.printStackTrace
        if(tries > 2) sys.error("Failed to authenticate. %s" format e.getMessage)
        else {
          log.warn("Invalid credentials %s" format e.getMessage)
          acquireCredentials(log, tries + 1)
        }
    }
  }

  def associateOrGenPublicKey(log: sbt.Logger, client: HerokuClient) = {
    val home = System.getProperty("user.home")
    val keys = Seq(new File(home, ".ssh/id_rsa.pub"),
                   new File(home, ".ssh/id_dsa.pub"))
    val keyFile = keys.find { _.exists }
    log.info("Heroku requires a public ssh key")

    def notNow = {
      log.warn(
        "to add associate your ssh later use hero:keys-add %s/.ssh/id_rsa.pub" format home)
    }

    if(keyFile.isDefined) {
      val confirm = ask("Would you like to associate yours now? [Y/N] ") {
        _.toLowerCase.trim
      }
      if(Seq("y", "yes", "yep", "yea") contains confirm) {
        log.info("Registering key %s" format keyFile.get)
        dispatch.Http(
          client.keys.add(sbt.IO.read(keyFile.get)) as_str
        )
        log.info("Registered key")
      } else if(Seq("n", "no", "nope", "nah") contains confirm) notNow
      else {
        log.warn("did not understand answer %s" format confirm)
        notNow
      }
    } else {
      // punt on generating one through sbt. ask the user to generate one
      log.warn("could not find a publish ssh key. Consider creating one with")
      log.warn("""ssh-keygen -t rsa -N "" -f "%s/.ssh/id_rsa"""" format home)
      notNow
    }
  }


  def ask[T](question: String)(f: String => T): T = {
    print(question)
    f(readLine)
  }

  def store = new File(System.getProperty("user.home"), ".heroku/credentials")

}
