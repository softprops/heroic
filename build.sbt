sbtPlugin := true

organization := "me.lessis"

name := "heroic"

scalacOptions += "-deprecation"

version := "0.1.0-SNAPSHOT"

//seq(ScriptedPlugin.scriptedSettings: _*)

libraryDependencies += "net.databinder.dispatch" %% "dispatch-lift-json" % "0.9.4"

libraryDependencies += "org.slf4j" % "slf4j-jdk14" % "1.6.2"
