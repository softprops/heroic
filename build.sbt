sbtPlugin := true

organization := "me.lessis"

name := "heroic"

version <<= sbtVersion("0.1.0-%s-SNAPSHOT" format _)
