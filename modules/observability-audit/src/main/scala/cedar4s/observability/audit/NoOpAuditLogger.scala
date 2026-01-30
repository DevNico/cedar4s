package cedar4s.observability.audit

import cedar4s.capability.Applicative

/** No-op audit logger that does nothing.
  *
  * Useful for:
  *   - Testing without generating logs
  *   - Production environments where auditing is disabled
  *   - Development with minimal overhead
  *
  * This implementation has zero overhead - it simply returns a successful unit effect without performing any work.
  *
  * ==Example==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * val logger = NoOpAuditLogger[Future]()
  *
  * // Conditional logging in tests
  * val logger = if (enableAudit) JsonAuditLogger.stdout[Future]()
  *              else NoOpAuditLogger[Future]()
  * }}}
  *
  * @param F
  *   Effect type capability
  * @tparam F
  *   The effect type
  */
final class NoOpAuditLogger[F[_]](implicit F: Applicative[F]) extends AuditLogger[F] {

  def logDecision(event: AuthorizationEvent): F[Unit] = F.pure(())
}

object NoOpAuditLogger {

  /** Create a no-op audit logger.
    *
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def apply[F[_]: Applicative](): NoOpAuditLogger[F] =
    new NoOpAuditLogger[F]()
}
