package cedar4s.client

import cedar4s.auth.{CedarSession, ContextSchema, Principal, PrincipalResolver}
import cedar4s.entities.{CedarEntities, CedarEntity, CedarEntityType, CedarPrincipal, EntityStore}
import cedar4s.capability.Sync
import cedar4s.capability.Functor.FunctorOps

/** Configured Cedar runtime that produces request-scoped sessions.
  *
  * @tparam F
  *   Effect type (requires implicit Sync[F] capability)
  * @tparam P
  *   Principal entity type (requires implicit CedarEntityType[P] instance)
  */
final class CedarRuntime[F[_], P](
    engine: CedarEngine[F],
    entityStore: EntityStore[F],
    resolver: PrincipalResolver[F, P],
    interceptor: AuthInterceptor[F]
)(implicit F: Sync[F], entityType: CedarEntityType[P]) {

  private def resolvePrincipal(principal: Principal): F[CedarPrincipal] =
    F.map(resolver.resolve(principal)) {
      case Some(entity) =>
        val cedarEntity = entityType.toCedarEntity(entity)
        CedarPrincipal(cedarEntity.uid, CedarEntities(cedarEntity))
      case None =>
        // If principal can't be resolved, return empty CedarPrincipal with default entity
        CedarPrincipal(principal.toCedarEntity, CedarEntities.empty)
    }

  def session(principal: Principal, context: ContextSchema = ContextSchema.empty): CedarSession[F] =
    new CedarSessionRunner[F](
      sessionPrincipal = principal,
      sessionContext = context,
      engine = engine,
      store = entityStore,
      resolvePrincipal = resolvePrincipal,
      interceptor = interceptor
    )

  /** Add an interceptor to this runtime.
    *
    * The new interceptor will be combined with any existing interceptors and will run after them in sequence.
    *
    * @param newInterceptor
    *   The interceptor to add
    * @return
    *   A new CedarRuntime with the combined interceptors
    */
  def withInterceptor(newInterceptor: AuthInterceptor[F]): CedarRuntime[F, P] =
    new CedarRuntime[F, P](engine, entityStore, resolver, AuthInterceptor.combine(interceptor, newInterceptor))
}

object CedarRuntime {

  /** Create a CedarRuntime with a principal resolver.
    *
    * The resolver should return Some(principal entity) when the principal exists, or None if it cannot be resolved. The
    * framework will automatically convert the principal entity to CedarPrincipal using its CedarEntityType instance.
    *
    * @param engine
    *   Cedar authorization engine
    * @param entityStore
    *   Entity store for loading entities
    * @param resolver
    *   Principal resolver that returns principal entities
    * @tparam F
    *   Effect type
    * @tparam P
    *   Principal entity type (must extend PrincipalEntity and have CedarEntityType)
    */
  def apply[F[_], P](
      engine: CedarEngine[F],
      entityStore: EntityStore[F],
      resolver: PrincipalResolver[F, P]
  )(implicit F: Sync[F], entityType: CedarEntityType[P]): CedarRuntime[F, P] =
    new CedarRuntime[F, P](engine, entityStore, resolver, AuthInterceptor.noop[F])

  /** Create a resolver from a function that returns an optional principal entity.
    *
    * @param build
    *   Function that takes a Principal and returns F[Option[P]]
    * @tparam F
    *   Effect type
    * @tparam P
    *   Principal entity type
    */
  def resolverFrom[F[_], P](build: Principal => F[Option[P]]): PrincipalResolver[F, P] =
    (principal: Principal) => build(principal)
}
