import play.sbt.PlayImport._

name := "cedar4s-example-play-framework"
version := "0.1.0-SNAPSHOT"
scalaVersion := "3.7.0"

enablePlugins(Cedar4sPlugin, Smithy4sCodegenPlugin, PlayScala)

cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "playauth.cedarschema"
cedarScalaPackage := "example.playframework.cedar"
cedarSmithyNamespace := Some("example.playframework.authz")
cedarSmithyOutputDir := Some(baseDirectory.value / "src" / "main" / "resources" / "smithy" / "authz")

Compile / smithy4sInputDirs := Seq(
  baseDirectory.value / "src" / "main" / "resources" / "smithy",
  baseDirectory.value / "src" / "main" / "resources" / "smithy" / "authz"
)
Compile / smithy4sOutputDir := (Compile / sourceManaged).value / "main"
Compile / smithy4sAllowedNamespaces := List("example.playframework.api", "example.playframework.authz")

// Include policies and schema on classpath (Play defaults to conf/ only)
Compile / unmanagedResourceDirectories += baseDirectory.value / "src" / "main" / "resources"

val slickVersion = "3.5.2"
val jwtVersion = "10.0.0"
val smithy4playVersion = "1.1.1"

githubTokenSource := TokenSource.Environment("GITHUB_PACKAGES_TOKEN") || TokenSource.GitConfig(
  "github.token"
) || TokenSource.Environment("GITHUB_TOKEN")
resolvers += Resolver.githubPackages("innFactory")

// Align Jackson: cedar-java needs 2.15+, Play ships 2.14.x jackson-module-scala
// Use 2.17.x which satisfies both; override module-scala to match
val jacksonVersion = "2.17.3"
dependencyOverrides ++= Seq(
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
  "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion
)

libraryDependencies ++= Seq(
  guice,
  "io.github.devnico" %% "cedar4s-core" % "0.1.0-SNAPSHOT",
  "io.github.devnico" %% "cedar4s-client" % "0.1.0-SNAPSHOT",
  "de.innfactory" %% "smithy4play" % smithy4playVersion,
  "de.innfactory" %% "smithy4play-mcp" % smithy4playVersion,
  "com.typesafe.slick" %% "slick" % slickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
  "com.h2database" % "h2" % "2.2.224",
  "com.github.jwt-scala" %% "jwt-core" % jwtVersion,
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
)
