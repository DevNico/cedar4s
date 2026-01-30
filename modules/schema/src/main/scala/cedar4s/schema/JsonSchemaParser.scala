package cedar4s.schema

import cedar4s.schema.JsonSchemaModels._

/** Parser that transforms Cedar JSON schema into the CedarSchema AST.
  *
  * This parser handles the JSON schema format as defined in:
  * @see
  *   https://docs.cedarpolicy.com/schema/json-schema.html
  */
object JsonSchemaParser {

  private final case class TypeContext(
      namespace: Option[String],
      entityTypes: Set[String],
      commonTypes: Set[String],
      entityQualified: Set[String],
      commonQualified: Set[String]
  )

  /** Parse a JSON schema string into a CedarSchema AST.
    *
    * @param json
    *   The JSON schema content
    * @return
    *   Either a parse error or the parsed schema AST
    */
  def parse(json: String): Either[SchemaParseError, CedarSchema] =
    for {
      jsonSchema <- JsonSchemaModels.decode(json).left.map(e => SchemaParseError.JsonDecodeError(e.getMessage))
      ast <- toAst(jsonSchema)
    } yield ast

  /** Parse a JSON schema, throwing on error. Useful for tests and when errors should be fatal.
    */
  def parseUnsafe(json: String): CedarSchema =
    parse(json).fold(
      err => throw new IllegalArgumentException(err.message),
      identity
    )

  // ============================================================================
  // JSON to AST Transformation
  // ============================================================================

  private def toAst(json: JsonSchema): Either[SchemaParseError, CedarSchema] = {
    val entityQualified = json.toList.flatMap { case (nsName, nsJson) =>
      val prefix = if (nsName.isEmpty) "" else nsName + "::"
      nsJson.entityTypes.keys.map(name => prefix + name)
    }.toSet
    val commonQualified = json.toList.flatMap { case (nsName, nsJson) =>
      val prefix = if (nsName.isEmpty) "" else nsName + "::"
      nsJson.commonTypes.keys.map(name => prefix + name)
    }.toSet

    val namespaces = json.toList.map { case (nsName, nsJson) =>
      val ctx = TypeContext(
        namespace = if (nsName.isEmpty) None else Some(nsName),
        entityTypes = nsJson.entityTypes.keySet,
        commonTypes = nsJson.commonTypes.keySet,
        entityQualified = entityQualified,
        commonQualified = commonQualified
      )
      toNamespace(nsName, nsJson, ctx)
    }
    Right(CedarSchema(namespaces))
  }

  private def toNamespace(nsName: String, nsJson: NamespaceJson, ctx: TypeContext): Namespace = {
    val name = if (nsName.isEmpty) None else Some(QualifiedName(nsName))

    val entities = nsJson.entityTypes.toList.map { case (eName, eJson) =>
      toEntityDecl(eName, eJson, ctx)
    }

    val actions = nsJson.actions.toList.map { case (aName, aJson) =>
      toActionDecl(aName, aJson, ctx)
    }

    val commonTypes = nsJson.commonTypes.toList.map { case (tName, tJson) =>
      toCommonTypeDecl(tName, tJson, ctx)
    }

    Namespace(
      name = name,
      entities = entities,
      actions = actions,
      commonTypes = commonTypes,
      annotations = toAnnotations(nsJson.annotations)
    )
  }

  // ============================================================================
  // Entity Declarations
  // ============================================================================

  private def toEntityDecl(name: String, json: EntityTypeJson, ctx: TypeContext): EntityDecl = {
    EntityDecl(
      name = name,
      memberOf = json.memberOfTypes.getOrElse(Nil).map(QualifiedName(_)),
      shape = json.shape.map(toRecordType(_, ctx)),
      tags = json.tags.map(toSchemaType(_, ctx)),
      enumValues = json.`enum`,
      annotations = toAnnotations(json.annotations)
    )
  }

  // ============================================================================
  // Action Declarations
  // ============================================================================

  private def toActionDecl(name: String, json: ActionJson, ctx: TypeContext): ActionDecl = {
    ActionDecl(
      name = name,
      memberOf = json.memberOf.getOrElse(Nil).map(toActionRef),
      appliesTo = json.appliesTo.map(toAppliesTo(_, ctx)),
      annotations = toAnnotations(json.annotations)
    )
  }

