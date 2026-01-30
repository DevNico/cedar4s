package cedar4s.observability.audit

import cedar4s.client.{AuthInterceptor, AuthorizationResponse}
import cedar4s.capability.Functor

/** AuthInterceptor that converts authorization responses to audit events.
  *
  * This interceptor bridges Cedar's authorization flow with the audit logging system. It automatically captures:
  *   - Authorization decisions (allow/deny)
  *   - Principal and resource information
  *   - Action names
  *   - Decision timing
  *   - Policy evaluations
  *
  * ==Usage with CedarRuntime==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * val auditLogger = JsonAuditLogger.stdout[Future]()
  * val interceptor = AuditInterceptor(auditLogger)
  *
  * val runtime = CedarRuntime(engine, store, resolver)
  *   .withInterceptor(interceptor)
  * }}}
  *
  * ==Custom Event Enrichment==
  *
  * You can add custom context to events by wrapping the interceptor:
  *
  * {{{
  * val enrichedInterceptor = AuditInterceptor(
  *   logger = auditLogger,
  *   sessionIdExtractor = _ => Some("session-123"),
  *   requestIdExtractor = _ => Some("req-456"),
  *   contextExtractor = response => Map(
  *     "clientIp" -> "192.168.1.1",
  *     "userAgent" -> "Mozilla/5.0"
  *   )
  * )
  * }}}
  *
  * @param logger
  *   The audit logger to write events to
  * @param sessionIdExtractor
  *   Optional function to extract session ID from response
  * @param requestIdExtractor
  *   Optional function to extract request ID from response
  * @param contextExtractor
  *   Optional function to extract additional context
  * @param F
  *   Functor instance for effect type F
  * @tparam F
  *   The effect type
  */
final class AuditInterceptor[F[_]](
    logger: AuditLogger[F],
    sessionIdExtractor: AuthorizationResponse => Option[String] = _ => None,
    requestIdExtractor: AuthorizationResponse => Option[String] = _ => None,
    contextExtractor: AuthorizationResponse => Map[String, String] = _ => Map.empty
)(implicit F: Functor[F])
    extends AuthInterceptor[F] {

  import cedar4s.capability.Functor.FunctorOps

  def onResponse(response: AuthorizationResponse): F[Unit] = {
    val event = toAuditEvent(response)
    logger.logDecision(event)
  }

  private def toAuditEvent(response: AuthorizationResponse): AuthorizationEvent = {
    val principal = PrincipalInfo(
      entityType = response.principal.entityType,
      entityId = response.principal.entityId
    )

    val resource = ResourceInfo(
      entityType = response.resource.entityType,
      entityId = response.resource.entityId
    )

    val decision = Decision(
      allow = response.decision.allow,
      policies = response.decision.diagnostics
        .map(d => if (response.decision.allow) d.policiesSatisfied else d.policiesDenied)
        .getOrElse(Nil)
    )

    val reason = if (response.denied) response.decision.denyReason else None

    AuthorizationEvent(
      timestamp = response.timestamp,
      principal = principal,
      action = response.action.cedarAction,
      resource = resource,
      decision = decision,
      reason = reason,
      durationNanos = response.durationNanos,
      context = contextExtractor(response),
      sessionId = sessionIdExtractor(response),
      requestId = requestIdExtractor(response)
    )
  }
}

object AuditInterceptor {

  /** Create an audit interceptor with default extractors.
    *
    * @param logger
    *   The audit logger to write events to
    * @param F
    *   Functor instance for effect type F
    * @tparam F
    *   The effect type
    */
  def apply[F[_]: Functor](logger: AuditLogger[F]): AuditInterceptor[F] =
    new AuditInterceptor[F](logger)

  /** Create an audit interceptor with custom extractors.
    *
    * @param logger
    *   The audit logger to write events to
    * @param sessionIdExtractor
    *   Function to extract session ID from response
    * @param requestIdExtractor
    *   Function to extract request ID from response
    * @param contextExtractor
    *   Function to extract additional context
    * @param F
    *   Functor instance for effect type F
    * @tparam F
    *   The effect type
    */
  def withExtractors[F[_]: Functor](
      logger: AuditLogger[F],
      sessionIdExtractor: AuthorizationResponse => Option[String] = _ => None,
      requestIdExtractor: AuthorizationResponse => Option[String] = _ => None,
      contextExtractor: AuthorizationResponse => Map[String, String] = _ => Map.empty
  ): AuditInterceptor[F] =
    new AuditInterceptor[F](logger, sessionIdExtractor, requestIdExtractor, contextExtractor)
}
