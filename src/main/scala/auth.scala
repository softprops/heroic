package heroic

object Auth {
  import java.io.{File, PrintWriter}
  import com.codahale.jerkson.Json._
  import dispatch._
  import Http._

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
    
    if((email.isEmpty || password.isEmpty) && tries > 2) error("Failed to authenticate")
    else if(email.isEmpty || password.isEmpty) {
      log.warn("Empty email or password")
      acquireCredentials(log, tries + 1)
    } else try {
      (email, HerokuClient.auth(email, password)("api_key")) match {
        case (email, key) =>
          sbt.IO.write(store, "%s\n%s".format(email, key))
          log.info("Wrote credentials to %s" format store.getPath)
      }
    } catch {
      case StatusCode(406, msg) =>
        if(tries > 2) error("Failed to authenticate. %s" format msg)
        else {
          log.warn("Invalid credentials %s" format msg)
          acquireCredentials(log, tries + 1)
        }
      case e =>
        e.printStackTrace
        if(tries > 2) error("Failed to authenticate. %s" format e.getMessage)
        else {
          log.warn("Invalid credentials %s" format e.getMessage)
          acquireCredentials(log, tries + 1)
        }
    }
  }

  def ask[T](question: String)(f: String => T): T = {
    print(question)
    f(readLine)
  }

  def store = new File(System.getProperty("user.home"), ".heroku/credentials")

}
