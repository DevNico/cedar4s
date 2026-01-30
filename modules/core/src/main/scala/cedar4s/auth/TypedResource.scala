package cedar4s.auth

/** Marker trait for policy domains.
  *
  * Policy domains represent the entity types in your Cedar schema. Generated code creates singleton objects extending
  * this trait:
  *
  * {{{
  * object PolicyDomain {
  *   object User extends PolicyDomain
  *   object Folder extends PolicyDomain
  *   object Document extends PolicyDomain
  *   // ...
  * }
  * }}}
  *
  * These are used as phantom types to provide type-safe parent access.
  */
trait PolicyDomain

/** Evidence that domain D has parent P in the entity hierarchy.
  *
  * Generated from Cedar schema `entity X in [Y, Z]` declarations:
  *
  * {{{
  * // From Cedar schema:
  * // entity Document in [Folder] { ... }
  *
  * // Generated evidence:
  * given HasParent[PolicyDomain.Document.type, PolicyDomain.Folder.type] with {}
  * }}}
  *
  * This enables type-safe parent access on resources:
  *
  * {{{
  * val resource: TypedResource[PolicyDomain.Document.type] = ...
  * val folderId: Option[String] = resource.parent[PolicyDomain.Folder.type]  // Compiles!
  * val userId: Option[String] = resource.parent[PolicyDomain.User.type]      // Won't compile!
  * }}}
  *
  * @tparam D
  *   Child domain type
  * @tparam P
  *   Parent domain type
  */
trait HasParent[D <: PolicyDomain, P <: PolicyDomain]

object HasParent {

  /** Summon evidence from implicit scope */
  def apply[D <: PolicyDomain, P <: PolicyDomain](implicit ev: HasParent[D, P]): HasParent[D, P] = ev
}

/** Type-safe resource with parent chain access.
  *
  * This extends [[CedarResource]] with type-safe parent accessors. Generated code creates concrete implementations for
  * each entity type.
  *
  * ==Example==
  *
  * {{{
  * // Generated resource type
  * final case class DocumentResource(
  *     folderId: String,
  *     documentId: String
  * ) extends TypedResource[PolicyDomain.Document.type] {
  *   val entityType = "Document"
  *   val entityId = Some(documentId)
  *   val parents = List("Folder" -> folderId)
  *
  *   // Type-safe parent access
  *   def parent[P <: PolicyDomain](implicit ev: HasParent[PolicyDomain.Document.type, P]): Option[String] =
  *     parents.collectFirst { case (t, id) if matchesType[P](t) => id }
  * }
  *
  * // Usage:
  * val resource: DocumentResource = ...
  * val folderId: Option[String] = resource.parent[PolicyDomain.Folder.type]  // Some("folder-1")
  * }}}
  *
  * @tparam D
  *   Domain type (e.g., PolicyDomain.Document.type)
  */
trait TypedResource[D <: PolicyDomain] extends CedarResource {

  /** Get the parent entity ID of type P.
    *
    * Requires compile-time evidence that D has parent P.
    *
    * @tparam P
    *   Parent domain type
    * @return
    *   The parent entity ID, or None if not found
    */
  def parent[P <: PolicyDomain](implicit ev: HasParent[D, P]): Option[String]

  /** Get the immediate parent (first in chain).
    */
  def immediateParent: Option[(String, String)] = parents.headOption

  /** Get all ancestors of the specified type.
    */
  def ancestorsOfType(entityType: String): List[String] =
    parents.collect { case (t, id) if t == entityType => id }
}

/** Companion object for TypedResource with utilities.
  */
object TypedResource {

  /** Helper to match a type name against a domain type.
    *
    * Used in generated code for parent lookup:
    * {{{
    * def parent[P <: PolicyDomain](implicit ev: HasParent[D, P]): Option[String] =
    *   parents.collectFirst { case (t, id) if matchesType[P](t) => id }
    * }}}
    */
  def matchesType[P <: PolicyDomain](typeName: String)(implicit tag: DomainTypeTag[P]): Boolean =
    tag.typeName == typeName
}

/** Type tag for domain types.
  *
  * Generated for each domain to enable runtime type matching:
  *
  * {{{
  * given DomainTypeTag[PolicyDomain.Document.type] with {
  *   val typeName = "Document"
  * }
  * }}}
  */
trait DomainTypeTag[D <: PolicyDomain] {
  def typeName: String
}

object DomainTypeTag {
  def apply[D <: PolicyDomain](implicit tag: DomainTypeTag[D]): DomainTypeTag[D] = tag

  def instance[D <: PolicyDomain](name: String): DomainTypeTag[D] = new DomainTypeTag[D] {
    val typeName: String = name
  }
}
