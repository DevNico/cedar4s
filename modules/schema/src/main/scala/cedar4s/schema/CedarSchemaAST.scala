package cedar4s.schema

/** Cedar Schema Abstract Syntax Tree
  *
  * This module provides a complete AST representation of Cedar schema files, following the official Cedar schema
  * grammar specification.
  *
  * This is a generic, reusable module with no domain-specific assumptions. It can be used with any Cedar schema
  * regardless of authorization pattern.
  *
  * @see
  *   https://docs.cedarpolicy.com/schema/human-readable-schema-grammar.html
  */

// ============================================================================
// Core Schema
// ============================================================================

/** A complete Cedar schema containing zero or more namespaces.
  *
  * @param namespaces
  *   The namespaces in this schema. An empty list means all declarations are in the empty (global) namespace.
  */
final case class CedarSchema(
    namespaces: List[Namespace]
) {

  /** Get all entity declarations across all namespaces */
  def allEntities: List[EntityDecl] = namespaces.flatMap(_.entities)

  /** Get all action declarations across all namespaces */
  def allActions: List[ActionDecl] = namespaces.flatMap(_.actions)

  /** Get all common type declarations across all namespaces */
  def allCommonTypes: List[CommonTypeDecl] = namespaces.flatMap(_.commonTypes)

  /** Find an entity by unqualified name (searches all namespaces) */
  def findEntity(name: String): Option[EntityDecl] =
    allEntities.find(_.name == name)

  /** Find an entity by qualified name */
  def findEntity(name: QualifiedName): Option[EntityDecl] = name.namespace match {
    case Some(ns) => namespaces.find(_.name.exists(_.value == ns)).flatMap(_.entities.find(_.name == name.simple))
    case None     => findEntity(name.simple)
  }

  /** Find an action by name (e.g., "Mission::read") */
  def findAction(name: String): Option[ActionDecl] =
    allActions.find(_.name == name)

  /** Get all entities grouped by their domain (parsed from action names) */
  def entitiesByDomain: Map[String, List[EntityDecl]] = {
    val domainsFromActions = allActions.flatMap(_.domain).toSet
    domainsFromActions.map { domain =>
      domain -> allEntities.filter(_.name == domain)
    }.toMap
  }

  /** Get all actions grouped by domain */
  def actionsByDomain: Map[String, List[ActionDecl]] =
    allActions.groupBy(_.domain.getOrElse("Unknown"))

  /** Build the entity hierarchy graph */
  def entityHierarchy: EntityHierarchy = EntityHierarchy.build(this)
}

object CedarSchema {
  val empty: CedarSchema = CedarSchema(Nil)

  // ============================================================================
  // Parsing API
  // ============================================================================

  /** Parse a schema from JSON format.
    *
    * @param json
    *   The JSON schema content
    * @return
    *   Either a parse error or the parsed schema
    */
  def fromJson(json: String): Either[SchemaParseError, CedarSchema] =
    JsonSchemaParser.parse(json)

  /** Parse a schema from Cedar human-readable format.
    *
    * This converts the Cedar format to JSON using cedar-java, then parses the JSON.
    *
    * @param cedar
    *   The Cedar schema content
    * @return
    *   Either a parse error or the parsed schema
    */
  def fromCedar(cedar: String): Either[SchemaParseError, CedarSchema] =
    for {
      json <- SchemaConverter.cedarToJson(cedar)
      ast <- JsonSchemaParser.parse(json)
    } yield ast

  /** Parse a schema, auto-detecting the format.
    *
    * If the content starts with '{', it's treated as JSON. Otherwise, it's treated as Cedar human-readable format.
    *
    * @param content
    *   The schema content
    * @return
    *   Either a parse error or the parsed schema
    */
  def parse(content: String): Either[SchemaParseError, CedarSchema] = {
    val trimmed = content.trim
    if (trimmed.startsWith("{")) fromJson(content)
    else fromCedar(content)
  }

  /** Parse a schema, throwing on error. Useful for tests and when errors should be fatal.
    *
    * @param content
    *   The schema content (auto-detects format)
    * @return
    *   The parsed schema
    * @throws IllegalArgumentException
    *   if parsing fails
    */
  def parseUnsafe(content: String): CedarSchema =
    parse(content).fold(
      err => throw new IllegalArgumentException(err.message),
      identity
    )

  /** Parse a schema from a file path.
    *
    * Format is detected by file extension:
    *   - `.json` or `.cedarschema.json` → JSON format
    *   - `.cedarschema` or other → Cedar human-readable format
    *
    * @param path
    *   The file path
    * @return
    *   Either a parse error or the parsed schema
    */
  def fromFile(path: java.nio.file.Path): Either[SchemaParseError, CedarSchema] = {
    try {
      val content = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)
      val fileName = path.getFileName.toString.toLowerCase
      if (fileName.endsWith(".json")) fromJson(content)
      else fromCedar(content)
    } catch {
      case e: java.io.IOException =>
        Left(SchemaParseError.FileError(e.getMessage))
    }
  }

  /** Parse a schema from a file path string.
    */
  def fromFile(pathStr: String): Either[SchemaParseError, CedarSchema] =
    fromFile(java.nio.file.Paths.get(pathStr))
}

