package heroic

object Auth {
  import java.io.{ File, PrintWriter }
  import dispatch._
  import Prompt._

  def removeCredentials(log: sbt.Logger) = store match {
    case f if(f.exists) =>
      sbt.IO.delete(f)
      log.info("Credentials removed")
    case _ => log.info("No credentials to remove")
  }

  def credentials: Option[String] =
    store match {
      case f if(f.exists) => io.Source.fromFile(f).getLines.toSeq.headOption
      case _ => None
    }

  def acquireCredentials(log: sbt.Logger, tries: Int = 0): Unit = {
    import net.liftweb.json._
    log.info("Authenticate with Heroku")
    val apikey = askDiscretely("Enter your API key (from https://dashboard.heroku.com/account): ") { _.trim }    
    if(apikey.isEmpty && tries > 2) sys.error("Failed to authenticate")
    else if(apikey.isEmpty) {
      log.warn("Empty email or password")
      acquireCredentials(log, tries + 1)
    } else {      
      val cli = new Client(BasicAuth(apikey))
      val req = cli.apps.list(as.lift.Json)
      val err = for {
        JField("error", JString(err)) <- req()
      } yield err
      if (err.isEmpty) {
        sbt.IO.write(store, apikey)
        log.info("Wrote credentials to %s" format store.getPath)
        val kreq = cli.keys.list(as.lift.Json)
        val keys = for {
          JArray(ary)                      <- kreq()
          JObject(fields)                  <- ary
          JField("contents", JString(key)) <- fields
        } yield key
        if (keys.isEmpty) associateOrGenPublicKey(log, cli)
      } else {
        if(tries > 2) sys.error("Failed to authenticate.")
        else {
          log.warn("Invalid credentials")
          acquireCredentials(log, tries + 1)
        }
      }
    }
  }

  def associateOrGenPublicKey(log: sbt.Logger, client: Client) = {
    val home = System.getProperty("user.home")
    val keys = Seq(new File(home, ".ssh/id_rsa.pub"),
                   new File(home, ".ssh/id_dsa.pub"))
    val keyFile = keys.find { _.exists }
    log.info("Heroku requires a public ssh key")

    def notNow = {
      log.warn(
        "to add associate your ssh later use hero:keys-add %s/.ssh/id_rsa.pub" format home)
    }

    if (keyFile.isDefined) {
      val confirm = ask("Would you like to associate yours now? [Y/N] ") {
        _.toLowerCase.trim
      }
      if(Prompt.Okays contains confirm) {
        log.info("Registering key %s" format keyFile.get)
        val http = new Http
        log.info(client.keys.add(sbt.IO.read(keyFile.get))(as.String)())
        log.info("Registered key")
      } else if(Prompt.Nos contains confirm) notNow
      else {
        log.warn("%s does not compute." format confirm)
        notNow
      }
    } else {
      // punt on generating one through sbt. ask the user to generate one
      log.warn("could not find a publish ssh key. Consider creating one with")
      log.warn("""ssh-keygen -t rsa -N "" -f "%s/.ssh/id_rsa"""" format home)
      notNow
    }
  }

  def store = new File(System.getProperty("user.home"), ".heroic/credentials")
}
