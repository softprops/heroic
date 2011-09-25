libraryDependencies <++= sbtVersion(v => Seq(
  "org.scala-tools.sbt" %% "scripted-plugin" % v
))

addSbtPlugin("me.lessis" % "screen-writer" % "0.1.0-SNAPSHOT")
