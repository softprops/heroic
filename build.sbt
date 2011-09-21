sbtPlugin := true

organization := "me.lessis"

name := "heroic"

version <<= sbtVersion("0.1.0-%s-SNAPSHOT" format _)

seq(screenwriter.Plugin.options:_*)

seq(ScriptedPlugin.scriptedSettings: _*)
