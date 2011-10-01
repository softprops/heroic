package heroic

object Procman {
  import sbt.{Logger, ProcessLogger, Process}
  import Process._

  case class Proc(ptype: String, cmd: String, colorize: String => String)

  lazy val rand = new java.util.Random

  def start(file: java.io.File, log: Logger, color: Boolean = true, port: Int = 5000) = {
    val c = "\033[0;32m" :: "\033[0;33m" :: "\033[0;34m" :: "\033[0;35m" :: "\033[0;36m" ::  Nil
    val procs = (io.Source.fromFile(file).getLines.zipWithIndex.map { case (s, l) => s.split(":") match {
      case Array(ptype, cmd) => Proc(ptype.trim, cmd.trim,
                                     if(color) {
                                       val clr = c(rand.nextInt(c.size))
                                       s => "%s%s \033[0m" format(clr, s)
                                     } else s => s)
      case _ => error("malformed proc definition on line %s" format l)
    }}).toList
    val pad = "%-" + math.max(procs.map(_.ptype.length).sortWith(_>_).last, 6) + "s"
    procs.toList.map { p =>
      log.info("starting proc %s" format p.ptype)
      Process(p.cmd, None, ("PORT", port.toString)) run(new ProcessLogger {
        def ln(s: String) = "%s %s" format(p.colorize("%s %s |" format(
          new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()),
          pad format p.ptype
        )), s)
        def info(s: => String) {
          log.info(ln(s))
        }
        def error(s: => String) {
          log.error(ln(s))
        }
        def buffer[T](f: => T) = f
      })
    }
  }
}
