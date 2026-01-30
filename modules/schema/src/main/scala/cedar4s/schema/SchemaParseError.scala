package cedar4s.schema

/** Error types for schema parsing.
  */
sealed trait SchemaParseError {
  def message: String
}

object SchemaParseError {

  /** Error decoding JSON structure */
  final case class JsonDecodeError(details: String) extends SchemaParseError {
    def message: String = s"JSON decode error: $details"
  }

  /** Error converting between Cedar and JSON formats */
  final case class ConversionError(details: String) extends SchemaParseError {
    def message: String = s"Schema conversion error: $details"
  }

  /** Invalid schema structure */
  final case class InvalidSchema(details: String) extends SchemaParseError {
    def message: String = s"Invalid schema: $details"
  }

  /** File I/O error */
  final case class FileError(details: String) extends SchemaParseError {
    def message: String = s"File error: $details"
  }
}
