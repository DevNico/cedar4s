package cedar4s.codegen

import java.nio.file.Path

/** Configuration for Cedar code generation.
  *
  * Follows the smithy4s pattern: specify inputs and desired outputs. Scala generation is always enabled. Smithy
  * generation is enabled when `smithy` is defined.
  *
  * ==Example Usage==
  *
  * {{{
  * // Scala only (default)
  * CedarCodegenArgs(
  *   input = CedarInput(
  *     schemaFiles = List(Paths.get("schema.cedarschema"))
  *   ),
  *   scala = ScalaOutput(
  *     outputDir = Paths.get("src/main/scala"),
  *     packageName = "com.example.authz"
  *   )
  * )
  *
  * // With Smithy integration
  * CedarCodegenArgs(
  *   input = CedarInput(
  *     schemaFiles = List(Paths.get("schema.cedarschema"))
  *   ),
  *   scala = ScalaOutput(
  *     outputDir = Paths.get("src/main/scala"),
  *     packageName = "com.example.authz"
  *   ),
  *   smithy = Some(SmithyOutput(
  *     outputDir = Paths.get("src/main/smithy"),
  *     namespace = "com.example.api.authz"
  *   ))
  * )
  * }}}
  */
final case class CedarCodegenArgs(
    /** Input configuration (schema files) */
    input: CedarInput,

    /** Scala output configuration (always required) */
    scala: ScalaOutput,

    /** Smithy output configuration (optional) */
    smithy: Option[SmithyOutput] = None
)

/** Input configuration for Cedar code generation.
  *
  * @param schemaFiles
  *   Cedar schema files to process (.cedarschema)
  * @param tenantRoots
  *   Override root entity detection (empty = auto-detect)
  */
final case class CedarInput(
    schemaFiles: List[Path],
    tenantRoots: Set[String] = Set.empty
)

/** Scala output configuration.
  *
  * @param outputDir
  *   Directory for generated Scala files
  * @param packageName
  *   Package name for generated code
  */
final case class ScalaOutput(
    outputDir: Path,
    packageName: String
)

/** Smithy output configuration.
  *
  * When provided, generates per-domain action enums for use in smithy4s API definitions.
  *
  * @param outputDir
  *   Directory for generated .smithy files
  * @param namespace
  *   Smithy namespace (e.g., "com.example.api.authz")
  */
final case class SmithyOutput(
    outputDir: Path,
    namespace: String
)

/** Result of code generation.
  *
  * @param scalaFiles
  *   Generated Scala files (relative path -> content)
  * @param smithyFiles
  *   Generated Smithy files (relative path -> content)
  * @param warnings
  *   Any warnings during generation
  */
final case class CedarCodegenResult(
    scalaFiles: Map[Path, String],
    smithyFiles: Map[Path, String] = Map.empty,
    warnings: List[String] = Nil
) {

  /** All generated files */
  def allFiles: Map[Path, String] = scalaFiles ++ smithyFiles

  def ++(other: CedarCodegenResult): CedarCodegenResult =
    CedarCodegenResult(
      scalaFiles ++ other.scalaFiles,
      smithyFiles ++ other.smithyFiles,
      warnings ++ other.warnings
    )
}

object CedarCodegenResult {
  val empty: CedarCodegenResult = CedarCodegenResult(Map.empty)
}
