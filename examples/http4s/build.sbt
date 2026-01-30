name := "cedar4s-example-http4s"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.7.0"

enablePlugins(Cedar4sPlugin)

cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "http4sauth.cedarschema"
cedarScalaPackage := "example.http4s.cedar"

val http4sVersion = "1.0.0-M44"
val circeVersion = "0.14.10"

libraryDependencies ++= Seq(
  "io.github.devnico" %% "cedar4s-core" % "0.1.0-SNAPSHOT",
  "io.github.devnico" %% "cedar4s-client" % "0.1.0-SNAPSHOT",
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "org.http4s" %% "http4s-ember-client" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion
)
