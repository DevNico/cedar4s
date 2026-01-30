package cedar4s.client

import cedar4s.auth.{CedarAction, CedarAuthError, CedarResource, Principal}
import cedar4s.entities.{CedarEntities, CedarPrincipal}

import java.time.Instant

/** Response from an authorization check, capturing full context.
  *
  * Named to align with Cedar Rust's Response type, which provides `.decision()` and `.diagnostics()` methods.
  *
  * This type is designed for interceptors/middleware to enable:
  *   - Auditing: Log who did what, when, and whether it was allowed
  *   - Metrics: Track authorization latency and decision patterns
  *   - Tracing: Correlate authorization decisions with distributed traces
  *   - Debugging: Capture full context for troubleshooting policy issues
  *
  * ==Example==
  *
  * {{{
  * // Custom audit logger
  * class AuditLogger[F[_]: Async] extends AuthInterceptor[F] {
  *   def onResponse(response: AuthorizationResponse): F[Unit] =
  *     if (!response.allowed) {
  *       logDenial(
  *         principal = response.principal,
  *         action = response.action,
  *         resource = response.resource,
  *         reason = response.decision.denyReason
  *       )
  *     } else {
  *       F.unit
  *     }
  * }
  *
  * // Register interceptor
  * val factory = CedarRuntime(engine, store, buildPrincipal)
  *   .withInterceptor(auditLogger)
  * }}}
  *
  * @param timestamp
  *   When the authorization check occurred
  * @param durationNanos
  *   How long the authorization check took (in nanoseconds)
  * @param principal
  *   The principal that attempted the action
  * @param cedarPrincipal
  *   The Cedar principal entity with attributes
  * @param action
  *   The action that was attempted
  * @param resource
  *   The resource that was accessed
  * @param context
  *   The context attributes for the request
  * @param entities
  *   All entities loaded for this authorization decision
  * @param decision
  *   The authorization decision from Cedar
  * @param errors
  *   Any errors that occurred during authorization (empty if successful)
  */
final case class AuthorizationResponse(
    // Timing
    timestamp: Instant,
    durationNanos: Long,

    // Request info
    principal: Principal,
    cedarPrincipal: CedarPrincipal,
    action: CedarAction,
    resource: CedarResource,
    context: CedarContext,

    // Entities loaded for this decision
    entities: CedarEntities,

    // Decision
    decision: CedarDecision,

    // Errors (if any occurred during evaluation)
    errors: List[CedarAuthError]
) {

  /** Convenience accessor - true if decision was Allow */
  def allowed: Boolean = decision.allow

  /** Convenience accessor - true if decision was Deny */
  def denied: Boolean = !decision.allow

  /** Duration in milliseconds */
  def durationMs: Long = durationNanos / 1_000_000

  /** Duration in microseconds */
  def durationMicros: Long = durationNanos / 1_000
}
