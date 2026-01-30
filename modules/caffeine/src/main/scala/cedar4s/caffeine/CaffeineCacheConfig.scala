package cedar4s.caffeine

import scala.concurrent.duration.FiniteDuration

/** Configuration for Caffeine-based entity cache.
  *
  * @param maximumSize
  *   Maximum number of entries in the cache (LRU eviction)
  * @param expireAfterWrite
  *   Optional TTL - entries expire this long after being written
  * @param expireAfterAccess
  *   Optional TTL - entries expire this long after last access
  * @param recordStats
  *   Whether to record cache statistics (hit/miss counts, etc.)
  */
final case class CaffeineCacheConfig(
    maximumSize: Long = 10_000,
    expireAfterWrite: Option[FiniteDuration] = None,
    expireAfterAccess: Option[FiniteDuration] = None,
    recordStats: Boolean = true
)

object CaffeineCacheConfig {

  /** Default configuration suitable for most use cases.
    *
    *   - 10,000 entry limit
    *   - 5 minute TTL (write-based)
    *   - Statistics enabled
    */
  val default: CaffeineCacheConfig = CaffeineCacheConfig(
    maximumSize = 10_000,
    expireAfterWrite = Some(scala.concurrent.duration.DurationInt(5).minutes),
    recordStats = true
  )

  /** High-throughput configuration for demanding workloads.
    *
    *   - 100,000 entry limit
    *   - 1 minute write TTL + 30 second access TTL
    *   - Statistics enabled
    */
  val highThroughput: CaffeineCacheConfig = CaffeineCacheConfig(
    maximumSize = 100_000,
    expireAfterWrite = Some(scala.concurrent.duration.DurationInt(1).minute),
    expireAfterAccess = Some(scala.concurrent.duration.DurationInt(30).seconds),
    recordStats = true
  )

  /** Short-lived cache for frequently changing data.
    *
    *   - 50,000 entry limit
    *   - 30 second TTL
    *   - Statistics enabled
    */
  val shortLived: CaffeineCacheConfig = CaffeineCacheConfig(
    maximumSize = 50_000,
    expireAfterWrite = Some(scala.concurrent.duration.DurationInt(30).seconds),
    recordStats = true
  )

  /** Large cache with long retention.
    *
    *   - 500,000 entry limit
    *   - 30 minute TTL
    *   - Statistics enabled
    */
  val large: CaffeineCacheConfig = CaffeineCacheConfig(
    maximumSize = 500_000,
    expireAfterWrite = Some(scala.concurrent.duration.DurationInt(30).minutes),
    recordStats = true
  )

  /** Minimal configuration for testing.
    *
    *   - 100 entry limit
    *   - 1 second TTL
    *   - Statistics enabled
    */
  val testing: CaffeineCacheConfig = CaffeineCacheConfig(
    maximumSize = 100,
    expireAfterWrite = Some(scala.concurrent.duration.DurationInt(1).second),
    recordStats = true
  )
}
