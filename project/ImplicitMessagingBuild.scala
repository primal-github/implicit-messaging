import sbt._
import sbt.Keys._

object ImplicitMessagingBuild extends Build {

  lazy val implicitMessaging = Project(
    id = "implicit-messaging",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "Implicit Messaging",
      organization := "com.primal",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.10.0",
      scalacOptions ++= Seq("-feature", "-deprecation"),
      resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.1.4"
    )
  )
}
