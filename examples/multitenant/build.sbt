name := "cedar4s-example-multitenant"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.7.0"

enablePlugins(Cedar4sPlugin)

cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "saas.cedarschema"
cedarScalaPackage := "example.saas.cedar"

libraryDependencies ++= Seq(
  "io.github.devnico" %% "cedar4s-core" % "0.1.0-SNAPSHOT",
  "io.github.devnico" %% "cedar4s-client" % "0.1.0-SNAPSHOT"
)
