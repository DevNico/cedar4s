package cedar4s.auth

import cedar4s.schema.CedarEntityUid

/** Base trait for Cedar principals.
  *
  * Principals represent the "who" in authorization - users, services, teams, etc. Generated code creates concrete
  * implementations for each principal type defined in the Cedar schema.
  *
  * ==Usage==
  *
  * Principals are typically generated from Cedar schema entity definitions that are referenced in action
  * `appliesTo { principal: [...] }` blocks:
  *
  * {{{
  * // Generated from Cedar schema
  * object Principals {
  *   final case class User(id: String) extends Principal {
  *     val entityType = "User"
  *     val entityId = id
  *   }
  * }
  *
  * // Usage in authorization
  * Document.View("folder-1", "doc-1")
  *   .asPrincipal(Principals.User("alice"))
  *   .require
  * }}}
  *
  * @see
  *   [[Principal.CanPerform]] for compile-time action/principal validation
  */
trait Principal {

  /** Entity type name (e.g., "User", "ServiceAccount", "Team") */
  def entityType: String

  /** Entity ID */
  def entityId: String

  /** Convert to Cedar entity UID */
  def toCedarEntity: CedarEntityUid = CedarEntityUid(entityType, entityId)
}

object Principal {

  /** Type class evidence that action A can be performed by principal P.
    *
    * Generated from Cedar schema `appliesTo { principal: [...] }` blocks. This provides compile-time safety -
    * attempting to use a principal type that isn't allowed for an action results in a compilation error.
    *
    * ==Example==
    *
    * Given this Cedar schema:
    * {{{
    * action view appliesTo {
    *     principal: [User, ServiceAccount],
    *     resource: Document,
    * };
    *
    * action edit appliesTo {
    *     principal: [User],  // ServiceAccount cannot edit!
    *     resource: Document,
    * };
    * }}}
    *
    * The codegen produces:
    * {{{
    * object PrincipalEvidence {
    *   given CanPerform[User, View.type] with {}
    *   given CanPerform[ServiceAccount, View.type] with {}
    *   given CanPerform[User, Edit.type] with {}
    *   // No CanPerform[ServiceAccount, Edit.type] - would cause compile error!
    * }
    * }}}
    *
    * @tparam P
    *   The principal type (e.g., User, ServiceAccount)
    * @tparam A
    *   The action type (e.g., Document.View, Document.Edit)
    */
  trait CanPerform[P <: Principal, A <: CedarAction]

  object CanPerform {

    /** Summon evidence that principal P can perform action A */
    def apply[P <: Principal, A <: CedarAction](implicit ev: CanPerform[P, A]): CanPerform[P, A] = ev

    /** Universal instance when principal restrictions are not used.
      *
      * This allows opting out of compile-time principal checking when:
      *   - The Cedar schema doesn't specify principal types
      *   - You want to defer all checking to Cedar runtime
      *
      * Import this explicitly to disable compile-time principal checking:
      * {{{
      * import cedar4s.auth.Principal.CanPerform.anyPrincipalCanPerformAnyAction
      * }}}
      */
    def anyPrincipalCanPerformAnyAction[P <: Principal, A <: CedarAction]: CanPerform[P, A] =
      new CanPerform[P, A] {}

    /** Alias for anyPrincipalCanPerformAnyAction - useful for tests */
    def allow[P <: Principal, A <: CedarAction]: CanPerform[P, A] =
      new CanPerform[P, A] {}
  }
}
