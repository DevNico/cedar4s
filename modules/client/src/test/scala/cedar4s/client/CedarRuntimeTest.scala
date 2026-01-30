package cedar4s.client

import cedar4s.auth._
import cedar4s.entities.{
  CedarEntities,
  CedarEntity,
  CedarValue,
  CedarEntityType,
  CedarPrincipal,
  ResourceRef,
  EntityStore
}
import cedar4s.schema.CedarEntityUid
import cedar4s.capability.instances.{futureSync, futureMonadError}
import munit.FunSuite

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/** Tests for CedarRuntime trait and CedarRuntime implementation.
  *
  * These tests verify:
  *   - Runtime creates sessions with correct principal binding
  *   - Multiple sessions can be created from the same runtime
  *   - Runtime is reusable and thread-safe (shared CedarEngine/EntityStore)
  *   - Implicit/given patterns work correctly
  */
class CedarRuntimeTest extends FunSuite {

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  // Test principals
  case class TestUser(id: String) extends Principal {
    val entityType = "Test::User"
    val entityId = id
  }

  // Test principal entity
  case class TestUserEntity(id: String, name: String)

  // Provide CedarEntityType for test principal
  implicit val testUserEntityType: CedarEntityType.Aux[TestUserEntity, String] =
    new CedarEntityType[TestUserEntity] {
      type Id = String
      val entityType: String = "Test::User"
      def toCedarEntity(a: TestUserEntity): CedarEntity = CedarEntity(
        entityType = entityType,
        entityId = a.id,
        parents = Set.empty,
        attributes = Map("name" -> CedarValue.string(a.name))
      )
      def getParentIds(a: TestUserEntity): List[(String, String)] = Nil
    }

  val alice = TestUser("alice")
  val bob = TestUser("bob")

  // Mock engine
  val mockEngine = CedarEngine.fromResources(
    policiesPath = "test-policies",
    policyFiles = Seq("ownership.cedar")
  )

  // Mock entity store
  val mockStore = new EntityStore[Future] {
    override def loadEntity(entityType: String, entityId: String): Future[Option[CedarEntity]] =
      Future.successful(None)

    override def loadEntities(uids: Set[CedarEntityUid]): Future[CedarEntities] =
      Future.successful(CedarEntities.empty)

    override def loadForRequest(principal: CedarPrincipal, resource: ResourceRef): Future[CedarEntities] =
      Future.successful(CedarEntities.empty)

    override def loadForBatch(principal: CedarPrincipal, resources: Seq[ResourceRef]): Future[CedarEntities] =
      Future.successful(CedarEntities.empty)

    override def loadEntityWithParents(
        entityType: String,
        entityId: String
    ): Future[Option[(CedarEntity, List[(String, String)])]] =
      Future.successful(None)
  }

  // Principal resolver
  def resolvePrincipal(principal: Principal): Future[Option[TestUserEntity]] = {
    principal match {
      case TestUser(id) => Future.successful(Some(TestUserEntity(id, s"User $id")))
      case _            => Future.successful(None)
    }
  }

  test("session creates runner with correct principal") {
    val runtime =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(resolvePrincipal))

    val aliceSession = runtime.session(alice)
    assertEquals(aliceSession.principal, alice)

    val bobSession = runtime.session(bob)
    assertEquals(bobSession.principal, bob)
  }

  test("different sessions have different principals") {
    val runtime =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(resolvePrincipal))

    val aliceSession = runtime.session(alice)
    val bobSession = runtime.session(bob)

    assertEquals(aliceSession.principal.entityId, "alice")
    assertEquals(bobSession.principal.entityId, "bob")
  }

  test("runtime can be used with implicit pattern") {
    val runtime =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(resolvePrincipal))

    // Create session for alice and make it implicit
    implicit val aliceSession: CedarSession[Future] = runtime.session(alice)

    // The implicit session should have alice as principal
    val session = implicitly[CedarSession[Future]]
    assertEquals(session.principal.entityId, "alice")
  }

  test("runtime is reusable across multiple requests") {
    val runtime =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(resolvePrincipal))

    // Simulate multiple requests
    val request1Session = runtime.session(alice)
    val request2Session = runtime.session(bob)
    val request3Session = runtime.session(alice)

    assertEquals(request1Session.principal.entityId, "alice")
    assertEquals(request2Session.principal.entityId, "bob")
    assertEquals(request3Session.principal.entityId, "alice")
  }

  test("resolver function is called correctly") {
    var resolvePrincipalCalls = List.empty[Principal]

    def trackingResolvePrincipal(principal: Principal): Future[Option[TestUserEntity]] = {
      resolvePrincipalCalls = resolvePrincipalCalls :+ principal
      principal match {
        case TestUser(id) => Future.successful(Some(TestUserEntity(id, s"User $id")))
        case _            => Future.successful(None)
      }
    }

    val runtime =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(trackingResolvePrincipal))
    val session = runtime.session(alice)

    // resolvePrincipal shouldn't be called yet (lazy)
    assertEquals(resolvePrincipalCalls.length, 0)

    // Note: resolver is called when an authorization check is performed
    // This test just verifies the runtime wires it correctly
  }

  test("runtime can be summoned using implicitly") {
    implicit val runtimeImplicit: CedarRuntime[Future, TestUserEntity] =
      CedarRuntime[Future, TestUserEntity](mockEngine, mockStore, CedarRuntime.resolverFrom(resolvePrincipal))

    val runtime = implicitly[CedarRuntime[Future, TestUserEntity]]
    val session = runtime.session(alice)

    assertEquals(session.principal, alice)
  }
}
