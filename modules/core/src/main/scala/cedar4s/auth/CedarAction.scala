package cedar4s.auth

/** Base traits for Cedar actions and resources.
  *
  * These provide the minimal interface needed by the authorization DSL. Generated code creates concrete implementations
  * with phantom types for compile-time safety.
  *
  * The design follows the "tagless final" pattern for effect polymorphism - the actual authorization execution is
  * delegated to an [[CedarSession]] typeclass.
  */

/** Base trait for Cedar actions.
  *
  * Generated actions extend this with phantom type members:
  * {{{
  * sealed trait MissionAction extends CedarAction {
  *   type Domain = PolicyDomain.Mission.type
  *   type Ownership = IndirectOwnership
  * }
  * }}}
  */
trait CedarAction {

  /** Simple action name (e.g., "read", "update") */
  def name: String

  /** Full Cedar action reference (e.g., "Namespace::Action::\"Entity::read\"") */
  def cedarAction: String

  /** Whether this is a collection action (create/list) */
  def isCollectionAction: Boolean
}

/** Base trait for Cedar resources.
  *
  * Generated resources extend this with phantom type parameters:
  * {{{
  * case class Resource[D <: PolicyDomain, O](
  *   entityType: String,
  *   entityId: Option[String],
  *   parents: List[(String, String)]
  * ) extends CedarResource
  * }}}
  */
trait CedarResource {

  /** Entity type name (e.g., "Mission") */
  def entityType: String

  /** Entity ID (None for collection resources) */
  def entityId: Option[String]

  /** Parent entity chain: List of (type, id) pairs */
  def parents: List[(String, String)]

  /** Format as Cedar entity UID string */
  def toCedarEntity: String
}
