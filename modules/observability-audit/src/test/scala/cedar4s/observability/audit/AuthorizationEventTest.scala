package cedar4s.observability.audit

import munit.FunSuite

import java.time.Instant

class AuthorizationEventTest extends FunSuite {

  test("PrincipalInfo.toCedarUid formats correctly") {
    val principal = PrincipalInfo("User", "alice")
    assertEquals(principal.toCedarUid, """User::"alice"""")
  }

  test("ResourceInfo.toCedarUid formats with entity ID") {
    val resource = ResourceInfo("Document", Some("doc-123"))
    assertEquals(resource.toCedarUid, """Document::"doc-123"""")
  }

  test("ResourceInfo.toCedarUid formats without entity ID") {
    val resource = ResourceInfo("Document", None)
    assertEquals(resource.toCedarUid, "Document")
  }

  test("Decision.policiesSatisfied returns policies when allowed") {
    val decision = Decision(allow = true, policies = List("policy-1", "policy-2"))
    assertEquals(decision.policiesSatisfied, List("policy-1", "policy-2"))
    assertEquals(decision.policiesDenied, Nil)
  }

  test("Decision.policiesDenied returns policies when denied") {
    val decision = Decision(allow = false, policies = List("policy-3"))
    assertEquals(decision.policiesDenied, List("policy-3"))
    assertEquals(decision.policiesSatisfied, Nil)
  }

  test("AuthorizationEvent.durationMs converts from nanos correctly") {
    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_500_000 // 1.5 ms
    )

    assertEquals(event.durationMs, 1.5)
  }

  test("AuthorizationEvent.durationMicros converts from nanos correctly") {
    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_500_000 // 1500 microseconds
    )

    assertEquals(event.durationMicros, 1500L)
  }

  test("AuthorizationEvent.allowed and denied are correct") {
    val allowedEvent = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_000_000
    )

    assert(allowedEvent.allowed)
    assert(!allowedEvent.denied)

    val deniedEvent = allowedEvent.copy(decision = Decision(allow = false))
    assert(!deniedEvent.allowed)
    assert(deniedEvent.denied)
  }

  test("AuthorizationEvent includes optional fields") {
    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::Edit",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = false, policies = List("deny-all")),
      reason = Some("User lacks edit permission"),
      durationNanos = 2_000_000,
      context = Map("clientIp" -> "192.168.1.1", "userAgent" -> "Mozilla/5.0"),
      sessionId = Some("session-456"),
      requestId = Some("req-789")
    )

    assertEquals(event.reason, Some("User lacks edit permission"))
    assertEquals(event.context, Map("clientIp" -> "192.168.1.1", "userAgent" -> "Mozilla/5.0"))
    assertEquals(event.sessionId, Some("session-456"))
    assertEquals(event.requestId, Some("req-789"))
  }
}
