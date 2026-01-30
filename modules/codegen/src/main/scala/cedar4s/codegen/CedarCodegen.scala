package cedar4s.codegen

import cedar4s.schema.CedarSchema
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}

/** Main entry point for Cedar code generation.
  *
  * Orchestrates the full pipeline:
  *   1. Parse Cedar schema files
  *   2. Transform to IR
  *   3. Render to Scala (and optionally Smithy)
  *   4. Write output files
  *
  * Follows the smithy4s pattern with separate input/output configurations.
  */
object CedarCodegen {

  /** Run code generation with the given arguments.
    *
    * @param args
    *   Code generation configuration
    * @return
    *   Generated files or error message
    */
  def run(args: CedarCodegenArgs): Either[String, CedarCodegenResult] = {
    for {
      // Parse all schema files
      schema <- parseSchemas(args.input.schemaFiles)

      // Transform to IR
      ir <- Right(transformToIR(schema, args.input))

      // Render outputs
      result <- Right(renderOutputs(ir, args))
    } yield result
  }

  /** Run code generation and write files to disk.
    *
    * @param args
    *   Code generation configuration
    * @return
    *   List of written file paths or error
    */
  def runAndWrite(args: CedarCodegenArgs): Either[String, List[Path]] = {
    run(args).flatMap { result =>
      Try {
        // Write Scala files
        Files.createDirectories(args.scala.outputDir)
        val scalaWritten = result.scalaFiles.map { case (relativePath, content) =>
          val fullPath = args.scala.outputDir.resolve(relativePath)
          Files.createDirectories(fullPath.getParent)
          Files.write(fullPath, content.getBytes(StandardCharsets.UTF_8))
          fullPath
        }

        // Write Smithy files (if configured)
        val smithyWritten = args.smithy match {
          case Some(smithyConfig) =>
            Files.createDirectories(smithyConfig.outputDir)
            result.smithyFiles.map { case (relativePath, content) =>
              val fullPath = smithyConfig.outputDir.resolve(relativePath)
              Files.createDirectories(fullPath.getParent)
              Files.write(fullPath, content.getBytes(StandardCharsets.UTF_8))
              fullPath
            }
          case None =>
            Seq.empty
        }

        (scalaWritten ++ smithyWritten).toList
      } match {
        case Success(paths) => Right(paths)
        case Failure(e)     => Left(s"Failed to write files: ${e.getMessage}")
      }
    }
  }

  /** Parse multiple schema files into a single schema.
    */
  private def parseSchemas(files: List[Path]): Either[String, CedarSchema] = {
    if (files.isEmpty) {
      Left("No schema files provided")
    } else {
      val results = files.map { file =>
        Try(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)) match {
          case Success(content) => CedarSchema.parse(content).left.map(_.toString)
          case Failure(e)       => Left(s"Failed to read ${file}: ${e.getMessage}")
        }
      }

      // Collect errors
      val errors = results.collect { case Left(e) => e }
      if (errors.nonEmpty) {
        Left(errors.mkString("\n"))
      } else {
        // Merge all schemas
        val schemas = results.collect { case Right(s) => s }
        Right(mergeSchemas(schemas))
      }
    }
  }

  /** Merge multiple schemas into one.
    */
  private def mergeSchemas(schemas: List[CedarSchema]): CedarSchema = {
    CedarSchema(schemas.flatMap(_.namespaces))
  }

  /** Transform parsed schema to IR.
    */
  private def transformToIR(schema: CedarSchema, input: CedarInput): CedarIR = {
    val config = SchemaToIR.Config(
      tenantRoots = input.tenantRoots
    )
    SchemaToIR.transform(schema, config)
  }

  /** Render IR to output files.
    */
  private def renderOutputs(ir: CedarIR, args: CedarCodegenArgs): CedarCodegenResult = {
    // Render Scala (always generated)
    val scalaFiles: Map[Path, String] = ScalaRenderer.render(ir, args.scala.packageName).map { case (name, content) =>
      Paths.get(name) -> content
    }

    // Render Smithy (optional - only if configured)
    val smithyFiles: Map[Path, String] = args.smithy match {
      case Some(smithyConfig) =>
        SmithyRenderer.render(ir, smithyConfig.namespace)
      case None =>
        Map.empty
    }

    CedarCodegenResult(
      scalaFiles = scalaFiles,
      smithyFiles = smithyFiles
    )
  }

  /** Generate code from a schema string (for testing).
    *
    * @param schemaContent
    *   Cedar schema content
    * @param outputPackage
    *   Package name for generated code
    * @return
    *   Map of filename -> content
    */
  def generateFromString(
      schemaContent: String,
      outputPackage: String
  ): Either[String, Map[String, String]] = {
    CedarSchema.parse(schemaContent) match {
      case Left(err)     => Left(err.toString)
      case Right(schema) =>
        val ir = SchemaToIR.transform(schema)
        Right(ScalaRenderer.render(ir, outputPackage))
    }
  }
}
