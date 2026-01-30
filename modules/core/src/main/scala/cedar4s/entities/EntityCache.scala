package cedar4s.entities

import cedar4s.Bijection
import cedar4s.capability.Applicative
import cedar4s.schema.CedarEntityUid

/** Effect-polymorphic cache for Cedar entities.
  *
  * This trait provides a pluggable caching abstraction that works with any effect type F[_]. Users can implement this
  * trait for their cache of choice:
  *   - Caffeine for local JVM caching (see cedar4s-caffeine module)
  *   - Redis/Valkey for distributed caching
  *   - Custom implementations for specific needs
  *
  * ==Usage with EntityStoreBuilder==
  *
  * {{{
  * import cedar4s.caffeine.CaffeineEntityCache
  *
  * val cache = CaffeineEntityCache[IO](CaffeineCacheConfig.default)
  *
  * val store = EntityStore.builder[IO]()
  *   .register(userFetcher)
  *   .register(documentFetcher)
  *   .withCache(cache)
  *   .build()
  * }}}
  *
  * ==Implementing a Custom Cache==
  *
  * {{{
  * class RedisEntityCache[F[_]](client: RedisClient)(implicit F: Async[F])
  *     extends EntityCache[F] {
  *
  *   def get(uid: CedarEntityUid): F[Option[CedarEntity]] =
  *     client.get(uid.toString).map(_.map(deserialize))
  *
  *   // ... other methods
  * }
  * }}}
  *
  * @tparam F
  *   The effect type (Future, IO, Task, etc.)
  */
trait EntityCache[F[_]] {

  /** Get a single entity from the cache.
    *
    * @param uid
    *   The entity UID to look up
    * @return
    *   F[Some(entity)] if found, F[None] if not cached
    */
  def get(uid: CedarEntityUid): F[Option[CedarEntity]]

  /** Get multiple entities from the cache.
    *
    * This method should be optimized for batch retrieval when possible. Only found entries are returned (missing UIDs
    * are omitted from result).
    *
    * @param uids
    *   Set of entity UIDs to look up
    * @return
    *   Map of found entities (UID -> Entity)
    */
  def getMany(uids: Set[CedarEntityUid]): F[Map[CedarEntityUid, CedarEntity]]

  /** Put a single entity into the cache.
    *
    * @param entity
    *   The entity to cache (uses entity.uid as key)
    */
  def put(entity: CedarEntity): F[Unit]

  /** Put multiple entities into the cache.
    *
    * This method should be optimized for batch insertion when possible.
    *
    * @param entities
    *   The entities to cache
    */
  def putMany(entities: Iterable[CedarEntity]): F[Unit]

  /** Invalidate a single cached entry.
    *
    * @param uid
    *   The entity UID to invalidate
    */
  def invalidate(uid: CedarEntityUid): F[Unit]

  /** Invalidate a single cached entry using typed IDs.
    */
  def invalidateEntity[A, Id](id: Id)(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): F[Unit] =
    invalidate(CedarEntityUid(ev.entityType, bij.from(id)))

  /** Invalidate all entries of a specific entity type.
    *
    * Useful when a type's schema changes or bulk updates occur.
    *
    * @param entityType
    *   The Cedar entity type (e.g., "MyApp::User")
    */
  def invalidateType(entityType: String): F[Unit]

  /** Invalidate all entries of a specific entity type using typed entity classes.
    */
  def invalidateTypeOf[A](implicit ev: CedarEntityType[A]): F[Unit] =
    invalidateType(ev.entityType)

  /** Invalidate a single entry and all its descendants in the entity hierarchy.
    *
    * This method invalidates:
    *   1. The specified entity
    *   2. All child entity types (if using generated EntitySchema)
    *
    * Useful when deleting or modifying an entity that may have children, to avoid stale cached data allowing access to
    * deleted/modified entities.
    *
    * Example: Deleting a Folder should invalidate all cached Documents in that folder.
    *
    * @param uid
    *   The entity UID to invalidate
    * @param childrenOf
    *   Function that returns child entity type names for a given entity type name. Generated code provides this via
    *   EntitySchema.childrenOf
    * @return
    *   Effect that performs the invalidation
    */
  def invalidateWithCascade(uid: CedarEntityUid, childrenOf: String => Set[String]): F[Unit]

  /** Invalidate an entry and its descendants using typed IDs.
    */
  def invalidateEntityWithCascade[A, Id](id: Id, childrenOf: String => Set[String])(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): F[Unit] =
    invalidateWithCascade(CedarEntityUid(ev.entityType, bij.from(id)), childrenOf)

  /** Clear the entire cache.
    */
  def invalidateAll(): F[Unit]

  /** Get cache statistics.
    *
    * Returns None if the cache implementation doesn't support statistics.
    *
    * @return
    *   Optional cache statistics
    */
  def stats: F[Option[CacheStats]]
}

/** Cache statistics for monitoring and debugging.
  *
  * @param hitCount
  *   Number of cache hits
  * @param missCount
  *   Number of cache misses
  * @param evictionCount
  *   Number of entries evicted
  * @param size
  *   Current number of entries in cache
  */
final case class CacheStats(
    hitCount: Long,
    missCount: Long,
    evictionCount: Long,
    size: Long
) {

  /** Calculate cache hit rate (0.0 to 1.0) */
  def hitRate: Double = {
    val total = hitCount + missCount
    if (total == 0) 0.0 else hitCount.toDouble / total
  }

  /** Calculate cache miss rate (0.0 to 1.0) */
  def missRate: Double = 1.0 - hitRate

  /** Total number of requests (hits + misses) */
  def requestCount: Long = hitCount + missCount
}

object CacheStats {
  val empty: CacheStats = CacheStats(0, 0, 0, 0)
}

object EntityCache {

  /** Create a no-op cache that never stores anything.
    *
    * Useful for testing or when caching should be disabled.
    */
  def none[F[_]](implicit F: Applicative[F]): EntityCache[F] = new NoOpCache[F]

  /** No-op cache implementation that always returns empty/None.
    */
  private class NoOpCache[F[_]](implicit F: Applicative[F]) extends EntityCache[F] {

    def get(uid: CedarEntityUid): F[Option[CedarEntity]] =
      F.pure(None)

    def getMany(uids: Set[CedarEntityUid]): F[Map[CedarEntityUid, CedarEntity]] =
      F.pure(Map.empty)

    def put(entity: CedarEntity): F[Unit] =
      F.pure(())

    def putMany(entities: Iterable[CedarEntity]): F[Unit] =
      F.pure(())

    def invalidate(uid: CedarEntityUid): F[Unit] =
      F.pure(())

    def invalidateType(entityType: String): F[Unit] =
      F.pure(())

    def invalidateWithCascade(uid: CedarEntityUid, childrenOf: String => Set[String]): F[Unit] =
      F.pure(())

    def invalidateAll(): F[Unit] =
      F.pure(())

    def stats: F[Option[CacheStats]] =
      F.pure(None)
  }
}
