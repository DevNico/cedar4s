package cedar4s.bench

import cedar4s.Bijection
import cedar4s.caffeine.{CaffeineEntityCache, CaffeineCacheConfig}
import cedar4s.capability.Applicative
import cedar4s.capability.instances.{futureMonadError, futureSync}
import cedar4s.client.{CedarContext, CedarDecision, CedarEngine, CedarRequest}
import cedar4s.entities.*
import cedar4s.schema.CedarEntityUid
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.compiletime.uninitialized
import scala.util.Random

/** JMH benchmarks for EntityStore performance with entity loading.
  *
  * These benchmarks focus on high-throughput scenarios where entity loading is the critical path:
  *   - Single entity fetch with varying latencies
  *   - Batch entity fetch efficiency
  *   - Cache hit/miss scenarios
  *   - Full authorization requests with entity resolution
  *   - Concurrent request handling
  *
  * Run with: sbt "bench/Jmh/run -i 5 -wi 3 -f 1 -t 1 EntityStoreBench"
  *
  * Quick sanity check: sbt "bench/Jmh/run -i 1 -wi 1 -f 1 -t 1 EntityStoreBench.fetchEntity_InMemory"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class EntityStoreBench {

  given ExecutionContext = ExecutionContext.global

  // Entity stores with different configurations
  var storeInMemory: EntityStore[Future] = uninitialized
  var storeTypicalLatency: EntityStore[Future] = uninitialized
  var storeSlowLatency: EntityStore[Future] = uninitialized
  var storeCached: CachingEntityStoreGeneric[Future] = uninitialized
  var storeBatching: BatchingEntityStore = uninitialized

  // Test data
  var testEntities: Map[String, CedarEntity] = uninitialized
  var singleUid: CedarEntityUid = uninitialized
  var batch10Uids: Set[CedarEntityUid] = uninitialized
  var batch100Uids: Set[CedarEntityUid] = uninitialized
  var batch1000Uids: Set[CedarEntityUid] = uninitialized

  // Auth request data
  var engine: CedarEngine[Future] = uninitialized
  var principal: CedarPrincipal = uninitialized
  var shallowResource: ResourceRef = uninitialized
  var deepResource: ResourceRef = uninitialized
  var authRequest: CedarRequest = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Build test entity data (1000 users, 1000 documents, 100 organizations)
    testEntities = EntityStoreBenchData.buildTestEntities()

    // Build UIDs for different batch sizes
    singleUid = CedarEntityUid("Bench::User", "user-1")
    batch10Uids = (1 to 10).map(i => CedarEntityUid("Bench::User", s"user-$i")).toSet
    batch100Uids = (1 to 100).map(i => CedarEntityUid("Bench::User", s"user-$i")).toSet
    batch1000Uids = (1 to 1000).map(i => CedarEntityUid("Bench::User", s"user-$i")).toSet

    // Build stores with different latency profiles
    storeInMemory = EntityStoreBenchData.buildStore(latencyMs = 0, testEntities)
    storeTypicalLatency = EntityStoreBenchData.buildStore(latencyMs = 2, testEntities)
    storeSlowLatency = EntityStoreBenchData.buildStore(latencyMs = 10, testEntities)

    // Build cached store (wraps in-memory for cache overhead measurement)
    val cache = CaffeineEntityCache[Future](
      CaffeineCacheConfig(
        maximumSize = 10000,
        expireAfterWrite = Some(5.minutes),
        recordStats = false
      )
    )
    storeCached = new CachingEntityStoreGeneric[Future](storeInMemory, cache)

    // Build batching store
    storeBatching = new BatchingEntityStore(
      storeInMemory,
      BatchConfig(windowMs = 5, maxBatchSize = 100)
    )

    // Pre-warm the cache
    Await.result(storeCached.loadEntities(batch100Uids), 5.seconds)

    // Setup for auth request benchmarks
    engine = CedarEngine.fromResources(
      policiesPath = "policies",
      policyFiles = Seq("ownership.cedar")
    )

    principal = CedarPrincipal(
      uid = CedarEntityUid("Bench::User", "alice"),
      entities = CedarEntities(testEntities("user-alice"))
    )

    // Shallow resource (no parent chain)
    shallowResource = ResourceRef(
      entityType = "Bench::Document",
      entityId = Some("doc-1"),
      parents = Nil
    )

    // Deep resource (5-level parent chain: doc -> folder -> folder -> folder -> org)
    deepResource = ResourceRef(
      entityType = "Bench::Document",
      entityId = Some("doc-deep"),
      parents = List(
        ("Bench::Folder", "folder-1"),
        ("Bench::Folder", "folder-2"),
        ("Bench::Folder", "folder-3"),
        ("Bench::Organization", "org-1")
      )
    )

    authRequest = CedarRequest(
      principal = CedarEntityUid("Bench::User", "alice"),
      action = CedarEntityUid("Bench::Action", "read"),
      resource = CedarEntityUid("Bench::Document", "doc-1"),
      context = CedarContext.empty
    )
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    storeBatching.shutdown()
  }

  // ===========================================================================
  // Single Entity Fetch Benchmarks
  // ===========================================================================

  @Benchmark
  def fetchEntity_InMemory(): Option[CedarEntity] = {
    Await.result(storeInMemory.loadEntity("Bench::User", "user-1"), 1.second)
  }

  @Benchmark
  def fetchEntity_TypicalLatency(): Option[CedarEntity] = {
    Await.result(storeTypicalLatency.loadEntity("Bench::User", "user-1"), 1.second)
  }

  // ===========================================================================
  // Batch Fetch Benchmarks
  // ===========================================================================

  @Benchmark
  @OperationsPerInvocation(10)
  def fetchBatch_10_InMemory(): CedarEntities = {
    Await.result(storeInMemory.loadEntities(batch10Uids), 1.second)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def fetchBatch_100_InMemory(): CedarEntities = {
    Await.result(storeInMemory.loadEntities(batch100Uids), 1.second)
  }

  @Benchmark
  @OperationsPerInvocation(1000)
  def fetchBatch_1000_InMemory(): CedarEntities = {
    Await.result(storeInMemory.loadEntities(batch1000Uids), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def fetchBatch_100_TypicalLatency(): CedarEntities = {
    Await.result(storeTypicalLatency.loadEntities(batch100Uids), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def fetchBatch_100_CacheHit(): CedarEntities = {
    // All 100 are in cache
    Await.result(storeCached.loadEntities(batch100Uids), 1.second)
  }

  // ===========================================================================
  // Auth Request with Entity Loading Benchmarks
  // ===========================================================================

  @Benchmark
  def loadForRequest_Shallow_InMemory(): CedarEntities = {
    Await.result(storeInMemory.loadForRequest(principal, shallowResource), 1.second)
  }

  @Benchmark
  def loadForRequest_Deep_InMemory(): CedarEntities = {
    Await.result(storeInMemory.loadForRequest(principal, deepResource), 1.second)
  }

  @Benchmark
  def loadForRequest_Shallow_TypicalLatency(): CedarEntities = {
    Await.result(storeTypicalLatency.loadForRequest(principal, shallowResource), 5.seconds)
  }

  @Benchmark
  def loadForRequest_Deep_TypicalLatency(): CedarEntities = {
    Await.result(storeTypicalLatency.loadForRequest(principal, deepResource), 5.seconds)
  }

  @Benchmark
  def loadForRequest_Cached(): CedarEntities = {
    Await.result(storeCached.loadForRequest(principal, shallowResource), 1.second)
  }

  // ===========================================================================
  // Full Authorization with Entity Loading
  // ===========================================================================

  @Benchmark
  def fullAuth_WithEntityLoading(): CedarDecision = {
    val entities = Await.result(storeInMemory.loadForRequest(principal, shallowResource), 1.second)
    Await.result(engine.authorize(authRequest, entities), 1.second)
  }

  @Benchmark
  def fullAuth_CachedEntities(): CedarDecision = {
    val entities = Await.result(storeCached.loadForRequest(principal, shallowResource), 1.second)
    Await.result(engine.authorize(authRequest, entities), 1.second)
  }

  // ===========================================================================
  // Concurrent Access Benchmarks
  // ===========================================================================

  @Benchmark
  @OperationsPerInvocation(100)
  @Threads(4)
  def concurrent_4Threads_CacheHit(blackhole: Blackhole): Unit = {
    val result = Await.result(storeCached.loadEntity("Bench::User", "user-1"), 1.second)
    blackhole.consume(result)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  @Threads(8)
  def concurrent_8Threads_CacheHit(blackhole: Blackhole): Unit = {
    val result = Await.result(storeCached.loadEntity("Bench::User", "user-1"), 1.second)
    blackhole.consume(result)
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================
}

/** Cache-specific benchmarks that avoid cache hit/miss contamination.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class EntityStoreCacheBench {

  given ExecutionContext = ExecutionContext.global

  var storeInMemory: EntityStore[Future] = uninitialized
  var cacheHitStore: CachingEntityStoreGeneric[Future] = uninitialized
  var cacheMissStore: CachingEntityStoreGeneric[Future] = uninitialized
  var testEntities: Map[String, CedarEntity] = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    testEntities = EntityStoreBenchData.buildTestEntities()
    storeInMemory = EntityStoreBenchData.buildStore(latencyMs = 0, testEntities)

    val cacheHit = CaffeineEntityCache[Future](
      CaffeineCacheConfig(
        maximumSize = 10000,
        expireAfterWrite = Some(5.minutes),
        recordStats = false
      )
    )
    val cacheMiss = CaffeineEntityCache[Future](
      CaffeineCacheConfig(
        maximumSize = 10000,
        expireAfterWrite = Some(5.minutes),
        recordStats = false
      )
    )

    cacheHitStore = new CachingEntityStoreGeneric[Future](storeInMemory, cacheHit)
    cacheMissStore = new CachingEntityStoreGeneric[Future](storeInMemory, cacheMiss)

    // Warm only the hit cache
    Await.result(cacheHitStore.loadEntity("Bench::User", "user-1"), 1.second)
  }

  @Setup(Level.Invocation)
  def clearMissCache(): Unit = {
    // Ensure cache-miss benchmark stays a miss
    Await.result(cacheMissStore.invalidateAll(), 1.second)
  }

  @Benchmark
  def fetchEntity_CacheHit_Isolated(): Option[CedarEntity] = {
    Await.result(cacheHitStore.loadEntity("Bench::User", "user-1"), 1.second)
  }

  @Benchmark
  def fetchEntity_CacheMiss_Isolated(): Option[CedarEntity] = {
    Await.result(cacheMissStore.loadEntity("Bench::User", "user-999"), 1.second)
  }
}

private object EntityStoreBenchData {
  def buildTestEntities(): Map[String, CedarEntity] = {
    val users = (1 to 1000).map { i =>
      val id = s"user-$i"
      id -> CedarEntity(
        entityType = "Bench::User",
        entityId = id,
        attributes = Map(
          "name" -> CedarValue.string(s"User $i"),
          "email" -> CedarValue.string(s"user$i@example.com")
        )
      )
    }.toMap

    val alice = "user-alice" -> CedarEntity(
      entityType = "Bench::User",
      entityId = "alice",
      attributes = Map(
        "name" -> CedarValue.string("Alice"),
        "email" -> CedarValue.string("alice@example.com")
      )
    )

    val documents = (1 to 1000).map { i =>
      val id = s"doc-$i"
      id -> CedarEntity(
        entityType = "Bench::Document",
        entityId = id,
        attributes = Map(
          "owner" -> CedarValue.entity("Bench::User", s"user-${(i % 100) + 1}"),
          "name" -> CedarValue.string(s"Document $i")
        )
      )
    }.toMap

    // Deep document with parent chain
    val deepDoc = "doc-deep" -> CedarEntity(
      entityType = "Bench::Document",
      entityId = "doc-deep",
      parents = Set(CedarEntityUid("Bench::Folder", "folder-1")),
      attributes = Map(
        "owner" -> CedarValue.entity("Bench::User", "alice"),
        "name" -> CedarValue.string("Deep Document")
      )
    )

    // Folders for hierarchy
    val folders = (1 to 3).map { i =>
      val id = s"folder-$i"
      val parent =
        if (i < 3) Set(CedarEntityUid("Bench::Folder", s"folder-${i + 1}"))
        else Set(CedarEntityUid("Bench::Organization", "org-1"))
      id -> CedarEntity(
        entityType = "Bench::Folder",
        entityId = id,
        parents = parent,
        attributes = Map("name" -> CedarValue.string(s"Folder $i"))
      )
    }.toMap

    val organizations = (1 to 100).map { i =>
      val id = s"org-$i"
      id -> CedarEntity(
        entityType = "Bench::Organization",
        entityId = id,
        attributes = Map("name" -> CedarValue.string(s"Organization $i"))
      )
    }.toMap

    users + alice ++ documents + deepDoc ++ folders ++ organizations
  }

  def buildStore(
      latencyMs: Int,
      testEntities: Map[String, CedarEntity]
  )(using ec: ExecutionContext): EntityStore[Future] = {
    // Create a fetcher that simulates database latency
    val fetcher = new SimulatedEntityFetcher(latencyMs, testEntities)

    EntityStore
      .builder[Future]()
      .register(fetcher)(using SimulatedEntityFetcher.entityType, Bijection.identity[String])
      .build()
  }
}

/** Simulated entity fetcher with configurable latency.
  *
  * Used to benchmark EntityStore under realistic conditions.
  */