// ============================================================================
// Namespace
// ============================================================================

/** A namespace containing entity, action, and type declarations.
  *
  * @param name
  *   The namespace name (None for the empty/global namespace)
  * @param entities
  *   Entity type declarations
  * @param actions
  *   Action declarations
  * @param commonTypes
  *   Common type declarations (type aliases)
  * @param annotations
  *   Annotations on the namespace itself
  */
final case class Namespace(
    name: Option[QualifiedName],
    entities: List[EntityDecl],
    actions: List[ActionDecl],
    commonTypes: List[CommonTypeDecl],
    annotations: List[Annotation] = Nil
) {

  /** Is this the empty (global) namespace? */
  def isEmpty: Boolean = name.isEmpty

  /** Get the namespace prefix for fully-qualified names */
  def prefix: String = name.map(_.value + "::").getOrElse("")

  /** Fully-qualify an entity name within this namespace */
  def qualify(entityName: String): QualifiedName =
    name.map(n => QualifiedName(n.parts :+ entityName)).getOrElse(QualifiedName.simple(entityName))

  /** Get the @doc annotation value, if any */
  def doc: Option[String] =
    annotations.find(_.name == "doc").map(_.value)

  /** Get a custom annotation value by name */
  def annotation(annotName: String): Option[String] =
    annotations.find(_.name == annotName).map(_.value)
}

object Namespace {
  def empty(
      entities: List[EntityDecl] = Nil,
      actions: List[ActionDecl] = Nil,
      commonTypes: List[CommonTypeDecl] = Nil
  ): Namespace = Namespace(None, entities, actions, commonTypes)
}

// ============================================================================
// Names and References
// ============================================================================

/** A qualified name like "Robotsecurity::User" or just "User".
  *
  * @param parts
  *   The parts of the name, split by "::"
  */
final case class QualifiedName(parts: List[String]) {
  require(parts.nonEmpty, "QualifiedName must have at least one part")

  /** The full name with "::" separators */
  def value: String = parts.mkString("::")

  /** The simple (last) part of the name */
  def simple: String = parts.last

  /** The namespace part (all but the last), if any */
  def namespace: Option[String] =
    if (parts.size > 1) Some(parts.init.mkString("::")) else None

  /** Whether this is a simple (unqualified) name */
  def isSimple: Boolean = parts.size == 1

  /** Prepend a namespace to this name */
  def inNamespace(ns: QualifiedName): QualifiedName =
    QualifiedName(ns.parts ++ parts)

  override def toString: String = value
}

object QualifiedName {

  /** Parse a qualified name from a string like "Foo::Bar::Baz" */
  def apply(name: String): QualifiedName =
    QualifiedName(name.split("::").toList)

  /** Create a simple (unqualified) name */
  def simple(name: String): QualifiedName =
    QualifiedName(List(name))

  /** Create from namespace + simple name */
  def apply(namespace: String, name: String): QualifiedName =
    QualifiedName(namespace.split("::").toList :+ name)
}

/** Reference to an action, which can be:
  *   - A simple name like "Read"
  *   - A string name like "view-document"
  *   - A qualified name like ExampleNS::Action::"Write"
  */
sealed trait ActionRef {
  def name: String
}

object ActionRef {
  final case class Ident(n: String) extends ActionRef {
    def name: String = n
  }
  final case class Str(n: String) extends ActionRef {
    def name: String = n
  }
  final case class Qualified(path: QualifiedName, n: String) extends ActionRef {
    def name: String = n
  }
}

// ============================================================================
// Entity Declarations
// ============================================================================

/** Entity type declaration.
  *
  * Examples:
  * {{{
  * entity User in [Group] = {
  *   "email": String,
  *   "active": Bool,
  * };
  *
  * entity Document in [Folder, Account] tags String;
  *
  * entity Status enum ["draft", "published", "archived"];
  * }}}
  *
  * @param name
  *   The entity type name
  * @param memberOf
  *   Parent entity types (from `in [...]` clause)
  * @param shape
  *   Record type defining attributes (None = no attributes)
  * @param tags
  *   Type for entity tags (None = no tags)
  * @param enumValues
  *   For enum entities, the allowed EID values
  * @param annotations
  *   Annotations like @doc("...")
  */
