package cedar4s.entities

import cedar4s.capability.futureMonadError
import cedar4s.Bijection
import cedar4s.schema.CedarEntityUid
import munit.FunSuite

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/** Tests for EntityStore and EntityRegistry.
  *
  * These tests verify:
  *   - Entity registration and lookup
  *   - Entity loading via fetchers
  *   - Parent hierarchy loading
  *   - Batch loading optimization
  */
class EntityStoreTest extends FunSuite {

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  // ===========================================================================
  // Test Entity Classes
  // ===========================================================================

  case class TestUser(id: String, name: String, email: String)

  object TestUser {
    implicit val cedarEntityType: CedarEntityType.Aux[TestUser, String] = new CedarEntityType[TestUser] {
      type Id = String
      val entityType: String = "Test::User"

      def toCedarEntity(a: TestUser): CedarEntity = CedarEntity(
        entityType = entityType,
        entityId = a.id,
        parents = Set.empty,
        attributes = Map(
          "name" -> CedarValue.string(a.name),
          "email" -> CedarValue.string(a.email)
        )
      )

      def getParentIds(a: TestUser): List[(String, String)] = Nil
    }
  }

  case class TestFolder(id: String, name: String, ownerId: String)

  object TestFolder {
    implicit val cedarEntityType: CedarEntityType.Aux[TestFolder, String] = new CedarEntityType[TestFolder] {
      type Id = String
      val entityType: String = "Test::Folder"

      def toCedarEntity(a: TestFolder): CedarEntity = CedarEntity(
        entityType = entityType,
        entityId = a.id,
        parents = Set.empty,
        attributes = Map(
          "name" -> CedarValue.string(a.name),
          "owner" -> CedarValue.entity("Test::User", a.ownerId)
        )
      )

      def getParentIds(a: TestFolder): List[(String, String)] = Nil
    }
  }

  case class TestDocument(id: String, folderId: String, name: String, ownerId: String)

  object TestDocument {
    implicit val cedarEntityType: CedarEntityType.Aux[TestDocument, String] = new CedarEntityType[TestDocument] {
      type Id = String
      val entityType: String = "Test::Document"

      def toCedarEntity(a: TestDocument): CedarEntity = CedarEntity(
        entityType = entityType,
        entityId = a.id,
        parents = Set(CedarEntityUid("Test::Folder", a.folderId)),
        attributes = Map(
          "name" -> CedarValue.string(a.name),
          "owner" -> CedarValue.entity("Test::User", a.ownerId)
        )
      )

      def getParentIds(a: TestDocument): List[(String, String)] =
        List("Test::Folder" -> a.folderId)
    }
  }

  // ===========================================================================
  // Test Fetchers (using EntityFetcher[F, A, Id] with String IDs)
  // ===========================================================================

  // Provide Bijection[String, String] for registration (identity for String IDs)
  implicit val stringBijection: Bijection[String, String] = Bijection.identity[String]

  class TestUserFetcher(data: Map[String, TestUser]) extends EntityFetcher[Future, TestUser, String] {
    var fetchCount = 0

    def fetch(id: String): Future[Option[TestUser]] = {
      fetchCount += 1
      Future.successful(data.get(id))
    }
  }

  class TestFolderFetcher(data: Map[String, TestFolder]) extends EntityFetcher[Future, TestFolder, String] {
    var fetchCount = 0

    def fetch(id: String): Future[Option[TestFolder]] = {
      fetchCount += 1
      Future.successful(data.get(id))
    }
  }

  class TestDocumentFetcher(data: Map[String, TestDocument]) extends EntityFetcher[Future, TestDocument, String] {
    var fetchCount = 0

    def fetch(id: String): Future[Option[TestDocument]] = {
      fetchCount += 1
      Future.successful(data.get(id))
    }
  }

  // ===========================================================================
  // Test Data
  // ===========================================================================

  val users = Map(
    "alice" -> TestUser("alice", "Alice", "alice@example.com"),
    "bob" -> TestUser("bob", "Bob", "bob@example.com")
  )

  val folders = Map(
    "folder-1" -> TestFolder("folder-1", "Shared", "alice")
  )

  val documents = Map(
    "doc-1" -> TestDocument("doc-1", "folder-1", "Meeting Notes", "alice"),
    "doc-2" -> TestDocument("doc-2", "folder-1", "Budget", "bob")
  )

  // ===========================================================================
  // EntityRegistry Tests
  // ===========================================================================

  test("EntityRegistry.register adds fetcher for entity type") {
    val userFetcher = new TestUserFetcher(users)
    val registry = EntityRegistry[Future]()
      .register(userFetcher)

    assert(registry.get("Test::User").isDefined)
    assertEquals(registry.entityTypes, Set("Test::User"))
  }

  test("EntityRegistry.get returns None for unregistered type") {
    val registry = EntityRegistry[Future]()
    assert(registry.get("Unknown::Type").isEmpty)
  }

  test("EntityRegistry supports multiple entity types") {
    val userFetcher = new TestUserFetcher(users)
    val folderFetcher = new TestFolderFetcher(folders)

    val registry = EntityRegistry[Future]()
      .register(userFetcher)
      .register(folderFetcher)

    assertEquals(registry.entityTypes, Set("Test::User", "Test::Folder"))
  }

  // ===========================================================================
  // EntityStore.loadEntity Tests
  // ===========================================================================

  test("loadEntity returns entity when found") {
    val userFetcher = new TestUserFetcher(users)
    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .build()

    val result = await(store.loadEntity("Test::User", "alice"))

    assert(result.isDefined)
    val entity = result.get
    assertEquals(entity.entityType, "Test::User")
    assertEquals(entity.entityId, "alice")
    assertEquals(entity.attributes.get("name"), Some(CedarValue.string("Alice")))
  }