class SimulatedEntityFetcher(
    latencyMs: Int,
    entities: Map[String, CedarEntity]
)(using ec: ExecutionContext)
    extends EntityFetcher[Future, CedarEntity, String] {

  override def fetch(id: String): Future[Option[CedarEntity]] = Future {
    if (latencyMs > 0) Thread.sleep(latencyMs)
    entities.get(id)
  }

  override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] = Future {
    // Single "round trip" regardless of batch size
    if (latencyMs > 0) Thread.sleep(latencyMs)
    ids.flatMap(id => entities.get(id).map(id -> _)).toMap
  }
}

object SimulatedEntityFetcher {
  // CedarEntityType for the simulated entities
  given entityType: CedarEntityType.Aux[CedarEntity, String] =
    CedarEntityType.instance(
      entityTypeName = "Bench::User",
      toEntity = identity,
      parentIds = e => e.parents.toList.map(p => (p.entityType, p.entityId))
    )
}

/** More realistic, workload-driven benchmarks for EntityStore.
  *
  * Focuses on:
  *   - Multi-entity graph with org/team/group/folder/doc relations
  *   - Hot/cold access patterns
  *   - Latency distributions and batch overheads
  *   - Cache warm + mixed request streams
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class RealWorldEntityStoreBench {

  given ExecutionContext = ExecutionContext.global

  @Param(Array("small", "medium"))
  var scale: String = uninitialized

  @Param(Array("typical", "spiky"))
  var latencyProfile: String = uninitialized

  var storeRealistic: EntityStore[Future] = uninitialized
  var storeCached: CachingEntityStoreGeneric[Future] = uninitialized
  var storeBatching: BatchingEntityStore = uninitialized

  var engine: CedarEngine[Future] = uninitialized

  var principal: CedarPrincipal = uninitialized
  var principalStream: Array[CedarPrincipal] = uninitialized
  var requestStream: Array[CedarRequest] = uninitialized
  var resourceStream: Array[ResourceRef] = uninitialized

  var userIdStream: Array[String] = uninitialized
  var docIdStream: Array[String] = uninitialized
  var shallowResource: ResourceRef = uninitialized
  var deepResource: ResourceRef = uninitialized
  var batchDocUids50: Set[CedarEntityUid] = uninitialized

  private val streamIndex = new AtomicInteger(0)
  private val userIndex = new AtomicInteger(0)
  private val docIndex = new AtomicInteger(0)

  @Setup(Level.Trial)
  def setup(): Unit = {
    val config = RealWorldConfig.forScale(scale)
    val seed = 42
    val dataset = RealWorldDataBuilder.build(config, seed)

    val latencyModel = LatencyModel.forProfile(latencyProfile, seed)

    storeRealistic = RealWorldDataBuilder.buildStore(dataset.entitiesByType, latencyModel)

    val cacheSize = math.max(1000, (dataset.totalEntityCount * 0.15).toInt)
    val cache = CaffeineEntityCache[Future](
      CaffeineCacheConfig(
        maximumSize = cacheSize,
        expireAfterWrite = Some(5.minutes),
        recordStats = false
      )
    )
    storeCached = new CachingEntityStoreGeneric[Future](storeRealistic, cache)

    storeBatching = new BatchingEntityStore(
      storeRealistic,
      BatchConfig(windowMs = 5, maxBatchSize = 100)
    )

    // Warm cache with hot users and documents plus parent chain for shallow/deep.
    Await.result(storeCached.loadEntities(dataset.cacheWarmUids), 10.seconds)

    engine = CedarEngine.fromResources(
      policiesPath = "policies",
      policyFiles = Seq("ownership.cedar", "hierarchy.cedar")
    )

    principal = dataset.defaultPrincipal
    principalStream = dataset.principalStream
    requestStream = dataset.requestStream
    resourceStream = dataset.resourceStream

    userIdStream = dataset.userIdStream
    docIdStream = dataset.docIdStream
    shallowResource = dataset.shallowResource
    deepResource = dataset.deepResource
    batchDocUids50 = dataset.batchDocUids50
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    storeBatching.shutdown()
  }

  // ===========================================================================
  // Hot/cold single-entity access
  // ===========================================================================

  @Benchmark
  def fetchUser_Mixed_Cached(): Option[CedarEntity] = {
    val id = userIdStream(nextIndex(userIndex, userIdStream.length))
    Await.result(storeCached.loadEntity("Bench::User", id), 1.second)
  }

  @Benchmark
  def fetchDocument_Mixed_Uncached(): Option[CedarEntity] = {
    val id = docIdStream(nextIndex(docIndex, docIdStream.length))
    Await.result(storeRealistic.loadEntity("Bench::Document", id), 2.seconds)
  }

  // ===========================================================================
  // Batch access with realistic overheads
  // ===========================================================================

  @Benchmark
  @OperationsPerInvocation(50)
  def fetchBatch_50Docs_Realistic(): CedarEntities = {
    Await.result(storeRealistic.loadEntities(batchDocUids50), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(50)
  def fetchBatch_50Docs_BatchedStore(): CedarEntities = {
    Await.result(storeBatching.loadEntities(batchDocUids50), 5.seconds)
  }

  // ===========================================================================
  // Resource loading
  // ===========================================================================

  @Benchmark
  def loadForRequest_Shallow_Realistic(): CedarEntities = {
    Await.result(storeRealistic.loadForRequest(principal, shallowResource), 2.seconds)
  }

  @Benchmark
  def loadForRequest_Deep_Realistic(): CedarEntities = {
    Await.result(storeRealistic.loadForRequest(principal, deepResource), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def loadForBatch_Mixed_Realistic(): CedarEntities = {
    Await.result(storeRealistic.loadForBatch(principal, resourceStream.take(100).toSeq), 10.seconds)
  }

  // ===========================================================================
  // End-to-end authorization with entity loading
  // ===========================================================================

  @Benchmark
  def fullAuth_Mixed_Realistic(): CedarDecision = {
    val idx = nextIndex(streamIndex, requestStream.length)
    val request = requestStream(idx)
    val resource = resourceStream(idx)
    val principalForRequest = principalStream(idx)
    val entities = Await.result(storeRealistic.loadForRequest(principalForRequest, resource), 5.seconds)
    Await.result(engine.authorize(request, entities), 2.seconds)
  }

  private def nextIndex(counter: AtomicInteger, size: Int): Int = {
    val idx = counter.getAndIncrement()
    if (size == 0) 0 else Math.floorMod(idx, size)
  }
}

private final case class RealWorldConfig(
    orgCount: Int,
    usersPerOrg: Int,
    groupsPerOrg: Int,
    teamsPerOrg: Int,
    foldersPerOrg: Int,
    docsPerOrg: Int,
    folderDepth: Int,
    hotFraction: Double,
    hotTrafficShare: Double
)

private object RealWorldConfig {
  def forScale(scale: String): RealWorldConfig = scale match {
    case "medium" =>
      RealWorldConfig(
        orgCount = 25,
        usersPerOrg = 200,
        groupsPerOrg = 8,
        teamsPerOrg = 8,
        foldersPerOrg = 60,
        docsPerOrg = 600,
        folderDepth = 4,
        hotFraction = 0.02,
        hotTrafficShare = 0.85
      )
    case _ =>
      RealWorldConfig(
        orgCount = 10,
        usersPerOrg = 80,
        groupsPerOrg = 5,
        teamsPerOrg = 5,
        foldersPerOrg = 30,
        docsPerOrg = 200,
        folderDepth = 3,
        hotFraction = 0.02,
        hotTrafficShare = 0.80
      )
  }
}

private final case class RealWorldDataset(
    entitiesByType: Map[String, Map[String, CedarEntity]],
    totalEntityCount: Int,
    cacheWarmUids: Set[CedarEntityUid],
    defaultPrincipal: CedarPrincipal,
    principalStream: Array[CedarPrincipal],
    requestStream: Array[CedarRequest],
    resourceStream: Array[ResourceRef],
    userIdStream: Array[String],
    docIdStream: Array[String],
    shallowResource: ResourceRef,
    deepResource: ResourceRef,
    batchDocUids50: Set[CedarEntityUid]
)

private object RealWorldDataBuilder {
  private val streamSize = 4096

  def build(config: RealWorldConfig, seed: Int): RealWorldDataset = {
    val rng = new Random(seed)

    val orgIds = (1 to config.orgCount).map(i => s"org-$i").toVector
    val orgEntities = orgIds.map { id =>
      id -> CedarEntity(
        entityType = "Bench::Organization",
        entityId = id,
        attributes = Map("name" -> CedarValue.string(s"Organization $id"))
      )
    }.toMap

    val groupEntities = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      (1 to config.groupsPerOrg).map { g =>
        val id = s"group-$orgIndex-$g"
        id -> CedarEntity(
          entityType = "Bench::Group",
          entityId = id,
          parents = Set(CedarEntityUid("Bench::Organization", orgId)),
          attributes = Map("name" -> CedarValue.string(s"Group $id"))
        )
      }
    }.toMap

    val groupIds = groupEntities.keys.toVector
    val groupIdsByOrg = groupIds.groupBy(id => id.split("-").drop(1).head)

    val groupParent = groupIds.map { groupId =>
      val orgIndex = groupId.split("-").drop(1).head
      val orgId = s"org-$orgIndex"
      groupId -> ("Bench::Organization", orgId)
    }.toMap

    val teamEntities = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      (1 to config.teamsPerOrg).map { t =>
        val id = s"team-$orgIndex-$t"
        id -> CedarEntity(
          entityType = "Bench::Team",
          entityId = id,
          parents = Set(CedarEntityUid("Bench::Organization", orgId)),
          attributes = Map("name" -> CedarValue.string(s"Team $id"))
        )
      }
    }.toMap

    val teamIds = teamEntities.keys.toVector
    val teamIdsByOrg = teamIds.groupBy(id => id.split("-").drop(1).head)

    val users = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-").toInt
      val orgGroupIds = groupIdsByOrg.getOrElse(orgIndex.toString, Vector.empty)
      val orgTeamIds = teamIdsByOrg.getOrElse(orgIndex.toString, Vector.empty)
      (1 to config.usersPerOrg).map { i =>
        val id = s"user-$orgIndex-$i"
        val groups = pickSome(orgGroupIds, rng, min = 1, max = 3).toSet
        val teams = pickSome(orgTeamIds, rng, min = 0, max = 2).toSet
        val adminOf = if (i == 1) Set(orgId) else Set.empty[String]
        id -> CedarEntity(
          entityType = "Bench::User",
          entityId = id,
          attributes = Map(
            "name" -> CedarValue.string(s"User $id"),
            "email" -> CedarValue.string(s"$id@example.com"),
            "groups" -> CedarValue.entitySet(groups, "Bench::Group"),
            "teams" -> CedarValue.entitySet(teams, "Bench::Team"),
            "adminOf" -> CedarValue.entitySet(adminOf, "Bench::Organization")
          )
        )
      }
    }.toMap

    val folderParent = scala.collection.mutable.Map.empty[String, (String, String)]
    val folderEntities = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      val orgGroupId = groupIdsByOrg(orgIndex).head
      val orgUsers = users.keys.filter(_.startsWith(s"user-$orgIndex-")).toVector
      (1 to config.foldersPerOrg).map { i =>
        val id = s"folder-$orgIndex-$i"
        val parent =
          if (i == 1) ("Bench::Group", orgGroupId)
          else if (i <= config.folderDepth) ("Bench::Folder", s"folder-$orgIndex-${i - 1}")
          else ("Bench::Folder", s"folder-$orgIndex-1")
        folderParent.update(id, parent)
        val viewers = pickSome(orgUsers, rng, min = 1, max = 5).toSet
        id -> CedarEntity(
          entityType = "Bench::Folder",
          entityId = id,
          parents = Set(CedarEntityUid(parent._1, parent._2)),
          attributes = Map(
            "name" -> CedarValue.string(s"Folder $id"),
            "viewers" -> CedarValue.entitySet(viewers, "Bench::User")
          )
        )
      }
    }.toMap

    val folderChainCache = scala.collection.mutable.Map.empty[String, List[(String, String)]]
    def folderParentChain(folderId: String): List[(String, String)] = {
      folderChainCache.getOrElseUpdate(
        folderId, {
          var chain = List.empty[(String, String)]
          var currentType = "Bench::Folder"
          var currentId = folderId
          var continue = true
          while (continue) {
            currentType match {
              case "Bench::Folder" =>
                folderParent.get(currentId) match {
                  case Some(parent) =>
                    chain = chain :+ parent
                    currentType = parent._1
                    currentId = parent._2
                  case None =>
                    continue = false
                }
              case "Bench::Group" =>
                groupParent.get(currentId) match {
                  case Some(parent) =>
                    chain = chain :+ parent
                    currentType = parent._1
                    currentId = parent._2
                  case None =>
                    continue = false
                }
              case _ =>
                continue = false
            }
          }
          chain
        }
      )
    }

    val documents = orgIds.flatMap { orgId =>
      val orgIndex = orgId.stripPrefix("org-")
      val orgUsers = users.keys.filter(_.startsWith(s"user-$orgIndex-")).toVector
      val orgFolders = folderEntities.keys.filter(_.startsWith(s"folder-$orgIndex-")).toVector
      (1 to config.docsPerOrg).map { i =>
        val id = s"doc-$orgIndex-$i"
        val folderId =
          if (orgIndex == "1" && i == 1) s"folder-1-1"
          else if (orgIndex == "1" && i == 2) s"folder-1-${config.folderDepth}"
          else orgFolders(rng.nextInt(orgFolders.size))
        val owner = orgUsers(rng.nextInt(orgUsers.size))
        val editors = pickSome(orgUsers, rng, min = 1, max = 3).toSet
        val viewers = pickSome(orgUsers, rng, min = 1, max = 6).toSet
        id -> CedarEntity(
          entityType = "Bench::Document",
          entityId = id,
          parents = Set(CedarEntityUid("Bench::Folder", folderId)),
          attributes = Map(
            "owner" -> CedarValue.entity("Bench::User", owner),
            "editors" -> CedarValue.entitySet(editors, "Bench::User"),
            "viewers" -> CedarValue.entitySet(viewers, "Bench::User"),
            "folder" -> CedarValue.entity("Bench::Folder", folderId),
            "name" -> CedarValue.string(s"Document $id")
          )
        )
      }
    }.toMap

    val allUsers = users.keys.toVector
    val allDocs = documents.keys.toVector

    val docResources = allDocs.map { docId =>
      val doc = documents(docId)
      val folderId = doc.parents.head.entityId
      val chain = folderParentChain(folderId)
      ResourceRef(
        entityType = "Bench::Document",
        entityId = Some(docId),
        parents = (("Bench::Folder", folderId) :: chain)
      )
    }
    val docResourceById = docResources.map(r => r.entityId.get -> r).toMap

    val org1UserId = allUsers.find(_.startsWith("user-1-")).get
    val aliceEntity = users(org1UserId)
    val principal = CedarPrincipal(
      uid = CedarEntityUid("Bench::User", org1UserId),
      entities = CedarEntities(aliceEntity)
    )

    val shallowResource = docResourceById.getOrElse("doc-1-1", docResources.head)
    val deepResource = docResourceById.getOrElse("doc-1-2", docResources.head)

    val hotUsers = hotSet(allUsers, config.hotFraction)
    val hotDocs = hotSet(allDocs, config.hotFraction)

    val userIdStream = weightedStream(allUsers, hotUsers, config.hotTrafficShare, streamSize, rng)
    val docIdStream = weightedStream(allDocs, hotDocs, config.hotTrafficShare, streamSize, rng)

    val principals = allUsers
      .take(128)
      .map { id =>
        CedarPrincipal(
          uid = CedarEntityUid("Bench::User", id),
          entities = CedarEntities(users(id))
        )
      }
      .toArray

    val resourceStream = docIdStream.map(id => docResourceById(id))

    val principalStream = Array.tabulate(streamSize) { i =>
      principals(i % principals.length)
    }

    val requestStream = (0 until streamSize).map { i =>
      val principalUid = principalStream(i).uid
      val action = actionForIndex(i)
      CedarRequest(
        principal = principalUid,
        action = CedarEntityUid("Bench::Action", action),
        resource = CedarEntityUid("Bench::Document", docIdStream(i)),
        context = CedarContext.empty
      )
    }.toArray

    val cacheWarmUids =
      (hotUsers.take(200).map(id => CedarEntityUid("Bench::User", id)) ++
        hotDocs.take(200).map(id => CedarEntityUid("Bench::Document", id)) ++
        shallowResource.parentUids ++
        deepResource.parentUids).toSet

    val batchDocUids50 = allDocs.take(50).map(id => CedarEntityUid("Bench::Document", id)).toSet

    val entitiesByType = Map(
      "Bench::Organization" -> orgEntities,
      "Bench::Group" -> groupEntities,
      "Bench::Team" -> teamEntities,
      "Bench::User" -> users,
      "Bench::Folder" -> folderEntities,
      "Bench::Document" -> documents
    )

    RealWorldDataset(
      entitiesByType = entitiesByType,
      totalEntityCount = entitiesByType.values.map(_.size).sum,
      cacheWarmUids = cacheWarmUids,
      defaultPrincipal = principal,
      principalStream = principalStream,
      requestStream = requestStream,
      resourceStream = resourceStream,
      userIdStream = userIdStream,
      docIdStream = docIdStream,
      shallowResource = shallowResource,
      deepResource = deepResource,
      batchDocUids50 = batchDocUids50
    )
  }

  def buildStore(
      entitiesByType: Map[String, Map[String, CedarEntity]],
      latencyModel: LatencyModel
  )(using ec: ExecutionContext): EntityStore[Future] = {
    var builder = EntityStore.builder[Future]()
    entitiesByType.foreach { case (entityType, entities) =>
      val fetcher = new RealisticEntityFetcher(entities, latencyModel)
      builder = builder.register(fetcher)(using cedarEntityType(entityType), Bijection.identity[String])
    }
    builder.build()
  }

  private def cedarEntityType(entityTypeName: String): CedarEntityType.Aux[CedarEntity, String] =
    CedarEntityType.instance(
      entityTypeName = entityTypeName,
      toEntity = identity,
      parentIds = e => e.parents.toList.map(p => (p.entityType, p.entityId))
    )

  private def pickSome[A](source: Vector[A], rng: Random, min: Int, max: Int): Vector[A] = {
    if (source.isEmpty) return Vector.empty
    val count = if (max <= 0) 0 else min + rng.nextInt(math.max(1, max - min + 1))
    rng.shuffle(source).take(count)
  }

  private def hotSet(ids: Vector[String], fraction: Double): Vector[String] = {
    val count = math.max(1, (ids.size * fraction).toInt)
    ids.take(count)
  }

  private def weightedStream(
      all: Vector[String],
      hot: Vector[String],
      hotShare: Double,
      size: Int,
      rng: Random
  ): Array[String] = {
    val cold = all.drop(hot.size)
    Array.tabulate(size) { _ =>
      val useHot = cold.isEmpty || rng.nextDouble() < hotShare
      if (useHot) hot(rng.nextInt(hot.size))
      else cold(rng.nextInt(cold.size))
    }
  }

  private def actionForIndex(i: Int): String = {
    val mod = i % 100
    if (mod < 70) "read"
    else if (mod < 90) "write"
    else if (mod < 98) "delete"
    else "admin"
  }
}

private final class LatencySampler(samplesMs: Array[Int]) {
  private val index = new AtomicInteger(0)
  def nextMs(): Int = samplesMs(Math.floorMod(index.getAndIncrement(), samplesMs.length))
}

private final case class LatencyModel(
    singleSampler: LatencySampler,
    batchBaseMs: Int,
    batchPerEntityMs: Int,
    batchJitterSampler: LatencySampler
) {
  def singleDelayMs(): Int = singleSampler.nextMs()
  def batchDelayMs(batchSize: Int): Int =
    batchBaseMs + (batchPerEntityMs * batchSize) + batchJitterSampler.nextMs()
}

private object LatencyModel {
  def forProfile(profile: String, seed: Int): LatencyModel = {
    val rng = new Random(seed + 7)
    profile match {
      case "spiky" =>
        LatencyModel(
          singleSampler =
            new LatencySampler(sampleDistribution(rng, 1000, Seq(2 -> 0.60, 6 -> 0.25, 15 -> 0.10, 50 -> 0.05))),
          batchBaseMs = 3,
          batchPerEntityMs = 1,
          batchJitterSampler =
            new LatencySampler(sampleDistribution(rng, 1000, Seq(2 -> 0.70, 6 -> 0.20, 15 -> 0.08, 40 -> 0.02)))
        )
      case _ =>
        LatencyModel(
          singleSampler =
            new LatencySampler(sampleDistribution(rng, 1000, Seq(2 -> 0.70, 5 -> 0.20, 10 -> 0.08, 25 -> 0.02))),
          batchBaseMs = 2,
          batchPerEntityMs = 1,
          batchJitterSampler =
            new LatencySampler(sampleDistribution(rng, 1000, Seq(2 -> 0.80, 5 -> 0.15, 10 -> 0.04, 20 -> 0.01)))
        )
    }
  }

  private def sampleDistribution(
      rng: Random,
      size: Int,
      weights: Seq[(Int, Double)]
  ): Array[Int] = {
    val expanded = weights.flatMap { case (ms, weight) =>
      val count = math.max(1, (size * weight).toInt)
      Vector.fill(count)(ms)
    }.toVector
    val padded = if (expanded.size >= size) expanded else expanded ++ Vector.fill(size - expanded.size)(weights.head._1)
    rng.shuffle(padded).take(size).toArray
  }
}

private final class RealisticEntityFetcher(
    entities: Map[String, CedarEntity],
    latencyModel: LatencyModel
)(using ec: ExecutionContext)
    extends EntityFetcher[Future, CedarEntity, String] {

  override def fetch(id: String): Future[Option[CedarEntity]] = Future {
    val delay = latencyModel.singleDelayMs()
    if (delay > 0) Thread.sleep(delay.toLong)
    entities.get(id)
  }

  override def fetchBatch(ids: Set[String])(using F: Applicative[Future]): Future[Map[String, CedarEntity]] = Future {
    val delay = latencyModel.batchDelayMs(ids.size)
    if (delay > 0) Thread.sleep(delay.toLong)
    ids.flatMap(id => entities.get(id).map(id -> _)).toMap
  }
}