final case class EntityDecl(
    name: String,
    memberOf: List[QualifiedName] = Nil,
    shape: Option[RecordType] = None,
    tags: Option[SchemaType] = None,
    enumValues: Option[List[String]] = None,
    annotations: List[Annotation] = Nil
) {

  /** Is this entity a root (no parents)? */
  def isRoot: Boolean = memberOf.isEmpty

  /** Is this an enum entity? */
  def isEnum: Boolean = enumValues.isDefined

  /** Get all attributes (empty if no shape) */
  def attributes: List[AttributeDecl] = shape.map(_.attributes).getOrElse(Nil)

  /** Get a specific attribute by name */
  def attribute(attrName: String): Option[AttributeDecl] =
    attributes.find(_.name == attrName)

  /** Get required attributes */
  def requiredAttributes: List[AttributeDecl] =
    attributes.filterNot(_.optional)

  /** Get optional attributes */
  def optionalAttributes: List[AttributeDecl] =
    attributes.filter(_.optional)

  /** Get the @doc annotation value, if any */
  def doc: Option[String] =
    annotations.find(_.name == "doc").map(_.value)

  /** Get a custom annotation value by name */
  def annotation(annotName: String): Option[String] =
    annotations.find(_.name == annotName).map(_.value)

  /** Check if this entity has a specific annotation */
  def hasAnnotation(annotName: String): Boolean =
    annotations.exists(_.name == annotName)
}

// ============================================================================
// Action Declarations
// ============================================================================

/** Action declaration.
  *
  * Example:
  * {{{
  * action "Mission::create" appliesTo {
  *   principal: [User, CustomerMembership],
  *   resource: Location,
  *   context: { "reason": String },
  * };
  * }}}
  *
  * @param name
  *   The action name (can include "::" for domain grouping)
  * @param memberOf
  *   Parent action groups
  * @param appliesTo
  *   Principal, resource, and context constraints
  * @param annotations
  *   Annotations like @doc("...")
  */
final case class ActionDecl(
    name: String,
    memberOf: List[ActionRef] = Nil,
    appliesTo: Option[AppliesTo] = None,
    annotations: List[Annotation] = Nil
) {

  /** Parse the domain from action name. E.g., "Mission::create" -> Some("Mission")
    */
  def domain: Option[String] = {
    val parts = name.split("::")
    if (parts.length >= 2) Some(parts.head) else None
  }

  /** Get the action name without domain prefix. E.g., "Mission::create" -> "create"
    */
  def actionName: String = {
    val parts = name.split("::")
    if (parts.length >= 2) parts.last else name
  }

  /** Get principal types this action applies to */
  def principalTypes: List[QualifiedName] =
    appliesTo.map(_.principals).getOrElse(Nil)

  /** Get resource types this action applies to */
  def resourceTypes: List[QualifiedName] =
    appliesTo.map(_.resources).getOrElse(Nil)

  /** Get the context record type */
  def contextType: Option[RecordType] =
    appliesTo.flatMap(_.context)

  /** Get the @doc annotation value, if any */
  def doc: Option[String] =
    annotations.find(_.name == "doc").map(_.value)

  /** Get a custom annotation value by name */
  def annotation(annotName: String): Option[String] =
    annotations.find(_.name == annotName).map(_.value)

  /** Determine if this is a "collection" action (operates on parent, not instance). Heuristic: if resource type differs
    * from domain, it's a collection action.
    */
  def isCollectionAction: Boolean = domain.exists { d =>
    resourceTypes.forall(_.simple != d)
  }
}

/** The appliesTo clause of an action.
  *
  * @param principals
  *   Entity types that can be the principal
  * @param resources
  *   Entity types that can be the resource
  * @param context
  *   Optional context record type
  */
final case class AppliesTo(
    principals: List[QualifiedName],
    resources: List[QualifiedName],
    context: Option[RecordType] = None
)

// ============================================================================
// Common Type Declarations
// ============================================================================

/** Common type declaration (type alias).
  *
  * Example:
  * {{{
  * type PersonName = {
  *   "first": String,
  *   "last": String,
  * };
  * }}}
  */
final case class CommonTypeDecl(
    name: String,
    typeExpr: SchemaType,
    annotations: List[Annotation] = Nil
) {
  def doc: Option[String] =
    annotations.find(_.name == "doc").map(_.value)

  /** Get a custom annotation value by name */
  def annotation(annotName: String): Option[String] =
    annotations.find(_.name == annotName).map(_.value)
}

// ============================================================================
// Schema Types
// ============================================================================

/** Type expressions used in Cedar schemas.
  *
  * Covers:
  *   - Primitive types: String, Long, Bool
  *   - Extension types: ipaddr, decimal, datetime, duration
  *   - Entity references: User, Namespace::Entity
  *   - Composite types: Set<T>, records
  *   - Common type references
  */
sealed trait SchemaType {

  /** Convert to Cedar schema syntax string */
  def toSchemaString: String
}

