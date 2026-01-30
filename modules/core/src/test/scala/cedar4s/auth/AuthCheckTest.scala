package cedar4s.auth

import cedar4s.entities.CedarValue
import munit.FunSuite

/** Tests for AuthCheck construction and composition.
  *
  * These tests verify:
  *   - Single request construction
  *   - AND composition via & operator
  *   - OR composition via | operator
  *   - Nested composition flattening
  *   - Context and condition handling
  */
class AuthCheckTest extends FunSuite {

  // ===========================================================================
  // Test Fixtures
  // ===========================================================================

  case class TestAction(actionName: String) extends CedarAction {
    def name: String = actionName
    def cedarAction: String = s"Test::Action::\"$actionName\""
    def isCollectionAction: Boolean = false
  }

  case class TestResource(entityType: String, id: String) extends CedarResource {
    def entityId: Option[String] = Some(id)
    def parents: List[(String, String)] = Nil
    def toCedarEntity: String = s"$entityType::\"$id\""
  }

  case class TestPrincipal(id: String) extends Principal {
    def entityType: String = "Test::User"
    def entityId: String = id
  }

  val readAction = TestAction("read")
  val writeAction = TestAction("write")
  val deleteAction = TestAction("delete")

  val doc1 = TestResource("Test::Document", "doc-1")
  val doc2 = TestResource("Test::Document", "doc-2")
  val folder1 = TestResource("Test::Folder", "folder-1")

  // ===========================================================================
  // Single Request Construction
  // ===========================================================================

  test("single creates a Single request with no principal") {
    val request = AuthCheck.single(readAction, doc1)

    assert(request.isInstanceOf[AuthCheck.Single[_, _, _]])
    val single = request.asInstanceOf[AuthCheck.Single[Nothing, TestAction, TestResource]]

    assertEquals(single.principal, None)
    assertEquals(single.action, readAction)
    assertEquals(single.resource, doc1)
    assertEquals(single.context, ContextSchema.empty)
    assertEquals(single.condition, None)
  }

  test("asPrincipal sets explicit principal") {
    val principal = TestPrincipal("alice")
    val request = AuthCheck
      .single(readAction, doc1)
      .asPrincipal(principal)(Principal.CanPerform.allow)

    assertEquals(request.principal, Some(principal))
  }

  test("withContext adds context attributes") {
    val ctx = ContextSchema("key" -> CedarValue.string("value"))
    val request = AuthCheck.single(readAction, doc1).withContext(ctx)

    assertEquals(request.context.toMap.get("key"), Some(CedarValue.string("value")))
  }

  test("withContext merges multiple contexts") {
    val ctx1 = ContextSchema("key1" -> CedarValue.string("value1"))
    val ctx2 = ContextSchema("key2" -> CedarValue.string("value2"))

    val request = AuthCheck
      .single(readAction, doc1)
      .withContext(ctx1)
      .withContext(ctx2)

    assertEquals(request.context.toMap.get("key1"), Some(CedarValue.string("value1")))
    assertEquals(request.context.toMap.get("key2"), Some(CedarValue.string("value2")))
  }

  test("when sets condition") {
    var evaluated = false
    val request = AuthCheck
      .single(readAction, doc1)
      .when { evaluated = true; true }

    assert(request.condition.isDefined)
    assert(!evaluated, "condition should not be evaluated at construction")
  }

  test("shouldRun returns true when no condition") {
    val request = AuthCheck.single(readAction, doc1)
    assert(request.shouldRun)
  }

  test("shouldRun returns condition result") {
    val trueRequest = AuthCheck.single(readAction, doc1).when(true)
    val falseRequest = AuthCheck.single(readAction, doc1).when(false)

    assert(trueRequest.shouldRun)
    assert(!falseRequest.shouldRun)
  }

  // ===========================================================================
  // AND Composition Tests
  // ===========================================================================

