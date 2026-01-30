package cedar4s.observability.audit

import munit.FunSuite

import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._

class Slf4jExampleUsageTest extends FunSuite {

  test("Slf4jAuditLogger example: basic usage") {
    // Create an SLF4J audit logger with default settings
    val auditLogger = Slf4jAuditLogger[Future]()

    // Create an authorization event
    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true, policies = List("allow-view")),
      reason = None,
      durationNanos = 1_234_567
    )

    // Log the event
    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: denied access with details") {
    // Use WARN level for denied access events
    val auditLogger = Slf4jAuditLogger[Future](
      level = LogLevel.Warn
    )

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "bob"),
      action = "Document::Delete",
      resource = ResourceInfo("Document", Some("doc-456")),
      decision = Decision(allow = false, policies = List("deny-delete")),
      reason = Some("User lacks delete permissions"),
      durationNanos = 987_654,
      context = Map(
        "clientIp" -> "192.168.1.100",
        "userAgent" -> "Mozilla/5.0"
      ),
      sessionId = Some("session-abc-123"),
      requestId = Some("req-xyz-789")
    )

    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: custom logger name") {
    // Use a custom logger name for organization-specific logging
    val auditLogger = Slf4jAuditLogger[Future](
      loggerName = "com.mycompany.security.audit",
      level = LogLevel.Info
    )

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("ServiceAccount", "payment-service"),
      action = "PaymentData::Read",
      resource = ResourceInfo("PaymentData", Some("payment-12345")),
      decision = Decision(allow = true, policies = List("service-access")),
      reason = None,
      durationNanos = 2_345_678
    )

    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: multiple loggers for different purposes") {
    // Create separate loggers for different audit purposes
    val generalAuditLogger = Slf4jAuditLogger[Future](
      loggerName = "cedar4s.audit.general",
      level = LogLevel.Info
    )

    val securityAuditLogger = Slf4jAuditLogger[Future](
      loggerName = "cedar4s.audit.security",
      level = LogLevel.Warn
    )

    val allowedEvent = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-public")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 500_000
    )

    val deniedEvent = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "eve"),
      action = "AdminPanel::Access",
      resource = ResourceInfo("AdminPanel", Some("settings")),
      decision = Decision(allow = false, policies = List("admin-only")),
      reason = Some("User is not an administrator"),
      durationNanos = 750_000,
      context = Map("severity" -> "high")
    )

    // Log allowed access to general audit log
    Await.result(generalAuditLogger.logDecision(allowedEvent), 5.seconds)

    // Log denied access to security audit log
    Await.result(securityAuditLogger.logDecision(deniedEvent), 5.seconds)
  }

  test("Slf4jAuditLogger example: plain text fallback") {
    // Disable structured logging to use plain text format
    val auditLogger = Slf4jAuditLogger[Future](
      useStructuredLogging = false
    )

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "charlie"),
      action = "File::Download",
      resource = ResourceInfo("File", Some("report.pdf")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_500_000,
      sessionId = Some("session-def-456"),
      requestId = Some("req-uvw-012")
    )

    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: combining multiple audit destinations") {
    // Create multiple loggers and combine them
    val slf4jLogger = Slf4jAuditLogger[Future](
      loggerName = "cedar4s.audit"
    )

    val consoleLogger = JsonAuditLogger.stdout[Future]()

    // Combine both loggers
    val combinedLogger = AuditLogger.combine(slf4jLogger, consoleLogger)

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "admin"),
      action = "Config::Update",
      resource = ResourceInfo("Config", Some("security-settings")),
      decision = Decision(allow = true, policies = List("admin-policy")),
      reason = None,
      durationNanos = 3_456_789,
      context = Map("environment" -> "production")
    )

    // Event will be logged to both SLF4J and stdout
    Await.result(combinedLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: resource without entity ID") {
    val auditLogger = Slf4jAuditLogger[Future]()

    // Collection-level action (no specific entity)
    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "reader"),
      action = "Document::List",
      resource = ResourceInfo("Document", None), // No specific document
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 800_000
    )

    Await.result(auditLogger.logDecision(event), 5.seconds)
  }

  test("Slf4jAuditLogger example: debug level logging") {
    // Use Debug level for development and troubleshooting
    val auditLogger = Slf4jAuditLogger[Future](
      level = LogLevel.Debug
    )

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("Developer", "dev-user"),
      action = "Debug::Trace",
      resource = ResourceInfo("DebugInfo", Some("trace-1")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 100_000,
      context = Map("debug" -> "true", "trace" -> "enabled")
    )

    Await.result(auditLogger.logDecision(event), 5.seconds)
  }
}
