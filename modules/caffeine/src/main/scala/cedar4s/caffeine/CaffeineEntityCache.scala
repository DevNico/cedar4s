package cedar4s.caffeine

import cedar4s.capability.Applicative
import cedar4s.entities.{CacheStats, CedarEntity, EntityCache}
import cedar4s.schema.CedarEntityUid
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

/** Caffeine-based implementation of EntityCache.
  *
  * Caffeine is a high-performance, near-optimal caching library for the JVM. This implementation provides:
  *   - LRU eviction when maximum size is reached
  *   - Time-based expiration (write and/or access based)
  *   - Optional statistics recording
  *   - Thread-safe concurrent access
  *
  * ==Usage==
  *
  * {{{
  * import cedar4s.caffeine.{CaffeineEntityCache, CaffeineCacheConfig}
  * import scala.concurrent.Future
  * import scala.concurrent.ExecutionContext.Implicits.global
  *
  * // Create cache with default config
  * val cache = CaffeineEntityCache[Future](CaffeineCacheConfig.default)
  *
  * // Use with EntityStore
  * val store = EntityStore.builder[Future]()
  *   .register(userFetcher)
  *   .withCache(cache)
  *   .build()
  * }}}
  *
  * ==Configuration==
  *
  * See [[CaffeineCacheConfig]] for available configuration options:
  *   - `CaffeineCacheConfig.default` - 10K entries, 5 min TTL
  *   - `CaffeineCacheConfig.highThroughput` - 100K entries, 1 min TTL
  *   - `CaffeineCacheConfig.shortLived` - 50K entries, 30 sec TTL
  *
  * ==Thread Safety==
  *
  * This cache is fully thread-safe and can be used concurrently from multiple threads without external synchronization.
  *
  * @tparam F
  *   The effect type (must have Applicative instance)
  */
class CaffeineEntityCache[F[_]](config: CaffeineCacheConfig)(implicit F: Applicative[F]) extends EntityCache[F] {

  private val cache: Cache[CedarEntityUid, CedarEntity] = {
    val builder = Caffeine
      .newBuilder()
      .maximumSize(config.maximumSize)

    config.expireAfterWrite.foreach { duration =>
      builder.expireAfterWrite(duration.toMillis, TimeUnit.MILLISECONDS)
    }

    config.expireAfterAccess.foreach { duration =>
      builder.expireAfterAccess(duration.toMillis, TimeUnit.MILLISECONDS)
    }

    if (config.recordStats) {
      builder.recordStats()
    }

    builder.build[CedarEntityUid, CedarEntity]()
  }

  override def get(uid: CedarEntityUid): F[Option[CedarEntity]] =
    F.pure(Option(cache.getIfPresent(uid)))

  override def getMany(uids: Set[CedarEntityUid]): F[Map[CedarEntityUid, CedarEntity]] =
    F.pure(cache.getAllPresent(uids.asJava).asScala.toMap)

  override def put(entity: CedarEntity): F[Unit] =
    F.pure(cache.put(entity.uid, entity))

  override def putMany(entities: Iterable[CedarEntity]): F[Unit] =
    F.pure(cache.putAll(entities.map(e => e.uid -> e).toMap.asJava))

  override def invalidate(uid: CedarEntityUid): F[Unit] =
    F.pure(cache.invalidate(uid))

  override def invalidateType(entityType: String): F[Unit] = F.pure {
    // Remove all entries matching the entity type
    cache.asMap().keySet().removeIf(_.entityType == entityType)
  }

  override def invalidateWithCascade(uid: CedarEntityUid, childrenOf: String => Set[String]): F[Unit] = F.pure {
    // Invalidate the entity itself
    cache.invalidate(uid)

    // Extract simple entity name from fully qualified type (e.g., "MyApp::Document" -> "Document")
    val simpleName = uid.entityType.split("::").lastOption.getOrElse(uid.entityType)

    // Get all child entity types
    val children = childrenOf(simpleName)

    // Invalidate all cached entities of child types
    children.foreach { childName =>
      // Child names are simple names, need to match against full entity types
      // that end with "::childName"
      cache.asMap().keySet().removeIf { key =>
        key.entityType.endsWith(s"::$childName") || key.entityType == childName
      }
    }
  }

  override def invalidateAll(): F[Unit] =
    F.pure(cache.invalidateAll())

  override def stats: F[Option[CacheStats]] = F.pure {
    if (config.recordStats) {
      val s = cache.stats()
      Some(
        CacheStats(
          hitCount = s.hitCount(),
          missCount = s.missCount(),
          evictionCount = s.evictionCount(),
          size = cache.estimatedSize()
        )
      )
    } else {
      None
    }
  }

  /** Get the estimated number of entries in the cache.
    */
  def estimatedSize: Long = cache.estimatedSize()

  /** Perform any pending maintenance operations.
    *
    * Caffeine performs maintenance lazily, but this can be called to force immediate cleanup of expired entries.
    */
  def cleanUp(): F[Unit] = F.pure(cache.cleanUp())

  /** Get direct access to the underlying Caffeine cache.
    *
    * Use with caution - this bypasses the F[_] effect wrapper. Useful for advanced use cases like custom eviction
    * listeners.
    */
  def underlying: Cache[CedarEntityUid, CedarEntity] = cache
}

object CaffeineEntityCache {

  /** Create a new Caffeine-based entity cache with the given configuration.
    *
    * @param config
    *   Cache configuration
    * @return
    *   New cache instance
    */
  def apply[F[_]: Applicative](config: CaffeineCacheConfig = CaffeineCacheConfig.default): CaffeineEntityCache[F] =
    new CaffeineEntityCache[F](config)

  /** Create a cache with default configuration.
    */
  def default[F[_]: Applicative]: CaffeineEntityCache[F] =
    apply(CaffeineCacheConfig.default)

  /** Create a cache optimized for high-throughput scenarios.
    */
  def highThroughput[F[_]: Applicative]: CaffeineEntityCache[F] =
    apply(CaffeineCacheConfig.highThroughput)
}
