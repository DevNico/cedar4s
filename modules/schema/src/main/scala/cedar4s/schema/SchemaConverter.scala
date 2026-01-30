package cedar4s.schema

import com.cedarpolicy.model.schema.{Schema => JSchema}
import com.cedarpolicy.model.schema.Schema.JsonOrCedar

/** Converter between Cedar human-readable schema format and JSON schema format.
  *
  * Uses cedar-java's native conversion functions via JNI.
  */
object SchemaConverter {

  /** Convert Cedar human-readable schema to JSON format.
    *
    * @param cedarSchema
    *   Schema in Cedar human-readable format
    * @return
    *   Either an error or the JSON schema string
    */
  def cedarToJson(cedarSchema: String): Either[SchemaParseError, String] =
    try {
      val schema = JSchema.parse(JsonOrCedar.Cedar, cedarSchema)
      val jsonNode = schema.toJsonFormat()
      Right(jsonNode.toString)
    } catch {
      case e: com.cedarpolicy.model.exception.InternalException =>
        Left(SchemaParseError.ConversionError(e.getMessage))
      case e: Exception =>
        Left(SchemaParseError.ConversionError(s"Unexpected error: ${e.getMessage}"))
    }

  /** Convert JSON schema to Cedar human-readable format.
    *
    * @param jsonSchema
    *   Schema in JSON format
    * @return
    *   Either an error or the Cedar schema string
    */
  def jsonToCedar(jsonSchema: String): Either[SchemaParseError, String] =
    try {
      val schema = JSchema.parse(JsonOrCedar.Json, jsonSchema)
      Right(schema.toCedarFormat())
    } catch {
      case e: com.cedarpolicy.model.exception.InternalException =>
        Left(SchemaParseError.ConversionError(e.getMessage))
      case e: Exception =>
        Left(SchemaParseError.ConversionError(s"Unexpected error: ${e.getMessage}"))
    }

  /** Validate that a Cedar schema string is syntactically correct.
    *
    * @param cedarSchema
    *   Schema in Cedar human-readable format
    * @return
    *   Either an error or Unit on success
    */
  def validateCedar(cedarSchema: String): Either[SchemaParseError, Unit] =
    try {
      JSchema.parse(JsonOrCedar.Cedar, cedarSchema)
      Right(())
    } catch {
      case e: com.cedarpolicy.model.exception.InternalException =>
        Left(SchemaParseError.InvalidSchema(e.getMessage))
      case e: Exception =>
        Left(SchemaParseError.InvalidSchema(s"Unexpected error: ${e.getMessage}"))
    }

  /** Validate that a JSON schema string is syntactically correct.
    *
    * @param jsonSchema
    *   Schema in JSON format
    * @return
    *   Either an error or Unit on success
    */
  def validateJson(jsonSchema: String): Either[SchemaParseError, Unit] =
    try {
      JSchema.parse(JsonOrCedar.Json, jsonSchema)
      Right(())
    } catch {
      case e: com.cedarpolicy.model.exception.InternalException =>
        Left(SchemaParseError.InvalidSchema(e.getMessage))
      case e: Exception =>
        Left(SchemaParseError.InvalidSchema(s"Unexpected error: ${e.getMessage}"))
    }
}
