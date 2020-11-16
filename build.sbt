name := "loadBalancer"

val AkkaVersion = "2.6.10"
val ScalaTestVersion = "3.2.2"
val ScalaTestMockitoVersion = "3.2.2.0"
val MockitoScalaTest = "1.16.3"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "org.scalatest" %% "scalatest" % ScalaTestVersion % "test",
  "org.scalatestplus" %% "mockito-3-4" % ScalaTestMockitoVersion % "test",
  "org.mockito" %% "mockito-scala-scalatest"  % MockitoScalaTest % "test",
)