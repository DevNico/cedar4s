package cedar4s.client

import cedar4s.capability.{Applicative, MonadError}

/** Interceptor for authorization responses.
  *
  * Interceptors can be used for auditing, metrics, tracing, debugging, or any other cross-cutting concern that needs to
  * observe authorization decisions.
  *
  * Interceptors are composable - use [[AuthInterceptor.combine]] to run multiple interceptors sequentially.
  *
  * ==Example: Audit Logger==
  *
  * {{{
  * class AuditLogger[F[_]: Async](logger: Logger[F]) extends AuthInterceptor[F] {
  *   def onResponse(response: AuthorizationResponse): F[Unit] = {
  *     if (response.denied) {
  *       logger.warn(
  *         s"Authorization denied: \${response.principal.entityId} " +
  *         s"attempted \${response.action.name} on \${response.resource.toCedarEntity}"
  *       )
  *     } else {
  *       logger.info(
  *         s"Authorization granted: \${response.principal.entityId} " +
  *         s"performed \${response.action.name} on \${response.resource.toCedarEntity}"
  *       )
  *     }
  *   }
  * }
  * }}}
  *
  * ==Example: Metrics Collector==
  *
  * {{{
  * class MetricsCollector[F[_]: Sync](metrics: Metrics[F]) extends AuthInterceptor[F] {
  *   def onResponse(response: AuthorizationResponse): F[Unit] = {
  *     for {
  *       _ <- metrics.histogram("auth.duration_ms").record(response.durationMs)
  *       _ <- metrics.counter("auth.decisions")
  *              .increment(Map("allowed" -> response.allowed.toString))
  *     } yield ()
  *   }
  * }
  * }}}
  *
  * ==Combining Interceptors==
  *
  * {{{
  * val combined = AuthInterceptor.combine(
  *   auditLogger,
  *   metricsCollector,
  *   tracingInterceptor
  * )
  *
  * val factory = CedarRuntime(engine, store, buildPrincipal)
  *   .withInterceptor(combined)
  * }}}
  *
  * @tparam F
  *   The effect type (Future, IO, etc.)
  */
trait AuthInterceptor[F[_]] {

  /** Called after an authorization decision is made.
    *
    * This method receives the full context of the authorization request and decision, including timing information, the
    * principal, action, resource, entities loaded, and the decision itself.
    *
    * Note: In the default CedarSessionRunner implementation, errors from this method are caught and suppressed to
    * prevent interceptor failures from blocking authorization decisions. This allows fire-and-forget logging/metrics
    * patterns.
    *
    * @param response
    *   The authorization response with full context
    * @return
    *   Effect that performs the interception logic
    */
  def onResponse(response: AuthorizationResponse): F[Unit]
}

object AuthInterceptor {

  /** No-op interceptor that does nothing.
    *
    * Useful as a default when no interception is needed.
    */
  def noop[F[_]](implicit F: Applicative[F]): AuthInterceptor[F] =
    new AuthInterceptor[F] {
      def onResponse(response: AuthorizationResponse): F[Unit] = F.pure(())
    }

  /** Combine multiple interceptors into a single interceptor that runs them sequentially.
    *
    * Interceptors are executed in the order provided. If any interceptor fails, subsequent interceptors will not run.
    *
    * @param interceptors
    *   The interceptors to combine
    * @return
    *   A single interceptor that runs all provided interceptors
    */
  def combine[F[_]](interceptors: AuthInterceptor[F]*)(implicit F: MonadError[F]): AuthInterceptor[F] =
    new AuthInterceptor[F] {
      def onResponse(response: AuthorizationResponse): F[Unit] = {
        interceptors.foldLeft(F.pure(())) { (acc, interceptor) =>
          F.flatMap(acc)(_ => interceptor.onResponse(response))
        }
      }
    }
}
