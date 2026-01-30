package cedar4s.observability.audit

import munit.FunSuite
import cedar4s.client.{AuthorizationResponse, CedarContext, CedarDecision, CedarDiagnostics}
import cedar4s.auth.{CedarAction, CedarResource, Principal}
import cedar4s.entities.{CedarEntities, CedarPrincipal}
import cedar4s.schema.CedarEntityUid

import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._

class AuditInterceptorTest extends FunSuite {

  // Test doubles
  case class TestPrincipal(entityId: String) extends Principal {
    val entityType = "User"
  }

  case class TestAction(name: String, cedarAction: String) extends CedarAction {
    val isCollectionAction = false
  }

  case class TestResource(
      entityType: String,
      entityId: Option[String],
      parents: List[(String, String)] = Nil
  ) extends CedarResource {
    def toCedarEntity: String = entityId match {
      case Some(id) => s"""$entityType::"$id""""
      case None     => entityType
    }
  }

  // Capture logged events
  class CapturingLogger extends AuditLogger[Future] {
    private var events = List.empty[AuthorizationEvent]

    def logDecision(event: AuthorizationEvent): Future[Unit] = Future {
      synchronized {
        events = events :+ event
      }
    }

    def getEvents: List[AuthorizationEvent] = synchronized { events }
  }

  test("AuditInterceptor converts AuthorizationResponse to AuthorizationEvent") {
    val capturingLogger = new CapturingLogger()
    val interceptor = AuditInterceptor(capturingLogger)

    val principal = TestPrincipal("alice")
    val action = TestAction("View", "Document::View")
    val resource = TestResource("Document", Some("doc-123"))

    val response = AuthorizationResponse(
      timestamp = Instant.parse("2026-01-29T12:34:56.789Z"),
      durationNanos = 1_500_000,
      principal = principal,
      cedarPrincipal = CedarPrincipal(
        CedarEntityUid("User", "alice"),
        CedarEntities.empty
      ),
      action = action,
      resource = resource,
      context = CedarContext.empty,
      entities = CedarEntities.empty,
      decision = CedarDecision(
        allow = true,
        diagnostics = Some(
          CedarDiagnostics(
            policiesSatisfied = List("policy-1", "policy-2")
          )
        )
      ),
      errors = Nil
    )

    Await.result(interceptor.onResponse(response), 5.seconds)

    val events = capturingLogger.getEvents
    assertEquals(events.length, 1)

    val event = events.head
    assertEquals(event.principal.entityType, "User")
    assertEquals(event.principal.entityId, "alice")
    assertEquals(event.action, "Document::View")
    assertEquals(event.resource.entityType, "Document")
    assertEquals(event.resource.entityId, Some("doc-123"))
    assertEquals(event.decision.allow, true)
    assertEquals(event.decision.policies, List("policy-1", "policy-2"))
    assertEquals(event.durationNanos, 1_500_000L)
    assertEquals(event.reason, None)
  }

  test("AuditInterceptor extracts deny reason") {
    val capturingLogger = new CapturingLogger()
    val interceptor = AuditInterceptor(capturingLogger)

    val response = AuthorizationResponse(
      timestamp = Instant.now(),
      durationNanos = 2_000_000,
      principal = TestPrincipal("bob"),
      cedarPrincipal = CedarPrincipal(
        CedarEntityUid("User", "bob"),
        CedarEntities.empty
      ),
      action = TestAction("Edit", "Document::Edit"),
      resource = TestResource("Document", Some("doc-456")),
      context = CedarContext.empty,
      entities = CedarEntities.empty,
      decision = CedarDecision(
        allow = false,
        diagnostics = Some(
          CedarDiagnostics(
            reason = Some("User lacks permission"),
            policiesDenied = List("deny-policy")
          )
        )
      ),
      errors = Nil
    )

    Await.result(interceptor.onResponse(response), 5.seconds)

    val events = capturingLogger.getEvents
    assertEquals(events.length, 1)

    val event = events.head
    assertEquals(event.decision.allow, false)
    assertEquals(event.reason, Some("User lacks permission"))
    assertEquals(event.decision.policies, List("deny-policy"))
  }

  test("AuditInterceptor uses custom extractors") {
    val capturingLogger = new CapturingLogger()
    val interceptor = AuditInterceptor.withExtractors[Future](
      logger = capturingLogger,
      sessionIdExtractor = _ => Some("session-123"),
      requestIdExtractor = _ => Some("req-456"),
      contextExtractor = _ => Map("clientIp" -> "192.168.1.1")
    )

    val response = AuthorizationResponse(
      timestamp = Instant.now(),
      durationNanos = 1_000_000,
      principal = TestPrincipal("charlie"),
      cedarPrincipal = CedarPrincipal(
        CedarEntityUid("User", "charlie"),
        CedarEntities.empty
      ),
      action = TestAction("Delete", "Document::Delete"),
      resource = TestResource("Document", Some("doc-789")),
      context = CedarContext.empty,
      entities = CedarEntities.empty,
      decision = CedarDecision(allow = true),
      errors = Nil
    )

    Await.result(interceptor.onResponse(response), 5.seconds)

    val events = capturingLogger.getEvents
    assertEquals(events.length, 1)

    val event = events.head
    assertEquals(event.sessionId, Some("session-123"))
    assertEquals(event.requestId, Some("req-456"))
    assertEquals(event.context, Map("clientIp" -> "192.168.1.1"))
  }

  test("AuditLogger.combine runs all loggers") {
    val logger1 = new CapturingLogger()
    val logger2 = new CapturingLogger()
    val combined = AuditLogger.combine(logger1, logger2)

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "dave"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-999")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_000_000
    )

    Await.result(combined.logDecision(event), 5.seconds)

    assertEquals(logger1.getEvents.length, 1)
    assertEquals(logger2.getEvents.length, 1)
    assertEquals(logger1.getEvents.head.principal.entityId, "dave")
    assertEquals(logger2.getEvents.head.principal.entityId, "dave")
  }
}
