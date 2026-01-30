package cedar4s.observability.audit

/** Audit logger for Cedar authorization decisions.
  *
  * AuditLogger provides a simple, effect-polymorphic interface for recording authorization events. Implementations can
  * write to different destinations:
  *   - SLF4J with structured JSON logging (recommended for production)
  *   - JSON logs (stdout/stderr)
  *   - Rotating log files
  *   - Centralized logging systems (via interceptors)
  *   - Security information and event management (SIEM) systems
  *
  * ==Thread Safety==
  *
  * Implementations should be thread-safe and suitable for concurrent use.
  *
  * ==Usage (SLF4J - Recommended)==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * // Create an SLF4J logger
  * val logger = Slf4jAuditLogger[Future]()
  *
  * // Log an authorization event
  * val event = AuthorizationEvent(
  *   timestamp = Instant.now(),
  *   principal = PrincipalInfo("User", "alice"),
  *   action = "Document::View",
  *   resource = ResourceInfo("Document", Some("doc-123")),
  *   decision = Decision(allow = true),
  *   reason = None,
  *   durationNanos = 1_500_000
  * )
  *
  * logger.logDecision(event)
  * }}}
  *
  * ==Usage (Simple JSON)==
  *
  * {{{
  * // Create a simple JSON logger
  * val logger = JsonAuditLogger.stdout[Future]()
  *
  * // Use the same way as SLF4J logger
  * logger.logDecision(event)
  * }}}
  *
  * ==Integration with CedarRuntime==
  *
  * Use [[AuditInterceptor]] to automatically convert authorization responses to audit events:
  *
  * {{{
  * val auditInterceptor = AuditInterceptor(logger)
  * val runtime = CedarRuntime(engine, store, resolver)
  *   .withInterceptor(auditInterceptor)
  * }}}
  *
  * @tparam F
  *   The effect type (Future, IO, etc.)
  */
trait AuditLogger[F[_]] {

  /** Log an authorization decision event.
    *
    * This method should be non-blocking when possible and handle errors gracefully (e.g., logging failures shouldn't
    * crash the application).
    *
    * @param event
    *   The authorization event to log
    * @return
    *   Effect that completes when the event is logged
    */
  def logDecision(event: AuthorizationEvent): F[Unit]
}

object AuditLogger {

  /** Combine multiple audit loggers into a single logger.
    *
    * Events will be logged to all loggers in sequence. If any logger fails, the error will propagate and subsequent
    * loggers won't run.
    *
    * @param loggers
    *   The loggers to combine
    * @return
    *   A single logger that logs to all provided loggers
    */
  def combine[F[_]](loggers: AuditLogger[F]*)(implicit F: cedar4s.capability.MonadError[F]): AuditLogger[F] =
    new AuditLogger[F] {
      def logDecision(event: AuthorizationEvent): F[Unit] = {
        loggers.foldLeft(F.pure(())) { (acc, logger) =>
          F.flatMap(acc)(_ => logger.logDecision(event))
        }
      }
    }
}
