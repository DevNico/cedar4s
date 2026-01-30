package cedar4s.client

import com.cedarpolicy.model.schema.Schema
import com.cedarpolicy.model.exception.InternalException
import cedar4s.schema.CedarSchema

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal

/** Error types for schema validation.
  *
  * Schema validation can fail in two ways:
  *   1. Syntax errors - The schema text cannot be parsed
  *   2. Semantic errors - The schema parses but contains invalid references, cycles, or other semantic issues detected
  *      by Cedar's validator
  */
sealed trait SchemaValidationError {
  def message: String
}

object SchemaValidationError {

  final case class SyntaxError(message: String) extends SchemaValidationError

  /** Semantic errors detected by Cedar's validator. These include:
    *   - Undefined entity type references
    *   - Cycles in entity hierarchy
    *   - Invalid action principal/resource types
    *   - Duplicate declarations
    *   - Type mismatches
    */
  final case class SemanticErrors(errors: List[String]) extends SchemaValidationError {
    def message: String = errors.mkString("\n")
  }

  /** Unexpected internal error during validation.
    */
  final case class InternalError(cause: Throwable) extends SchemaValidationError {
    def message: String = s"Internal validation error: ${cause.getMessage}"
  }
}

/** A validated Cedar schema that combines:
  *   - The cedar-java Schema object (for use with authorization engine)
  *   - The cedar4s AST (for code generation and programmatic inspection)
  *
  * Creating a ValidatedSchema guarantees the schema is both syntactically and semantically valid according to Cedar's
  * rules.
  */
final case class ValidatedSchema(
    javaSchema: Schema,
    ast: CedarSchema
) {

  /** Get the original schema text in Cedar format */
  def schemaText: Option[String] = javaSchema.schemaText.toScala
}

/** Schema validator that delegates to cedar-java for authoritative validation.
  *
  * Cedar-java uses the Rust Cedar library via JNI, providing the same validation as the official Cedar CLI. This
  * catches semantic errors that a parser alone cannot detect:
  *
  *   - Undefined entity type references (e.g., `entity User in [NonExistent]`)
  *   - Cycles in entity hierarchy (e.g., `entity A in [B]; entity B in [A]`)
  *   - Invalid principal/resource types in actions
  *   - Duplicate entity/action/type declarations
  *   - Type reference errors
  *
  * ==Usage==
  * {{{
  * val schemaText = """
  *   namespace MyApp {
  *     entity User;
  *     entity Document;
  *     action "read" appliesTo {
  *       principal: [User],
  *       resource: [Document],
  *     };
  *   }
  * """
  *
  * SchemaValidator.validate(schemaText) match {
  *   case Right(validatedSchema) =>
  *     // Use validatedSchema.javaSchema with authorization engine
  *     // Use validatedSchema.ast for code generation
  *   case Left(error) =>
  *     println(s"Schema invalid: ${error.message}")
  * }
  * }}}
  */
object SchemaValidator {

  /** Validate a Cedar schema string.
    *
    * This performs two-phase validation:
    *   1. Semantic validation via cedar-java (uses Rust Cedar validator)
    *   2. Syntax parsing via cedar4s to produce the Scala AST
    *
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @return
    *   Either a validation error or a ValidatedSchema containing both the cedar-java Schema and cedar4s AST
    */
  def validate(schemaText: String): Either[SchemaValidationError, ValidatedSchema] = {
    try {
      // Phase 1: Use cedar-java for authoritative semantic validation
      // This catches undefined references, cycles, type errors, etc.
      val javaSchema = Schema.parse(Schema.JsonOrCedar.Cedar, schemaText)

      // Phase 2: Parse with cedar4s to get the Scala AST
      // This should always succeed if cedar-java passed, but we handle
      // the case where our parser has subtle differences
      CedarSchema.parse(schemaText) match {
        case Right(ast) =>
          Right(ValidatedSchema(javaSchema, ast))
        case Left(parseError) =>
          // This indicates a bug - cedar-java accepted it but our parser didn't
          // Return syntax error for now, but this should be investigated
          Left(SchemaValidationError.SyntaxError(parseError.toString))
      }
    } catch {
      case e: InternalException =>
        // cedar-java throws InternalException for validation errors
        val errors = Option(e.getErrors)
          .map(_.asScala.toList)
          .getOrElse(List(e.getMessage))
        Left(SchemaValidationError.SemanticErrors(errors))

      case NonFatal(e) =>
        Left(SchemaValidationError.InternalError(e))
    }
  }

  /** Validate a schema and return just the AST.
    *
    * Convenience method for code generation use cases where you only need the AST but still want semantic validation.
    *
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @return
    *   Either a validation error or the validated CedarSchema AST
    */
  def validateToAst(schemaText: String): Either[SchemaValidationError, CedarSchema] =
    validate(schemaText).map(_.ast)

  /** Validate a schema and return just the cedar-java Schema.
    *
    * Convenience method for authorization use cases where you need the Schema object for policy validation.
    *
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @return
    *   Either a validation error or the validated Schema
    */
  def validateToJava(schemaText: String): Either[SchemaValidationError, Schema] =
    validate(schemaText).map(_.javaSchema)

  /** Check if a schema is valid without returning the parsed result.
    *
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @return
    *   true if the schema is valid, false otherwise
    */
  def isValid(schemaText: String): Boolean =
    validate(schemaText).isRight

  /** Validate a schema from a JSON string.
    *
    * Note: JSON schemas can be validated by cedar-java, but the cedar4s parser only supports Cedar format. For JSON
    * schemas, only validation is performed; the AST will be empty.
    *
    * @param jsonSchema
    *   The Cedar schema in JSON format
    * @return
    *   Either a validation error or a ValidatedSchema (with empty AST)
    */
  def validateJson(jsonSchema: String): Either[SchemaValidationError, ValidatedSchema] = {
    try {
      // Parse and validate JSON schema with cedar-java
      val javaSchema = Schema.parse(Schema.JsonOrCedar.Json, jsonSchema)

      // cedar-java 4.2.2 doesn't have format conversion, so we return empty AST
      // The JSON schema is still validated by cedar-java
      Right(ValidatedSchema(javaSchema, CedarSchema.empty))
    } catch {
      case e: InternalException =>
        val errors = Option(e.getErrors)
          .map(_.asScala.toList)
          .getOrElse(List(e.getMessage))
        Left(SchemaValidationError.SemanticErrors(errors))

      case NonFatal(e) =>
        Left(SchemaValidationError.InternalError(e))
    }
  }
}
