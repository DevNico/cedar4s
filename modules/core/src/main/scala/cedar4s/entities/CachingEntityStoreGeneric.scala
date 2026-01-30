package cedar4s.entities

import cedar4s.Bijection
import cedar4s.capability.Monad
import cedar4s.schema.CedarEntityUid

/** Generic caching decorator for EntityStore[F].
  *
  * This implementation works with any effect type F[_] and any EntityCache[F] implementation. It provides:
  *   - Cache-first lookup for all entity loading operations
  *   - Automatic cache population on successful fetches
  *   - Batch-optimized cache operations
  *
  * ==Usage==
  *
  * The recommended way to use this is through the builder:
  *
  * {{{
  * val cache = CaffeineEntityCache[IO](CaffeineCacheConfig.default)
  *
  * val store = EntityStore.builder[IO]()
  *   .register(userFetcher)
  *   .withCache(cache)
  *   .build()
  * }}}
  *
  * ==Cache Behavior==
  *
  *   - `loadEntity`: Checks cache first, fetches on miss, caches result
  *   - `loadEntities`: Batch lookup in cache, fetch only missing, cache results
  *   - `loadForRequest`: Optimized for auth requests, uses batch cache operations
  *   - `loadForBatch`: Optimized for batch auth checks
  *
  * ==Thread Safety==
  *
  * Thread safety depends on the underlying EntityCache implementation. Caffeine-based caches are fully thread-safe.
  *
  * @param underlying
  *   The underlying entity store to wrap
  * @param cache
  *   The cache implementation to use
  */
class CachingEntityStoreGeneric[F[_]](
    underlying: EntityStore[F],
    cache: EntityCache[F]
)(implicit F: Monad[F])
    extends EntityStore[F] {

  override def loadForRequest(principal: CedarPrincipal, resource: ResourceRef): F[CedarEntities] = {
    val uidsToLoad = resource.uid.toList ++ resource.parentUids
    F.map(loadWithCache(uidsToLoad.toSet))(principal.entities ++ _)
  }

  override def loadForBatch(principal: CedarPrincipal, resources: Seq[ResourceRef]): F[CedarEntities] = {
    val allUids = resources.flatMap(r => r.uid.toList ++ r.parentUids).toSet
    F.map(loadWithCache(allUids))(principal.entities ++ _)
  }

  override def loadEntity(entityType: String, entityId: String): F[Option[CedarEntity]] = {
    val uid = CedarEntityUid(entityType, entityId)
    F.flatMap(cache.get(uid)) {
      case Some(entity) =>
        F.pure(Some(entity))
      case None =>
        F.flatMap(underlying.loadEntity(entityType, entityId)) {
          case Some(entity) =>
            F.map(cache.put(entity))(_ => Some(entity))
          case None =>
            F.pure(None)
        }
    }
  }

  override def loadEntities(uids: Set[CedarEntityUid]): F[CedarEntities] =
    loadWithCache(uids)

  override def loadEntityWithParents(
      entityType: String,
      entityId: String
  ): F[Option[(CedarEntity, List[(String, String)])]] = {
    F.flatMap(underlying.loadEntityWithParents(entityType, entityId)) {
      case Some((entity, parents)) =>
        F.map(cache.put(entity))(_ => Some((entity, parents)))
      case None =>
        F.pure(None)
    }
  }

  /** Invalidate a specific entity from the cache.
    *
    * @param uid
    *   The entity UID to invalidate
    */
  def invalidate(uid: CedarEntityUid): F[Unit] = cache.invalidate(uid)

  /** Invalidate a specific entity from the cache using typed IDs.
    */
  def invalidateEntity[A, Id](id: Id)(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): F[Unit] =
    cache.invalidateEntity[A, Id](id)

  /** Invalidate all entities of a specific type.
    *
    * @param entityType
    *   The Cedar entity type (e.g., "MyApp::User")
    */
  def invalidateType(entityType: String): F[Unit] = cache.invalidateType(entityType)

  /** Invalidate all entities of a specific type using typed entity classes.
    */
  def invalidateTypeOf[A](implicit ev: CedarEntityType[A]): F[Unit] =
    cache.invalidateTypeOf[A]

  /** Clear the entire cache.
    */
  def invalidateAll(): F[Unit] = cache.invalidateAll()

  /** Invalidate an entity and its descendants using typed IDs.
    */
  def invalidateEntityWithCascade[A, Id](id: Id, childrenOf: String => Set[String])(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): F[Unit] =
    cache.invalidateEntityWithCascade[A, Id](id, childrenOf)

  /** Get cache statistics.
    *
    * @return
    *   Optional cache statistics (None if not supported by cache)
    */
  def cacheStats: F[Option[CacheStats]] = cache.stats

  /** Get the underlying cache.
    */
  def getCache: EntityCache[F] = cache

  /** Get the underlying (non-cached) store.
    */
  def getUnderlying: EntityStore[F] = underlying

  /** Load entities with cache-first strategy.
    *
    *   1. Batch lookup all UIDs in cache
    *   2. Identify missing UIDs
    *   3. Fetch missing from underlying store
    *   4. Cache the fetched results
    *   5. Combine cached + fetched
    */
  private def loadWithCache(uids: Set[CedarEntityUid]): F[CedarEntities] = {
    if (uids.isEmpty) {
      F.pure(CedarEntities.empty)
    } else {
      F.flatMap(cache.getMany(uids)) { cached =>
        val missing = uids -- cached.keySet
        if (missing.isEmpty) {
          F.pure(CedarEntities.fromSet(cached.values.toSet))
        } else {
          F.flatMap(underlying.loadEntities(missing)) { loaded =>
            F.map(cache.putMany(loaded.entities)) { _ =>
              CedarEntities.fromSet(cached.values.toSet) ++ loaded
            }
          }
        }
      }
    }
  }
}

object CachingEntityStoreGeneric {

  /** Create a caching wrapper around an existing store.
    *
    * @param store
    *   The underlying entity store
    * @param cache
    *   The cache implementation
    * @return
    *   A new caching entity store
    */
  def apply[F[_]: Monad](store: EntityStore[F], cache: EntityCache[F]): CachingEntityStoreGeneric[F] =
    new CachingEntityStoreGeneric[F](store, cache)
}
