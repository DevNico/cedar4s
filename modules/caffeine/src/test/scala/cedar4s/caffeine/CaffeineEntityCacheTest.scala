package cedar4s.caffeine

import cedar4s.Bijection
import cedar4s.capability.Applicative
import cedar4s.entities.{CacheStats, CedarEntity, CedarEntityType, CedarValue}
import cedar4s.schema.CedarEntityUid
import munit.FunSuite

import scala.concurrent.duration._

class CaffeineEntityCacheTest extends FunSuite {

  // Simple Id effect for synchronous testing
  type Id[A] = A

  implicit val applicative: Applicative[Id] = new Applicative[Id] {
    def pure[A](a: A): Id[A] = a
    def map2[A, B, C](fa: Id[A], fb: Id[B])(f: (A, B) => C): Id[C] = f(fa, fb)
  }

  def makeEntity(id: String, entityType: String = "Test::User"): CedarEntity =
    CedarEntity(
      entityType = entityType,
      entityId = id,
      attributes = Map("name" -> CedarValue.string(s"Entity $id"))
    )

  case class TypedUserId(value: String)
  case class TypedUser(id: TypedUserId)

  // Bijection for TypedUserId (replaces CedarEntityId)
  implicit val typedUserIdBijection: Bijection[String, TypedUserId] =
    Bijection(TypedUserId.apply, _.value)

  implicit val typedUserEntityType: CedarEntityType.Aux[TypedUser, TypedUserId] =
    new CedarEntityType[TypedUser] {
      type Id = TypedUserId
      val entityType: String = "Test::User"
      def toCedarEntity(a: TypedUser): CedarEntity = makeEntity(a.id.value, entityType)
      def getParentIds(a: TypedUser): List[(String, String)] = Nil
    }

  test("get returns None for missing entry") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val uid = CedarEntityUid("Test::User", "missing")

    assertEquals(cache.get(uid), None)
  }

  test("put and get round-trips entity") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entity = makeEntity("user-1")

    cache.put(entity)
    val result = cache.get(entity.uid)

    assertEquals(result, Some(entity))
  }

  test("getMany returns only found entries") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entity1 = makeEntity("user-1")
    val entity2 = makeEntity("user-2")

    cache.put(entity1)
    cache.put(entity2)

    val uids = Set(
      CedarEntityUid("Test::User", "user-1"),
      CedarEntityUid("Test::User", "user-2"),
      CedarEntityUid("Test::User", "user-missing")
    )

    val result = cache.getMany(uids)

    assertEquals(result.size, 2)
    assertEquals(result.get(entity1.uid), Some(entity1))
    assertEquals(result.get(entity2.uid), Some(entity2))
  }

  test("putMany stores multiple entities") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entities = (1 to 5).map(i => makeEntity(s"user-$i"))

    cache.putMany(entities)

    entities.foreach { entity =>
      assertEquals(cache.get(entity.uid), Some(entity))
    }
  }

  test("invalidate removes single entry") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entity = makeEntity("user-1")

    cache.put(entity)
    assertEquals(cache.get(entity.uid), Some(entity))

    cache.invalidate(entity.uid)
    assertEquals(cache.get(entity.uid), None)
  }

  test("invalidateType removes all entries of type") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val user1 = makeEntity("user-1", "Test::User")
    val user2 = makeEntity("user-2", "Test::User")
    val doc1 = makeEntity("doc-1", "Test::Document")

    cache.putMany(Seq(user1, user2, doc1))

    cache.invalidateType("Test::User")

    assertEquals(cache.get(user1.uid), None)
    assertEquals(cache.get(user2.uid), None)
    assertEquals(cache.get(doc1.uid), Some(doc1))
  }

  test("typed invalidation removes entity by typed id") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entity = makeEntity("typed-1", "Test::User")

    cache.put(entity)
    assertEquals(cache.get(entity.uid), Some(entity))

    cache.invalidateEntity[TypedUser, TypedUserId](TypedUserId("typed-1"))
    assertEquals(cache.get(entity.uid), None)
  }

  test("typed invalidation removes all entries of a type") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val user1 = makeEntity("typed-1", "Test::User")
    val user2 = makeEntity("typed-2", "Test::User")
    val doc1 = makeEntity("doc-1", "Test::Document")

    cache.putMany(Seq(user1, user2, doc1))

    cache.invalidateTypeOf[TypedUser]

    assertEquals(cache.get(user1.uid), None)
    assertEquals(cache.get(user2.uid), None)
    assertEquals(cache.get(doc1.uid), Some(doc1))
  }

  test("invalidateWithCascade removes children of the specified type") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val folder = makeEntity("folder-1", "Test::Folder")
    val doc = makeEntity("doc-1", "Test::Document")

    cache.putMany(Seq(folder, doc))

    cache.invalidateWithCascade(folder.uid, name => if (name == "Folder") Set("Document") else Set.empty)

    assertEquals(cache.get(folder.uid), None)
    assertEquals(cache.get(doc.uid), None)
  }

  test("invalidateAll clears entire cache") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing)
    val entities = (1 to 10).map(i => makeEntity(s"user-$i"))

    cache.putMany(entities)
    assertEquals(cache.estimatedSize > 0, true)

    cache.invalidateAll()
    cache.cleanUp() // Force cleanup

    assertEquals(cache.estimatedSize, 0L)
  }

  test("stats returns hit/miss counts when enabled") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing.copy(recordStats = true))
    val entity = makeEntity("user-1")

    cache.put(entity)

    // Generate some hits and misses
    cache.get(entity.uid) // hit
    cache.get(entity.uid) // hit
    cache.get(CedarEntityUid("Test::User", "missing")) // miss

    val stats = cache.stats
    assert(stats.isDefined)
    assertEquals(stats.get.hitCount, 2L)
    assertEquals(stats.get.missCount, 1L)
  }

  test("stats returns None when disabled") {
    val cache = CaffeineEntityCache[Id](CaffeineCacheConfig.testing.copy(recordStats = false))
    assertEquals(cache.stats, None)
  }

  test("LRU eviction works when max size exceeded") {
    val config = CaffeineCacheConfig(
      maximumSize = 5,
      expireAfterWrite = None,
      recordStats = true
    )
    val cache = CaffeineEntityCache[Id](config)

    // Insert more than max size
    (1 to 10).foreach { i =>
      cache.put(makeEntity(s"user-$i"))
    }

    cache.cleanUp() // Force eviction

    // Should have evicted some entries
    assert(cache.estimatedSize <= 5)
  }

  test("expireAfterWrite evicts entries after TTL") {
    val config = CaffeineCacheConfig(
      maximumSize = 100,
      expireAfterWrite = Some(50.millis),
      recordStats = true
    )
    val cache = CaffeineEntityCache[Id](config)

    val entity = makeEntity("user-1")
    cache.put(entity)

    assertEquals(cache.get(entity.uid), Some(entity))

    // Wait for expiration
    Thread.sleep(100)
    cache.cleanUp()

    assertEquals(cache.get(entity.uid), None)
  }

  test("default factory creates cache with default config") {
    val cache = CaffeineEntityCache.default[Id]
    val entity = makeEntity("user-1")

    cache.put(entity)
    assertEquals(cache.get(entity.uid), Some(entity))
  }

  test("highThroughput factory creates cache with high-throughput config") {
    val cache = CaffeineEntityCache.highThroughput[Id]
    val entity = makeEntity("user-1")

    cache.put(entity)
    assertEquals(cache.get(entity.uid), Some(entity))
  }
}
