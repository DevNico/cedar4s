package cedar4s.observability.audit

import munit.FunSuite
import io.circe.parser._

import java.io.ByteArrayOutputStream
import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._

class JsonAuditLoggerTest extends FunSuite {

  test("JsonAuditLogger writes valid JSON") {
    val out = new ByteArrayOutputStream()
    val logger = JsonAuditLogger.fromStream[Future](out)

    val event = AuthorizationEvent(
      timestamp = Instant.parse("2026-01-29T12:34:56.789Z"),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true, policies = List("policy-1")),
      reason = None,
      durationNanos = 1_500_000
    )

    Await.result(logger.logDecision(event), 5.seconds)

    val output = out.toString.trim
    val json = parse(output)

    assert(json.isRight, s"Expected valid JSON but got: $output")

    val jsonObj = json.toOption.get.asObject.get
    assertEquals(jsonObj("action").flatMap(_.asString), Some("Document::View"))
    assertEquals(jsonObj("allowed").flatMap(_.asBoolean), Some(true))
    assertEquals(jsonObj("denied").flatMap(_.asBoolean), Some(false))
  }

  test("JsonAuditLogger includes all event fields") {
    val out = new ByteArrayOutputStream()
    val logger = JsonAuditLogger.fromStream[Future](out)

    val event = AuthorizationEvent(
      timestamp = Instant.parse("2026-01-29T12:34:56.789Z"),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::Edit",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = false, policies = List("deny-policy")),
      reason = Some("Permission denied"),
      durationNanos = 2_500_000,
      context = Map("ip" -> "192.168.1.1"),
      sessionId = Some("session-123"),
      requestId = Some("req-456")
    )

    Await.result(logger.logDecision(event), 5.seconds)

    val output = out.toString.trim
    val json = parse(output).toOption.get.asObject.get

    // Check principal
    val principal = json("principal").get.asObject.get
    assertEquals(principal("entityType").flatMap(_.asString), Some("User"))
    assertEquals(principal("entityId").flatMap(_.asString), Some("alice"))
    assertEquals(principal("cedarUid").flatMap(_.asString), Some("""User::"alice""""))

    // Check resource
    val resource = json("resource").get.asObject.get
    assertEquals(resource("entityType").flatMap(_.asString), Some("Document"))
    assertEquals(resource("entityId").flatMap(_.asString), Some("doc-123"))

    // Check decision
    val decision = json("decision").get.asObject.get
    assertEquals(decision("allow").flatMap(_.asBoolean), Some(false))

    // Check optional fields
    assertEquals(json("reason").flatMap(_.asString), Some("Permission denied"))
    assertEquals(json("sessionId").flatMap(_.asString), Some("session-123"))
    assertEquals(json("requestId").flatMap(_.asString), Some("req-456"))
  }

  test("JsonAuditLogger pretty prints when enabled") {
    val out = new ByteArrayOutputStream()
    val logger = JsonAuditLogger.fromStream[Future](out, pretty = true)

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_000_000
    )

    Await.result(logger.logDecision(event), 5.seconds)

    val output = out.toString
    // Pretty printed JSON should have newlines and indentation
    assert(output.contains("\n"))
    assert(output.contains("  "))
  }

  test("JsonAuditLogger handles multiple events") {
    val out = new ByteArrayOutputStream()
    val logger = JsonAuditLogger.fromStream[Future](out)

    val events = (1 to 3).map { i =>
      AuthorizationEvent(
        timestamp = Instant.now(),
        principal = PrincipalInfo("User", s"user-$i"),
        action = "Document::View",
        resource = ResourceInfo("Document", Some(s"doc-$i")),
        decision = Decision(allow = true),
        reason = None,
        durationNanos = 1_000_000 * i
      )
    }

    events.foreach { event =>
      Await.result(logger.logDecision(event), 5.seconds)
    }

    val lines = out.toString.split("\n").filter(_.nonEmpty)
    assertEquals(lines.length, 3)

    // Each line should be valid JSON
    lines.foreach { line =>
      assert(parse(line).isRight, s"Expected valid JSON but got: $line")
    }
  }
}