object SchemaType {
  final case class Primitive(t: PrimitiveType) extends SchemaType {
    def toSchemaString: String = t.name
  }
  final case class Extension(t: ExtensionType) extends SchemaType {
    def toSchemaString: String = t.name
  }
  final case class EntityRef(name: QualifiedName) extends SchemaType {
    def toSchemaString: String = name.value
  }
  final case class SetOf(elementType: SchemaType) extends SchemaType {
    def toSchemaString: String = s"Set<${elementType.toSchemaString}>"
  }
  final case class Record(record: RecordType) extends SchemaType {
    def toSchemaString: String = record.toSchemaString
  }
  final case class TypeRef(name: QualifiedName) extends SchemaType {
    def toSchemaString: String = name.value
  }

  // Convenience constructors
  val String: SchemaType = Primitive(PrimitiveType.String)
  val Long: SchemaType = Primitive(PrimitiveType.Long)
  val Bool: SchemaType = Primitive(PrimitiveType.Bool)

  def entity(name: String): SchemaType = EntityRef(QualifiedName.simple(name))
  def entity(name: QualifiedName): SchemaType = EntityRef(name)
  def set(elementType: SchemaType): SchemaType = SetOf(elementType)
}

/** Cedar primitive types */
sealed trait PrimitiveType {
  def name: String
}

object PrimitiveType {
  case object String extends PrimitiveType { val name = "String" }
  case object Long extends PrimitiveType { val name = "Long" }
  case object Bool extends PrimitiveType { val name = "Bool" }
}

/** Cedar extension types */
sealed trait ExtensionType {
  def name: String
}

object ExtensionType {
  case object ipaddr extends ExtensionType { val name = "ipaddr" }
  case object decimal extends ExtensionType { val name = "decimal" }
  case object datetime extends ExtensionType { val name = "datetime" }
  case object duration extends ExtensionType { val name = "duration" }
}

/** Record type containing attribute declarations.
  */
final case class RecordType(
    attributes: List[AttributeDecl]
) {

  /** Get required attributes */
  def required: List[AttributeDecl] = attributes.filterNot(_.optional)

  /** Get optional attributes */
  def optional: List[AttributeDecl] = attributes.filter(_.optional)

  /** Check if this record has a specific attribute */
  def hasAttribute(attrName: String): Boolean = attributes.exists(_.name == attrName)

  /** Get attribute by name */
  def get(attrName: String): Option[AttributeDecl] = attributes.find(_.name == attrName)

  /** Convert to Cedar schema syntax */
  def toSchemaString: String = {
    if (attributes.isEmpty) "{}"
    else {
      val attrStrs = attributes.map { attr =>
        val opt = if (attr.optional) "?" else ""
        s""""${attr.name}"$opt: ${attr.typeExpr.toSchemaString}"""
      }
      s"{ ${attrStrs.mkString(", ")} }"
    }
  }
}

object RecordType {
  val empty: RecordType = RecordType(Nil)
}

/** Attribute declaration within a record.
  *
  * @param name
  *   Attribute name (can be identifier or string)
  * @param typeExpr
  *   The attribute's type
  * @param optional
  *   Whether the attribute is optional (indicated by ?)
  * @param annotations
  *   Annotations on this attribute
  */
final case class AttributeDecl(
    name: String,
    typeExpr: SchemaType,
    optional: Boolean = false,
    annotations: List[Annotation] = Nil
) {
  def doc: Option[String] =
    annotations.find(_.name == "doc").map(_.value)

  /** Get a custom annotation value by name */
  def annotation(annotName: String): Option[String] =
    annotations.find(_.name == annotName).map(_.value)
}

// ============================================================================
// Annotations
// ============================================================================

/** Schema annotation like @doc("description") or @refinement("com.example.CustomerId").
  *
  * Cedar defines `@doc` as a standard annotation. Cedar4s adds `@refinement` for specifying typed ID wrappers. All
  * other annotations are parsed generically and can be queried by name.
  *
  * Annotations can have one or more string values:
  *   - `@doc("description")` - single value
  *   - `@custom("a", "b", "c")` - multiple values
  *
  * @param name
  *   The annotation name (without @)
  * @param values
  *   The annotation values (at least one)
  */
final case class Annotation(
    name: String,
    values: List[String]
) {
  require(values.nonEmpty, "Annotation must have at least one value")

  /** Get the first (or only) value - for backwards compatibility with @doc */
  def value: String = values.head

  /** Get value at index, if present */
  def valueAt(index: Int): Option[String] = values.lift(index)

  /** Check if this is a single-value annotation */
  def isSingleValue: Boolean = values.size == 1
}

object Annotation {

  /** Create a single-value annotation */
  def apply(name: String, value: String): Annotation =
    Annotation(name, List(value))
}
