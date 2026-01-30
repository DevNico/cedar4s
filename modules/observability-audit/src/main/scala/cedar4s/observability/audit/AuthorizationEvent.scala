package cedar4s.observability.audit

import java.time.Instant

/** Comprehensive audit event capturing an authorization decision.
  *
  * This event captures everything needed for security auditing and compliance:
  *   - Who performed the action (principal)
  *   - What action they attempted
  *   - What resource they accessed
  *   - Whether it was allowed or denied
  *   - When it occurred and how long it took
  *   - Additional context for correlation and debugging
  *
  * ==Example==
  *
  * {{{
  * val event = AuthorizationEvent(
  *   timestamp = Instant.now(),
  *   principal = PrincipalInfo("User", "alice"),
  *   action = "Document::View",
  *   resource = ResourceInfo("Document", Some("doc-123")),
  *   decision = Decision(allow = true, policies = List("policy-1")),
  *   reason = None,
  *   durationNanos = 1_500_000,
  *   context = Map("clientIp" -> "192.168.1.1"),
  *   sessionId = Some("session-456"),
  *   requestId = Some("req-789")
  * )
  * }}}
  *
  * @param timestamp
  *   When the authorization check occurred
  * @param principal
  *   Information about the principal (who)
  * @param action
  *   The action that was attempted (what)
  * @param resource
  *   Information about the resource (on what)
  * @param decision
  *   The authorization decision (allow/deny and policies)
  * @param reason
  *   Optional denial reason (only present for denials)
  * @param durationNanos
  *   How long the authorization took (in nanoseconds)
  * @param context
  *   Additional context attributes (e.g., IP, user agent)
  * @param sessionId
  *   Optional session identifier for correlation
  * @param requestId
  *   Optional request identifier for distributed tracing
  */
final case class AuthorizationEvent(
    timestamp: Instant,
    principal: PrincipalInfo,
    action: String,
    resource: ResourceInfo,
    decision: Decision,
    reason: Option[String],
    durationNanos: Long,
    context: Map[String, String] = Map.empty,
    sessionId: Option[String] = None,
    requestId: Option[String] = None
) {

  /** Duration in milliseconds */
  def durationMs: Double = durationNanos / 1_000_000.0

  /** Duration in microseconds */
  def durationMicros: Long = durationNanos / 1_000

  /** Whether the action was allowed */
  def allowed: Boolean = decision.allow

  /** Whether the action was denied */
  def denied: Boolean = !decision.allow
}

/** Information about the principal who attempted the action.
  *
  * @param entityType
  *   The Cedar entity type (e.g., "User", "ServiceAccount")
  * @param entityId
  *   The entity identifier (e.g., "alice", "service-123")
  */
final case class PrincipalInfo(
    entityType: String,
    entityId: String
) {

  /** Format as Cedar entity UID (e.g., "User::\"alice\"") */
  def toCedarUid: String = s"""$entityType::"$entityId""""
}

/** Information about the resource that was accessed.
  *
  * @param entityType
  *   The Cedar entity type (e.g., "Document", "Folder")
  * @param entityId
  *   The entity identifier, if available (None for collection actions)
  */
final case class ResourceInfo(
    entityType: String,
    entityId: Option[String]
) {

  /** Format as Cedar entity UID (e.g., "Document::\"doc-123\"") */
  def toCedarUid: String = entityId match {
    case Some(id) => s"""$entityType::"$id""""
    case None     => entityType
  }
}

/** Authorization decision details.
  *
  * @param allow
  *   Whether the request was allowed
  * @param policies
  *   List of policy IDs that contributed to the decision
  */
final case class Decision(
    allow: Boolean,
    policies: List[String] = Nil
) {

  /** List of policies that allowed the request */
  def policiesSatisfied: List[String] = if (allow) policies else Nil

  /** List of policies that denied the request */
  def policiesDenied: List[String] = if (!allow) policies else Nil
}
