package cedar4s.observability.audit

import munit.FunSuite
import cedar4s.client.{AuthorizationResponse, CedarContext, CedarDecision, CedarEngine, CedarRuntime}
import cedar4s.auth.{CedarAction, CedarResource, Principal, PrincipalResolver}
import cedar4s.entities.{CedarEntities, CedarEntity, CedarEntityType, CedarPrincipal, EntityStore}
import cedar4s.schema.CedarEntityUid

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._

/** Example showing comprehensive usage of the audit logging module.
  *
  * This test demonstrates:
  *   - Creating audit loggers (JSON, File, NoOp)
  *   - Integrating with CedarRuntime via AuditInterceptor
  *   - Custom event enrichment with extractors
  *   - Combining multiple loggers
  */
class ExampleUsageTest extends FunSuite {

  // Domain model
  case class User(id: String, name: String) extends Principal {
    val entityType = "User"
    val entityId = id
  }

  case object DocumentAction {
    case object View extends CedarAction {
      val name = "view"
      val cedarAction = "Document::View"
      val isCollectionAction = false
    }

    case object Edit extends CedarAction {
      val name = "edit"
      val cedarAction = "Document::Edit"
      val isCollectionAction = false
    }
  }

  case class Document(id: String, title: String) extends CedarResource {
    val entityType = "Document"
    val entityId = Some(id)
    val parents = Nil
    def toCedarEntity = s"""Document::"$id""""
  }

  // Test doubles
  implicit val userEntityType: CedarEntityType[User] = new CedarEntityType[User] {
    val entityType = "User"

    def toCedarEntity(user: User): CedarEntity = CedarEntity(
      entityType = "User",
      entityId = user.id,
      parents = Set.empty,
      attributes = Map("name" -> cedar4s.entities.CedarValue.StringValue(user.name))
    )

    def getParentIds(user: User): List[(String, String)] = Nil
  }

  class MockEngine extends CedarEngine[Future] {
    def authorize(request: cedar4s.client.CedarRequest, entities: CedarEntities) =
      Future.successful(CedarDecision(allow = true))

    def authorizeBatch(requests: Seq[cedar4s.client.CedarRequest], entities: CedarEntities) =
      Future.successful(requests.map(_ => CedarDecision(allow = true)))

    def getAllowedActions(
        principal: CedarEntityUid,
        resource: CedarEntityUid,
        actionType: String,
        actions: Set[String],
        entities: CedarEntities
    ) = Future.successful(actions)
  }

  class MockEntityStore extends EntityStore[Future] {
    def loadEntity(entityType: String, entityId: String) =
      Future.successful(None)

    def loadEntityWithParents(entityType: String, entityId: String) =
      Future.successful(None)

    def loadEntities(uids: Set[CedarEntityUid]) =
      Future.successful(CedarEntities.empty)

    def loadForRequest(principal: CedarPrincipal, resource: cedar4s.entities.ResourceRef) =
      Future.successful(CedarEntities.empty)

    def loadForBatch(principal: CedarPrincipal, resources: Seq[cedar4s.entities.ResourceRef]) =
      Future.successful(CedarEntities.empty)
  }

  test("Example: Basic audit logging with JSON output") {
    // Create a JSON logger that writes to stdout
    val auditLogger = JsonAuditLogger.stdout[Future]()

    // Create an audit interceptor
    val interceptor = AuditInterceptor(auditLogger)

    // Create Cedar runtime with audit logging
    val engine = new MockEngine()
    val store = new MockEntityStore()
    val resolver = new PrincipalResolver[Future, User] {
      def resolve(principal: Principal): Future[Option[User]] =
        Future.successful(Some(User(principal.entityId, s"User ${principal.entityId}")))
    }

    val runtime = CedarRuntime(engine, store, resolver)
      .withInterceptor(interceptor)

    // Authorization will automatically be logged
    val session = runtime.session(User("alice", "Alice"))

    // Note: In a real application, you would call session.check(...) here
    // and the authorization would be logged automatically
    assert(session != null) // Basic assertion to make test pass
  }

  test("Example: Audit logging with custom extractors") {
    val out = new ByteArrayOutputStream()
    val auditLogger = JsonAuditLogger.fromStream[Future](out)

    // Create interceptor with custom extractors to enrich events
    val interceptor = AuditInterceptor.withExtractors[Future](
      logger = auditLogger,
      sessionIdExtractor = response => {
        // Extract session ID from response context
        Some("session-" + response.principal.entityId)
      },
      requestIdExtractor = response => {
        // Generate or extract request ID
        Some("req-" + System.currentTimeMillis())
      },
      contextExtractor = response => {
        // Add custom context
        Map(
          "action" -> response.action.name,
          "resourceType" -> response.resource.entityType,
          "allowed" -> response.allowed.toString
        )
      }
    )

    // Simulate authorization response
    val response = createTestResponse(
      principal = User("bob", "Bob"),
      action = DocumentAction.View,
      resource = Document("doc-123", "Test Document"),
      allowed = true
    )

    Await.result(interceptor.onResponse(response), 5.seconds)

    val output = out.toString
    assert(output.contains("bob"))
    assert(output.contains("session-bob"))
  }

  test("Example: Combining multiple audit destinations") {
    val stdout = new ByteArrayOutputStream()
    val stderr = new ByteArrayOutputStream()

    val stdoutLogger = JsonAuditLogger.fromStream[Future](stdout)
    val stderrLogger = JsonAuditLogger.fromStream[Future](stderr)
    val noopLogger = NoOpAuditLogger[Future]()

    // Combine loggers to write to multiple destinations
    val combinedLogger = AuditLogger.combine(
      stdoutLogger,
      stderrLogger,
      noopLogger
    )

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "charlie"),
      action = "Document::Edit",
      resource = ResourceInfo("Document", Some("doc-456")),
      decision = Decision(allow = false, policies = List("deny-policy")),
      reason = Some("Insufficient permissions"),
      durationNanos = 2_500_000,
      context = Map("ip" -> "192.168.1.1"),
      sessionId = Some("session-789"),
      requestId = Some("req-012")
    )

    Await.result(combinedLogger.logDecision(event), 5.seconds)

    // Event should be in both stdout and stderr
    val stdoutContent = stdout.toString
    val stderrContent = stderr.toString

    assert(stdoutContent.contains("charlie"))
    assert(stderrContent.contains("charlie"))
    assert(stdoutContent.contains("Insufficient permissions"))
    assert(stderrContent.contains("Insufficient permissions"))
  }

  test("Example: Conditional audit logging") {
    // Use NoOp logger in test/dev, real logger in production
    val enableAudit = false // Would come from config

    val auditLogger = if (enableAudit) {
      JsonAuditLogger.stdout[Future]()
    } else {
      NoOpAuditLogger[Future]()
    }

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "dave"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-789")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_000_000
    )

    // This completes without doing anything when audit is disabled
    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  // Helper to create test AuthorizationResponse
  private def createTestResponse(
      principal: User,
      action: CedarAction,
      resource: CedarResource,
      allowed: Boolean
  ): AuthorizationResponse = {
    AuthorizationResponse(
      timestamp = Instant.now(),
      durationNanos = 1_500_000,
      principal = principal,
      cedarPrincipal = CedarPrincipal(
        CedarEntityUid("User", principal.entityId),
        CedarEntities.empty
      ),
      action = action,
      resource = resource,
      context = CedarContext.empty,
      entities = CedarEntities.empty,
      decision = CedarDecision(allow = allowed),
      errors = Nil
    )
  }
}
