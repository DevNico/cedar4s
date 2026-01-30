package cedar4s.sbt

import sbt._
import sbt.Keys._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

// Import the Cedar codegen library (cross-compiled to Scala 2.12)
import cedar4s.codegen.{CedarCodegen, CedarCodegenArgs, CedarInput, ScalaOutput, SmithyOutput}
import cedar4s.schema.CedarSchema

/** sbt plugin for generating Cedar authorization code from Cedar schema files.
  *
  * Parses the Cedar schema (.cedarschema) and generates:
  *   - Scala: PolicyDomain.scala, Actions.scala, Resource.scala, EntityIds.scala (to managed sources in target/)
  *   - Smithy: Per-domain action enums (to smithy-generated/) [optional]
  *
  * This plugin is GENERIC and uses the cedar4s.codegen library for all code generation. All entity IDs are
  * automatically generated as newtypes (${EntityName}Id).
  *
  * Usage in build.sbt:
  * {{{
  * enablePlugins(Cedar4sPlugin)
  *
  * // Configure schema file location
  * cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "myapp.cedarschema"
  * cedarScalaPackage := "com.example.cedar.policies"
  *
  * // Configure Smithy generation (optional)
  * cedarSmithyNamespace := Some("com.example.api.authz")
  * cedarSmithyOutputDir := Some(baseDirectory.value / "smithy-specs")
  * }}}
  */
object Cedar4sPlugin extends AutoPlugin {

  object autoImport {
    // Core settings
    val cedarSchemaFile = settingKey[File]("Path to the Cedar schema file")
    val cedarScalaPackage = settingKey[String]("Package name for generated Scala code")

    // Smithy settings (optional)
    val cedarSmithyNamespace =
      settingKey[Option[String]]("Smithy namespace for generated action enums (None = disabled)")
    val cedarSmithyOutputDir = settingKey[Option[File]]("Directory for generated Smithy action enums")

    // Advanced settings
    val cedarTenantRoots = settingKey[Set[String]]("Override root entity detection (empty = auto-detect)")

    // Task
    val cedarCodegen = taskKey[Seq[File]]("Generate Scala (and optionally Smithy) code from Cedar schema")
  }

  import autoImport._

  override def trigger = noTrigger

  override def projectSettings: Seq[Setting[_]] = Seq(
    // Defaults - users should override cedarSchemaFile and cedarScalaPackage
    cedarSchemaFile := baseDirectory.value / "src" / "main" / "resources" / "schema" / "schema.cedarschema",
    cedarScalaPackage := "cedar.policies",
    cedarSmithyNamespace := None,
    cedarSmithyOutputDir := None,
    cedarTenantRoots := Set.empty,

    // Wire up source generation to compile
    Compile / sourceGenerators += cedarCodegen.taskValue,
    cedarCodegen := {
      val log = streams.value.log
      val schemaFile = cedarSchemaFile.value
      val pkg = cedarScalaPackage.value
      val smithyNs = cedarSmithyNamespace.value
      val smithyOut = cedarSmithyOutputDir.value
      val roots = cedarTenantRoots.value

      // Output Scala to managed sources (target/scala-X.Y.Z/src_managed/main)
      val scalaOutRoot = (Compile / sourceManaged).value
      val scalaOut = pkg.split("\\.").foldLeft(scalaOutRoot)(_ / _)

      log.info(s"[cedar4s] Reading schema from $schemaFile")
      log.info(s"[cedar4s] Generating Scala to $scalaOut")
      smithyNs.foreach { ns =>
        log.info(s"[cedar4s] Generating Smithy ($ns) to ${smithyOut.getOrElse("<not configured>")}")
      }

      if (!schemaFile.exists()) {
        sys.error(s"Cedar schema not found at $schemaFile")
      }

      // Build codegen args following smithy4s pattern
      val args = CedarCodegenArgs(
        input = CedarInput(
          schemaFiles = List(schemaFile.toPath),
          tenantRoots = roots
        ),
        scala = ScalaOutput(
          outputDir = scalaOut.toPath,
          packageName = pkg
        ),
        smithy = (smithyNs, smithyOut) match {
          case (Some(ns), Some(dir)) =>
            Some(
              SmithyOutput(
                outputDir = dir.toPath,
                namespace = ns
              )
            )
          case (Some(ns), None) =>
            log.warn(
              "[cedar4s] cedarSmithyNamespace is set but cedarSmithyOutputDir is not - Smithy generation disabled"
            )
            None
          case _ =>
            None
        }
      )

      // Run code generation
      CedarCodegen.runAndWrite(args) match {
        case Right(paths) =>
          log.info(s"[cedar4s] Generated ${paths.size} files")
          paths.foreach(p => log.info(s"[cedar4s]   $p"))

          // Return only Scala files for compilation (Smithy files are separate)
          paths.filter(_.toString.endsWith(".scala")).map(_.toFile)

        case Left(error) =>
          sys.error(s"Cedar codegen failed: $error")
      }
    }
  )
}
