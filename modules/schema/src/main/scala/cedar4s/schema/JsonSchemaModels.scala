package cedar4s.schema

import io.circe._
import io.circe.generic.semiauto._

/** JSON schema models matching Cedar's JSON schema format.
  *
  * @see
  *   https://docs.cedarpolicy.com/schema/json-schema.html
  */
object JsonSchemaModels {

  // ============================================================================
  // Top-Level Schema
  // ============================================================================

  /** Root schema is a map from namespace name to namespace definition. Empty string key represents the empty/global
    * namespace.
    */
  type JsonSchema = Map[String, NamespaceJson]

  // ============================================================================
  // Namespace
  // ============================================================================

  final case class NamespaceJson(
      entityTypes: Map[String, EntityTypeJson] = Map.empty,
      actions: Map[String, ActionJson] = Map.empty,
      commonTypes: Map[String, TypeJson] = Map.empty,
      annotations: Option[Map[String, String]] = None
  )

  object NamespaceJson {
    implicit val decoder: Decoder[NamespaceJson] = Decoder.instance { cursor =>
      for {
        entityTypes <- cursor.getOrElse[Map[String, EntityTypeJson]]("entityTypes")(Map.empty)
        actions <- cursor.getOrElse[Map[String, ActionJson]]("actions")(Map.empty)
        commonTypes <- cursor.getOrElse[Map[String, TypeJson]]("commonTypes")(Map.empty)
        annotations <- cursor.get[Option[Map[String, String]]]("annotations")
      } yield NamespaceJson(entityTypes, actions, commonTypes, annotations)
    }
  }

  // ============================================================================
  // Entity Types
  // ============================================================================

  final case class EntityTypeJson(
      memberOfTypes: Option[List[String]] = None,
      shape: Option[ShapeJson] = None,
      tags: Option[TypeJson] = None,
      // Enum entities have predefined EIDs
      `enum`: Option[List[String]] = None,
      annotations: Option[Map[String, String]] = None
  )

  object EntityTypeJson {
    implicit val decoder: Decoder[EntityTypeJson] = deriveDecoder[EntityTypeJson]
  }

  // ============================================================================
  // Actions
  // ============================================================================

  final case class ActionJson(
      memberOf: Option[List[ActionMemberJson]] = None,
      appliesTo: Option[AppliesToJson] = None,
      annotations: Option[Map[String, String]] = None
  )

  object ActionJson {
    implicit val decoder: Decoder[ActionJson] = deriveDecoder[ActionJson]
  }

  final case class ActionMemberJson(
      id: String,
      `type`: Option[String] = None
  )

  object ActionMemberJson {
    implicit val decoder: Decoder[ActionMemberJson] = deriveDecoder[ActionMemberJson]
  }

  final case class AppliesToJson(
      principalTypes: Option[List[String]] = None,
      resourceTypes: Option[List[String]] = None,
      context: Option[TypeJson] = None
  )

  object AppliesToJson {
    implicit val decoder: Decoder[AppliesToJson] = deriveDecoder[AppliesToJson]
  }

  // ============================================================================
  // Shape (Record Type for Entity Attributes)
  // ============================================================================

  final case class ShapeJson(
      `type`: String, // Always "Record"
      attributes: Map[String, AttributeJson] = Map.empty
  )

  object ShapeJson {
    implicit val decoder: Decoder[ShapeJson] = deriveDecoder[ShapeJson]
  }

  final case class AttributeJson(
      `type`: Option[String] = None,
      name: Option[String] = None,
      element: Option[TypeJson] = None,
      attributes: Option[Map[String, AttributeJson]] = None,
      required: Option[Boolean] = None,
      annotations: Option[Map[String, String]] = None
  )

  object AttributeJson {
    implicit lazy val decoder: Decoder[AttributeJson] = deriveDecoder[AttributeJson]
  }

  // ============================================================================
  // Type Expressions
  // ============================================================================

  /** Type expressions in Cedar JSON schema.
    *
    * Types can be:
    *   - Primitives: { "type": "String" }, { "type": "Long" }, { "type": "Boolean" }
    *   - Entity refs: { "type": "Entity", "name": "User" }
    *   - Extensions: { "type": "Extension", "name": "ipaddr" }
    *   - Sets: { "type": "Set", "element": {...} }
    *   - Records: { "type": "Record", "attributes": {...} }
    *   - Common type refs: { "type": "SomeTypeName" } or { "type": "EntityOrCommon", "name": "..." }
    */
  final case class TypeJson(
      `type`: Option[String] = None,
      name: Option[String] = None,
      element: Option[TypeJson] = None,
      attributes: Option[Map[String, AttributeJson]] = None
  )

  object TypeJson {
    implicit lazy val decoder: Decoder[TypeJson] = deriveDecoder[TypeJson]
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  /** Decode a JSON string into a JsonSchema */
  def decode(json: String): Either[io.circe.Error, JsonSchema] =
    io.circe.parser.decode[JsonSchema](json)

  /** Implicit decoder for the root schema */
  implicit val schemaDecoder: Decoder[JsonSchema] = Decoder.decodeMap[String, NamespaceJson]
}
