sbtPlugin := true

organization := "me.lessis"

name := "heroic"

version <<= sbtVersion(v =>
  if(v.startsWith("0.11")) "0.1.0-SNAPSHOT"
  else if(v.startsWith("0.10")) "0.1.0-%s-SNAPSHOT".format(v)
  else error("unsupported version of sbt %s" format v)
)

seq(ScriptedPlugin.scriptedSettings: _*)
