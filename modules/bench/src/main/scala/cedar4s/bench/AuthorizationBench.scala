package cedar4s.bench

import cedar4s.capability.instances.futureSync
import cedar4s.client.{CedarContext, CedarDecision, CedarEngine, CedarRequest}
import cedar4s.entities.{CedarEntities, CedarEntity, CedarValue}
import cedar4s.schema.CedarEntityUid
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.compiletime.uninitialized

/** JMH benchmarks for Cedar authorization performance.
  *
  * These benchmarks measure the core authorization path:
  *   - Single authorization request latency
  *   - Batch authorization throughput
  *   - Impact of entity count on performance
  *   - Impact of policy count on performance
  *
  * Run with: sbt "bench/Jmh/run -i 10 -wi 5 -f 2 -t 1 AuthorizationBench"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class AuthorizationBench {

  given ExecutionContext = ExecutionContext.global

  var engine: CedarEngine[Future] = uninitialized
  var minimalEntities: CedarEntities = uninitialized
  var typicalEntities: CedarEntities = uninitialized
  var largeEntities: CedarEntities = uninitialized
  var allowRequest: CedarRequest = uninitialized
  var denyRequest: CedarRequest = uninitialized
  var batchRequests: Seq[CedarRequest] = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Load engine with ownership policies
    engine = CedarEngine.fromResources(
      policiesPath = "policies",
      policyFiles = Seq("ownership.cedar")
    )

    // Build entity sets of varying sizes
    minimalEntities = buildMinimalEntities()
    typicalEntities = buildTypicalEntities()
    largeEntities = buildLargeEntities(50)

    // Build requests
    allowRequest = CedarRequest(
      principal = CedarEntityUid("Bench::User", "alice"),
      action = CedarEntityUid("Bench::Action", "read"),
      resource = CedarEntityUid("Bench::Document", "doc-1"),
      context = CedarContext.empty
    )

    denyRequest = CedarRequest(
      principal = CedarEntityUid("Bench::User", "eve"),
      action = CedarEntityUid("Bench::Action", "delete"),
      resource = CedarEntityUid("Bench::Document", "doc-1"),
      context = CedarContext.empty
    )

    // Build batch of 100 requests (mix of allow/deny)
    batchRequests = (1 to 100).map { i =>
      val user = if (i % 2 == 0) "alice" else "bob"
      val action = if (i % 3 == 0) "write" else "read"
      CedarRequest(
        principal = CedarEntityUid("Bench::User", user),
        action = CedarEntityUid("Bench::Action", action),
        resource = CedarEntityUid("Bench::Document", s"doc-${i % 10}"),
        context = CedarContext.empty
      )
    }
  }

  // ===========================================================================
  // Single Request Benchmarks
  // ===========================================================================

  @Benchmark
  def authorizeAllow_Minimal(): CedarDecision = {
    Await.result(engine.authorize(allowRequest, minimalEntities), 1.second)
  }

  @Benchmark
  def authorizeAllow_Typical(): CedarDecision = {
    Await.result(engine.authorize(allowRequest, typicalEntities), 1.second)
  }

  @Benchmark
  def authorizeAllow_Large(): CedarDecision = {
    Await.result(engine.authorize(allowRequest, largeEntities), 1.second)
  }

  @Benchmark
  def authorizeDeny_Minimal(): CedarDecision = {
    Await.result(engine.authorize(denyRequest, minimalEntities), 1.second)
  }

  // ===========================================================================
  // Batch Benchmarks
  // ===========================================================================

  @Benchmark
  @OperationsPerInvocation(100)
  def authorizeBatch_100(): Seq[CedarDecision] = {
    Await.result(engine.authorizeBatch(batchRequests, typicalEntities), 10.seconds)
  }

  // ===========================================================================
  // Entity Building Helpers
  // ===========================================================================

  private def buildMinimalEntities(): CedarEntities = {
    // Just principal and resource
    CedarEntities(
      CedarEntity(
        entityType = "Bench::User",
        entityId = "alice",
        attributes = Map("name" -> CedarValue.string("Alice"))
      ),
      CedarEntity(
        entityType = "Bench::Document",
        entityId = "doc-1",
        attributes = Map(
          "owner" -> CedarValue.entity("Bench::User", "alice"),
          "name" -> CedarValue.string("Document 1")
        )
      )
    )
  }

  private def buildTypicalEntities(): CedarEntities = {
    // Principal, resource, and some related entities
    CedarEntities(
      // Users
      CedarEntity(
        "Bench::User",
        "alice",
        attributes = Map(
          "name" -> CedarValue.string("Alice"),
          "email" -> CedarValue.string("alice@example.com")
        )
      ),
      CedarEntity(
        "Bench::User",
        "bob",
        attributes = Map(
          "name" -> CedarValue.string("Bob"),
          "email" -> CedarValue.string("bob@example.com")
        )
      ),
      CedarEntity(
        "Bench::User",
        "eve",
        attributes = Map(
          "name" -> CedarValue.string("Eve"),
          "email" -> CedarValue.string("eve@example.com")
        )
      ),
      // Documents
      CedarEntity(
        "Bench::Document",
        "doc-1",
        attributes = Map(
          "owner" -> CedarValue.entity("Bench::User", "alice"),
          "editors" -> CedarValue.entitySet(Set("bob"), "Bench::User"),
          "viewers" -> CedarValue.entitySet(Set.empty, "Bench::User"),
          "name" -> CedarValue.string("Document 1")
        )
      ),
      CedarEntity(
        "Bench::Document",
        "doc-2",
        attributes = Map(
          "owner" -> CedarValue.entity("Bench::User", "bob"),
          "editors" -> CedarValue.entitySet(Set("alice"), "Bench::User"),
          "viewers" -> CedarValue.entitySet(Set.empty, "Bench::User"),
          "name" -> CedarValue.string("Document 2")
        )
      )
    )
  }

  private def buildLargeEntities(count: Int): CedarEntities = {
    val users = (1 to count).map { i =>
      CedarEntity(
        s"Bench::User",
        s"user-$i",
        attributes = Map(
          "name" -> CedarValue.string(s"User $i"),
          "email" -> CedarValue.string(s"user$i@example.com")
        )
      )
    }

    val documents = (1 to count).map { i =>
      CedarEntity(
        "Bench::Document",
        s"doc-$i",
        attributes = Map(
          "owner" -> CedarValue.entity("Bench::User", s"user-${(i % count) + 1}"),
          "editors" -> CedarValue.entitySet((1 to 3).map(j => s"user-${((i + j) % count) + 1}").toSet, "Bench::User"),
          "viewers" -> CedarValue
            .entitySet((1 to 5).map(j => s"user-${((i + j + 3) % count) + 1}").toSet, "Bench::User"),
          "name" -> CedarValue.string(s"Document $i")
        )
      )
    }

    // Add alice for the allowRequest
    val alice = CedarEntity(
      "Bench::User",
      "alice",
      attributes = Map(
        "name" -> CedarValue.string("Alice"),
        "email" -> CedarValue.string("alice@example.com")
      )
    )

    val aliceDoc = CedarEntity(
      "Bench::Document",
      "doc-1",
      attributes = Map(
        "owner" -> CedarValue.entity("Bench::User", "alice"),
        "editors" -> CedarValue.entitySet(Set("user-1", "user-2"), "Bench::User"),
        "viewers" -> CedarValue.entitySet(Set("user-3", "user-4", "user-5"), "Bench::User"),
        "name" -> CedarValue.string("Document 1")
      )
    )

    CedarEntities.fromSet((users ++ documents :+ alice :+ aliceDoc).toSet)
  }
}
