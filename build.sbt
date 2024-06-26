
name := """play-rfc6750"""
version := "1.0.0-rc.0"

lazy val root = (project in file("."))
    .enablePlugins(PlayJava)
    .settings(
        
    )

scalaVersion := "3.3.3"

// disable javadocs generation
Compile / doc / sources := Seq.empty
// dont publish documentation artifact
Compile / packageDoc / publishArtifact := false

libraryDependencies ++= Seq(
  caffeine,
  guice,
  ws,

  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % "test"
)

