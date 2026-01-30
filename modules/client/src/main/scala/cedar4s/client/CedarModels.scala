package cedar4s.client

import cedar4s.entities.CedarValue
import cedar4s.schema.CedarEntityUid

/** Cedar authorization decision.
  *
  * @param allow
  *   Whether the request is allowed
  * @param diagnostics
  *   Optional diagnostics from Cedar
  */
final case class CedarDecision(
    allow: Boolean,
    diagnostics: Option[CedarDiagnostics] = None
) {

  /** Convenience method for deny reason */
  def denyReason: Option[String] = diagnostics.flatMap(_.reason)
}

/** Diagnostics information from Cedar authorization.
  */
final case class CedarDiagnostics(
    reason: Option[String] = None,
    errors: List[String] = Nil,
    policiesSatisfied: List[String] = Nil,
    policiesDenied: List[String] = Nil
)

object CedarDiagnostics {
  val empty: CedarDiagnostics = CedarDiagnostics()
}

/** Cedar authorization request context.
  *
  * Contains additional context attributes for policy evaluation.
  */
final case class CedarContext(
    attributes: Map[String, CedarValue] = Map.empty
) {
  def +(kv: (String, CedarValue)): CedarContext = CedarContext(attributes + kv)
  def ++(other: CedarContext): CedarContext = CedarContext(attributes ++ other.attributes)
}

object CedarContext {
  val empty: CedarContext = CedarContext()

  def apply(attrs: (String, CedarValue)*): CedarContext = CedarContext(attrs.toMap)
}

/** Cedar authorization request.
  *
  * @param principal
  *   The principal making the request (e.g., User::"alice")
  * @param action
  *   The action being performed (e.g., Action::"read")
  * @param resource
  *   The resource being accessed (e.g., Document::"doc1")
  * @param context
  *   Additional context for the request
  */
final case class CedarRequest(
    principal: CedarEntityUid,
    action: CedarEntityUid,
    resource: CedarEntityUid,
    context: CedarContext = CedarContext.empty
)
