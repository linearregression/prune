name := "prune"

version := "1.0"

scalaVersion := "2.11.2"

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.2.0",
  "com.typesafe" % "config" % "1.2.1",
  "com.typesafe.play" %% "play-json" % "2.4.0-M1",
  "commons-io" % "commons-io" % "2.4",
  "joda-time" % "joda-time" % "2.4",
  "org.apache.commons" % "commons-exec" % "1.2",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "3.4.1.201406201815-r",
  // Test dependencies
  "org.scalatest" %% "scalatest" % "2.2.1" % "test"
)

scalacOptions ++= Seq("-feature")