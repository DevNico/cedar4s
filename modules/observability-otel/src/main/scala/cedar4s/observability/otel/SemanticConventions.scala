package cedar4s.observability.otel

/** Cedar-specific semantic conventions for OpenTelemetry attributes.
  *
  * These conventions follow OpenTelemetry's attribute naming guidelines:
  *   - Use namespaced keys (cedar.*)
  *   - Use snake_case for multi-word names
  *   - Keep names concise but descriptive
  *   - Align with Cedar domain concepts
  *
  * ==Attribute Categories==
  *
  *   - **cedar.principal.***: Information about the principal making the request
  *   - **cedar.action.***: The action being authorized
  *   - **cedar.resource.***: The target resource
  *   - **cedar.decision.***: Authorization decision and diagnostics
  *   - **cedar.performance.***: Performance metrics
  *
  * ==Usage==
  *
  * {{{
  * import io.opentelemetry.api.trace.Span
  * import cedar4s.observability.otel.SemanticConventions._
  *
  * span.setAttribute(CEDAR_PRINCIPAL_TYPE, "User")
  * span.setAttribute(CEDAR_PRINCIPAL_ID, "alice")
  * span.setAttribute(CEDAR_ACTION, "Document::Action::\"View\"")
  * span.setAttribute(CEDAR_DECISION, "allow")
  * }}}
  */
object SemanticConventions {

  // ===========================================================================
  // Principal Attributes
  // ===========================================================================

  /** The Cedar entity type of the principal (e.g., "User", "ServiceAccount").
    */
  val CEDAR_PRINCIPAL_TYPE: String = "cedar.principal.type"

  /** The Cedar entity ID of the principal (e.g., "alice", "service-123").
    */
  val CEDAR_PRINCIPAL_ID: String = "cedar.principal.id"

  /** The full Cedar entity UID of the principal (e.g., User::"alice").
    */
  val CEDAR_PRINCIPAL_UID: String = "cedar.principal.uid"

  // ===========================================================================
  // Action Attributes
  // ===========================================================================

  /** The Cedar action being authorized (e.g., "Document::Action::\"View\"").
    */
  val CEDAR_ACTION: String = "cedar.action"

  /** The action type without namespace (e.g., "View", "Edit").
    */
  val CEDAR_ACTION_NAME: String = "cedar.action.name"

  // ===========================================================================
  // Resource Attributes
  // ===========================================================================

  /** The Cedar entity type of the resource (e.g., "Document", "Folder").
    */
  val CEDAR_RESOURCE_TYPE: String = "cedar.resource.type"

  /** The Cedar entity ID of the resource (e.g., "doc-123").
    */
  val CEDAR_RESOURCE_ID: String = "cedar.resource.id"

  /** The full Cedar entity UID of the resource (e.g., Document::"doc-123").
    */
  val CEDAR_RESOURCE_UID: String = "cedar.resource.uid"

  // ===========================================================================
  // Decision Attributes
  // ===========================================================================

  /** The authorization decision ("allow" or "deny").
    */
  val CEDAR_DECISION: String = "cedar.decision"

  /** The reason for denial (if decision was "deny").
    */
  val CEDAR_DENY_REASON: String = "cedar.decision.deny_reason"

  /** Number of reasons in the deny diagnostics.
    */
  val CEDAR_DENY_REASON_COUNT: String = "cedar.decision.deny_reason_count"

  // ===========================================================================
  // Performance Attributes
  // ===========================================================================

  /** Duration of the authorization check in milliseconds.
    */
  val CEDAR_DURATION_MS: String = "cedar.duration_ms"

  /** Number of entities loaded for this decision.
    */
  val CEDAR_ENTITIES_COUNT: String = "cedar.entities.count"

  // ===========================================================================
  // Error Attributes
  // ===========================================================================

  /** Error message if authorization failed.
    */
  val CEDAR_ERROR: String = "cedar.error"

  /** Error type/category.
    */
  val CEDAR_ERROR_TYPE: String = "cedar.error.type"

  // ===========================================================================
  // Context Attributes
  // ===========================================================================

  /** Whether context was provided for this request.
    */
  val CEDAR_HAS_CONTEXT: String = "cedar.context.present"

  /** Number of context attributes provided.
    */
  val CEDAR_CONTEXT_SIZE: String = "cedar.context.size"

  // ===========================================================================
  // Span Event Names
  // ===========================================================================

  /** Event logged when authorization is denied.
    */
  val EVENT_AUTHORIZATION_DENIED: String = "cedar.authorization.denied"

  /** Event logged when principal resolution fails.
    */
  val EVENT_PRINCIPAL_RESOLUTION_FAILED: String = "cedar.principal.resolution_failed"

  /** Event logged when entity loading fails.
    */
  val EVENT_ENTITY_LOADING_FAILED: String = "cedar.entity.loading_failed"
}