  test("loadEntity returns None when not found") {
    val userFetcher = new TestUserFetcher(users)
    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .build()

    val result = await(store.loadEntity("Test::User", "unknown"))
    assert(result.isEmpty)
  }

  test("loadEntity returns None for unregistered type") {
    val store = EntityStore.builder[Future]().build()

    val result = await(store.loadEntity("Unknown::Type", "id"))
    assert(result.isEmpty)
  }

  // ===========================================================================
  // EntityStore.loadEntities Tests
  // ===========================================================================

  test("loadEntities loads multiple entities") {
    val userFetcher = new TestUserFetcher(users)
    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .build()

    val uids = Set(
      CedarEntityUid("Test::User", "alice"),
      CedarEntityUid("Test::User", "bob")
    )

    val result = await(store.loadEntities(uids))

    assertEquals(result.size, 2)
    assert(result.find(CedarEntityUid("Test::User", "alice")).isDefined)
    assert(result.find(CedarEntityUid("Test::User", "bob")).isDefined)
  }

  test("loadEntities handles mixed found/not-found") {
    val userFetcher = new TestUserFetcher(users)
    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .build()

    val uids = Set(
      CedarEntityUid("Test::User", "alice"),
      CedarEntityUid("Test::User", "unknown")
    )

    val result = await(store.loadEntities(uids))

    assertEquals(result.size, 1)
    assert(result.find(CedarEntityUid("Test::User", "alice")).isDefined)
  }

  test("loadEntities handles multiple entity types") {
    val userFetcher = new TestUserFetcher(users)
    val folderFetcher = new TestFolderFetcher(folders)

    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .register(folderFetcher)
      .build()

    val uids = Set(
      CedarEntityUid("Test::User", "alice"),
      CedarEntityUid("Test::Folder", "folder-1")
    )

    val result = await(store.loadEntities(uids))

    assertEquals(result.size, 2)
    assertEquals(result.ofType("Test::User").size, 1)
    assertEquals(result.ofType("Test::Folder").size, 1)
  }

  // ===========================================================================
  // EntityStore.loadForRequest Tests
  // ===========================================================================

  test("loadForRequest loads resource and merges with principal entities") {
    val userFetcher = new TestUserFetcher(users)
    val docFetcher = new TestDocumentFetcher(documents)
    val folderFetcher = new TestFolderFetcher(folders)

    val store = EntityStore
      .builder[Future]()
      .register(userFetcher)
      .register(docFetcher)
      .register(folderFetcher)
      .build()

    val principal = CedarPrincipal(
      uid = CedarEntityUid("Test::User", "alice"),
      entities = CedarEntities(
        CedarEntity("Test::User", "alice", attributes = Map("name" -> CedarValue.string("Alice")))
      )
    )

    val resource = ResourceRef(
      entityType = "Test::Document",
      entityId = Some("doc-1"),
      parents = List("Test::Folder" -> "folder-1")
    )

    val result = await(store.loadForRequest(principal, resource))

    // Should contain: principal (alice), resource (doc-1), parent (folder-1)
    assert(result.find(CedarEntityUid("Test::User", "alice")).isDefined, "Should have principal")
    assert(result.find(CedarEntityUid("Test::Document", "doc-1")).isDefined, "Should have resource")
    assert(result.find(CedarEntityUid("Test::Folder", "folder-1")).isDefined, "Should have parent")
  }

  // ===========================================================================
  // EntityStore.loadEntityWithParents Tests
  // ===========================================================================

  test("loadEntityWithParents returns entity and parent chain") {
    val docFetcher = new TestDocumentFetcher(documents)

    val store = EntityStore
      .builder[Future]()
      .register(docFetcher)
      .build()

    val result = await(store.loadEntityWithParents("Test::Document", "doc-1"))

    assert(result.isDefined)
    val (entity, parents) = result.get
    assertEquals(entity.entityId, "doc-1")
    assertEquals(parents, List("Test::Folder" -> "folder-1"))
  }

  test("loadEntityWithParents returns None when entity not found") {
    val docFetcher = new TestDocumentFetcher(documents)

    val store = EntityStore
      .builder[Future]()
      .register(docFetcher)
      .build()

    val result = await(store.loadEntityWithParents("Test::Document", "unknown"))

    assert(result.isEmpty, "Should return None when entity not found")
  }

  // ===========================================================================
  // CedarEntities Tests
  // ===========================================================================

  test("CedarEntities.++ merges entity sets") {
    val entities1 = CedarEntities(
      CedarEntity("Test::User", "alice")
    )
    val entities2 = CedarEntities(
      CedarEntity("Test::User", "bob")
    )

    val merged = entities1 ++ entities2

    assertEquals(merged.size, 2)
  }

  test("CedarEntities.find returns matching entity") {
    val entities = CedarEntities(
      CedarEntity("Test::User", "alice", attributes = Map("name" -> CedarValue.string("Alice"))),
      CedarEntity("Test::User", "bob")
    )

    val found = entities.find(CedarEntityUid("Test::User", "alice"))

    assert(found.isDefined)
    assertEquals(found.get.attributes.get("name"), Some(CedarValue.string("Alice")))
  }

  test("CedarEntities.ofType filters by entity type") {
    val entities = CedarEntities(
      CedarEntity("Test::User", "alice"),
      CedarEntity("Test::User", "bob"),
      CedarEntity("Test::Folder", "folder-1")
    )

    val users = entities.ofType("Test::User")

    assertEquals(users.size, 2)
  }
}