  test("& creates All from two Single requests") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)

    val combined = req1 & req2

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 2)
  }

  test("& flattens nested All on left") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)
    val req3 = AuthCheck.single(readAction, folder1)

    val combined = (req1 & req2) & req3

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 3, "Should flatten to single All with 3 requests")
  }

  test("& flattens nested All on right") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)
    val req3 = AuthCheck.single(readAction, folder1)

    val combined = req1 & (req2 & req3)

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 3, "Should flatten to single All with 3 requests")
  }

  test("& flattens both sides when both are All") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)
    val req3 = AuthCheck.single(readAction, folder1)
    val req4 = AuthCheck.single(writeAction, folder1)

    val combined = (req1 & req2) & (req3 & req4)

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 4, "Should flatten to single All with 4 requests")
  }

  test("AuthCheck.all creates All from varargs") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)
    val req3 = AuthCheck.single(readAction, folder1)

    val combined = AuthCheck.all(req1, req2, req3)

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 3)
  }

  // ===========================================================================
  // OR Composition Tests
  // ===========================================================================

  test("| creates AnyOf from two Single requests") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(writeAction, doc1)

    val combined = req1 | req2

    assert(combined.isInstanceOf[AuthCheck.AnyOf])
    val anyOf = combined.asInstanceOf[AuthCheck.AnyOf]
    assertEquals(anyOf.requests.size, 2)
  }

  test("| flattens nested AnyOf on left") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(writeAction, doc1)
    val req3 = AuthCheck.single(deleteAction, doc1)

    val combined = (req1 | req2) | req3

    assert(combined.isInstanceOf[AuthCheck.AnyOf])
    val anyOf = combined.asInstanceOf[AuthCheck.AnyOf]
    assertEquals(anyOf.requests.size, 3, "Should flatten to single AnyOf with 3 requests")
  }

  test("| flattens nested AnyOf on right") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(writeAction, doc1)
    val req3 = AuthCheck.single(deleteAction, doc1)

    val combined = req1 | (req2 | req3)

    assert(combined.isInstanceOf[AuthCheck.AnyOf])
    val anyOf = combined.asInstanceOf[AuthCheck.AnyOf]
    assertEquals(anyOf.requests.size, 3, "Should flatten to single AnyOf with 3 requests")
  }

  test("AuthCheck.anyOf creates AnyOf from varargs") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(writeAction, doc1)
    val req3 = AuthCheck.single(deleteAction, doc1)

    val combined = AuthCheck.anyOf(req1, req2, req3)

    assert(combined.isInstanceOf[AuthCheck.AnyOf])
    val anyOf = combined.asInstanceOf[AuthCheck.AnyOf]
    assertEquals(anyOf.requests.size, 3)
  }

  // ===========================================================================
  // Mixed Composition Tests
  // ===========================================================================

  test("mixed & and | preserves structure") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(readAction, doc2)
    val req3 = AuthCheck.single(writeAction, folder1)

    // (req1 & req2) | req3 should be AnyOf(All(req1, req2), req3)
    val combined = (req1 & req2) | req3

    assert(combined.isInstanceOf[AuthCheck.AnyOf])
    val anyOf = combined.asInstanceOf[AuthCheck.AnyOf]
    assertEquals(anyOf.requests.size, 2)
    assert(anyOf.requests.head.isInstanceOf[AuthCheck.All])
  }

  test("mixed | and & preserves structure") {
    val req1 = AuthCheck.single(readAction, doc1)
    val req2 = AuthCheck.single(writeAction, doc1)
    val req3 = AuthCheck.single(readAction, folder1)

    // (req1 | req2) & req3 should be All(AnyOf(req1, req2), req3)
    val combined = (req1 | req2) & req3

    assert(combined.isInstanceOf[AuthCheck.All])
    val all = combined.asInstanceOf[AuthCheck.All]
    assertEquals(all.requests.size, 2)
    assert(all.requests.head.isInstanceOf[AuthCheck.AnyOf])
  }
}