  private def toActionRef(json: ActionMemberJson): ActionRef = {
    json.`type` match {
      case Some(tpe) =>
        // Qualified action reference: type is the namespace::Action, id is the action name
        val parts = tpe.split("::").toList
        if (parts.lastOption.contains("Action")) {
          ActionRef.Qualified(QualifiedName(parts), json.id)
        } else {
          // Just treat as identifier
          ActionRef.Ident(json.id)
        }
      case None =>
        // Simple identifier
        ActionRef.Ident(json.id)
    }
  }

  private def toAppliesTo(json: AppliesToJson, ctx: TypeContext): AppliesTo = {
    AppliesTo(
      principals = json.principalTypes.getOrElse(Nil).map(QualifiedName(_)),
      resources = json.resourceTypes.getOrElse(Nil).map(QualifiedName(_)),
      context = json.context.map(toRecordTypeFromType(_, ctx))
    )
  }

  // ============================================================================
  // Common Type Declarations
  // ============================================================================

  private def toCommonTypeDecl(name: String, json: TypeJson, ctx: TypeContext): CommonTypeDecl = {
    CommonTypeDecl(
      name = name,
      typeExpr = toSchemaType(json, ctx),
      annotations = Nil // commonTypes don't have annotations in JSON format at this level
    )
  }

  // ============================================================================
  // Type Transformations
  // ============================================================================

  private def toSchemaType(json: TypeJson, ctx: TypeContext): SchemaType = {
    json.`type` match {
      // Primitive types
      case Some("String")                 => SchemaType.Primitive(PrimitiveType.String)
      case Some("Long")                   => SchemaType.Primitive(PrimitiveType.Long)
      case Some("Boolean") | Some("Bool") => SchemaType.Primitive(PrimitiveType.Bool)

      // Entity reference
      case Some("Entity") =>
        val name = json.name.getOrElse("Unknown")
        SchemaType.EntityRef(QualifiedName(name))

      // Extension types
      case Some("Extension") =>
        val extName = json.name.getOrElse("unknown")
        extName match {
          case "ipaddr"   => SchemaType.Extension(ExtensionType.ipaddr)
          case "decimal"  => SchemaType.Extension(ExtensionType.decimal)
          case "datetime" => SchemaType.Extension(ExtensionType.datetime)
          case "duration" => SchemaType.Extension(ExtensionType.duration)
          case other      => SchemaType.Extension(ExtensionType.ipaddr) // Fallback, shouldn't happen
        }

      // Set type
      case Some("Set") =>
        val element = json.element.map(toSchemaType(_, ctx)).getOrElse(SchemaType.String)
        SchemaType.SetOf(element)

      // Record type
      case Some("Record") =>
        val record = toRecordTypeFromAttributes(json.attributes.getOrElse(Map.empty), ctx)
        SchemaType.Record(record)

      // EntityOrCommon - could be entity, common type, extension type, OR primitive type reference
      case Some("EntityOrCommon") =>
        val name = json.name.getOrElse("Unknown")
        resolveEntityOrCommon(name, ctx)

      // Common type reference (any other string is treated as a type name)
      case Some(typeName) =>
        // Could be a common type reference
        SchemaType.TypeRef(QualifiedName(typeName))

      case None =>
        // Default to String if no type specified
        SchemaType.Primitive(PrimitiveType.String)
    }
  }

  private def toRecordType(json: ShapeJson, ctx: TypeContext): RecordType = {
    toRecordTypeFromAttributes(json.attributes, ctx)
  }

  private def toRecordTypeFromType(json: TypeJson, ctx: TypeContext): RecordType = {
    json.`type` match {
      case Some("Record") =>
        toRecordTypeFromAttributes(json.attributes.getOrElse(Map.empty), ctx)
      case _ =>
        // If it's a type reference to a common type, we can't resolve it here
        // Return empty record for now; semantic analysis can resolve
        RecordType.empty
    }
  }

  private def toRecordTypeFromAttributes(attrs: Map[String, AttributeJson], ctx: TypeContext): RecordType = {
    val attributes = attrs.toList.map { case (attrName, attrJson) =>
      toAttributeDecl(attrName, attrJson, ctx)
    }
    RecordType(attributes)
  }

  private def toAttributeDecl(name: String, json: AttributeJson, ctx: TypeContext): AttributeDecl = {
    val typeExpr = toSchemaTypeFromAttr(json, ctx)
    val optional = !json.required.getOrElse(true) // Default is required

    AttributeDecl(
      name = name,
      typeExpr = typeExpr,
      optional = optional,
      annotations = toAnnotations(json.annotations)
    )
  }

  private def toSchemaTypeFromAttr(json: AttributeJson, ctx: TypeContext): SchemaType = {
    json.`type` match {
      // Primitive types
      case Some("String")                 => SchemaType.Primitive(PrimitiveType.String)
      case Some("Long")                   => SchemaType.Primitive(PrimitiveType.Long)
      case Some("Boolean") | Some("Bool") => SchemaType.Primitive(PrimitiveType.Bool)

      // Entity reference
      case Some("Entity") =>
        val name = json.name.getOrElse("Unknown")
        SchemaType.EntityRef(QualifiedName(name))

      // Extension types
      case Some("Extension") =>
        val extName = json.name.getOrElse("unknown")
        extName match {
          case "ipaddr"   => SchemaType.Extension(ExtensionType.ipaddr)
          case "decimal"  => SchemaType.Extension(ExtensionType.decimal)
          case "datetime" => SchemaType.Extension(ExtensionType.datetime)
          case "duration" => SchemaType.Extension(ExtensionType.duration)
          case _          => SchemaType.Extension(ExtensionType.ipaddr)
        }

      // Set type
      case Some("Set") =>
        val element = json.element.map(toSchemaType(_, ctx)).getOrElse(SchemaType.String)
        SchemaType.SetOf(element)

      // Record type
      case Some("Record") =>
        val record = toRecordTypeFromAttributes(json.attributes.getOrElse(Map.empty), ctx)
        SchemaType.Record(record)

      // EntityOrCommon - could be entity, common type, extension type, OR primitive type
      case Some("EntityOrCommon") =>
        val name = json.name.getOrElse("Unknown")
        resolveEntityOrCommon(name, ctx)

      // Common type reference
      case Some(typeName) =>
        SchemaType.TypeRef(QualifiedName(typeName))

      case None =>
        SchemaType.Primitive(PrimitiveType.String)
    }
  }

  private def resolveEntityOrCommon(name: String, ctx: TypeContext): SchemaType = {
    name match {
      case "String"           => SchemaType.Primitive(PrimitiveType.String)
      case "Long"             => SchemaType.Primitive(PrimitiveType.Long)
      case "Boolean" | "Bool" => SchemaType.Primitive(PrimitiveType.Bool)
      case "ipaddr"           => SchemaType.Extension(ExtensionType.ipaddr)
      case "decimal"          => SchemaType.Extension(ExtensionType.decimal)
      case "datetime"         => SchemaType.Extension(ExtensionType.datetime)
      case "duration"         => SchemaType.Extension(ExtensionType.duration)
      case other              =>
        val isQualified = other.contains("::")
        val simple = if (isQualified) other.split("::").last else other
        val isCommon = if (isQualified) ctx.commonQualified.contains(other) else ctx.commonTypes.contains(simple)
        val isEntity = if (isQualified) ctx.entityQualified.contains(other) else ctx.entityTypes.contains(simple)
        if (isCommon && !isEntity) SchemaType.TypeRef(QualifiedName(other))
        else if (isEntity && !isCommon) SchemaType.EntityRef(QualifiedName(other))
        else if (isCommon && isEntity) SchemaType.TypeRef(QualifiedName(other))
        else SchemaType.EntityRef(QualifiedName(other))
    }
  }

  // ============================================================================
  // Annotations
  // ============================================================================

  private def toAnnotations(json: Option[Map[String, String]]): List[Annotation] = {
    json.getOrElse(Map.empty).toList.map { case (name, value) =>
      Annotation(name, value)
    }
  }
}
