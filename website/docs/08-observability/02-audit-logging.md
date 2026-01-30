---
sidebar_position: 2
title: Audit Logging
---

# Audit Logging

Production-ready audit logging for Cedar authorization decisions with structured JSON output.

## Introduction

Production-ready audit logging for Cedar authorization decisions with structured JSON output.

## Installation

Add the audit logging module to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  // Core audit logging
  "io.github.devnico" %% "cedar4s-observability-audit" % "{{VERSION}}",

  // For SLF4J with Logback (recommended for production)
  "ch.qos.logback" % "logback-classic" % "1.5.15",
  "net.logstash.logback" % "logstash-logback-encoder" % "8.0"
)
```

### Minimal Setup (No External Dependencies)

For simple use cases without external logging frameworks:

```scala
libraryDependencies += "io.github.devnico" %% "cedar4s-observability-audit" % "{{VERSION}}"
```

## Quick Start

### Basic Usage

```scala
import cedar4s.observability.audit._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._

// Create an SLF4J audit logger (recommended)
val auditLogger = Slf4jAuditLogger[Future]()

// Create an audit interceptor
val interceptor = AuditInterceptor(auditLogger)

// Integrate with Cedar runtime
val runtime = CedarRuntime(engine, store, resolver)
  .withInterceptor(interceptor)

// All authorization checks are now automatically logged
val session = runtime.session(user)
session.check(action, resource).require
```

That's it! Every authorization decision will now be logged with full context.

## Logger Implementations

| Logger | Use Case | Key Features |
|--------|----------|--------------|
| **Slf4jAuditLogger** | Production | Async, rotation, SLF4J integration |
| **JsonAuditLogger** | Development | No dependencies, direct output |
| **FileAuditLogger** | Simple deployments | Built-in rotation |
| **NoOpAuditLogger** | Testing | Zero overhead |

## Configuration

### Production Logback Configuration

Create `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <appender name="ASYNC_AUDIT" class="ch.qos.logback.classic.AsyncAppender">
    <neverBlock>true</neverBlock>
    <queueSize>1024</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <appender-ref ref="AUDIT_FILE"/>
  </appender>

  <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/var/log/cedar/audit.json</file>

    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>/var/log/cedar/audit-%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>90</maxHistory>
      <totalSizeCap>50GB</totalSizeCap>
    </rollingPolicy>

    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <prettyPrint>false</prettyPrint>
      <timestampPattern>yyyy-MM-dd'T'HH:mm:ss.SSSX</timestampPattern>
      <customFields>{"service":"cedar-authz","environment":"production"}</customFields>
    </encoder>
  </appender>

  <logger name="cedar4s.audit" level="INFO" additivity="false">
    <appender-ref ref="ASYNC_AUDIT"/>
  </logger>

  <root level="WARN">
    <appender-ref ref="CONSOLE"/>
  </root>

</configuration>
```

### Development Logback Configuration

For development, use simpler configuration with console output:

```xml
<configuration>
  <appender name="AUDIT_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <prettyPrint>true</prettyPrint>
    </encoder>
  </appender>

  <logger name="cedar4s.audit" level="DEBUG" additivity="false">
    <appender-ref ref="AUDIT_CONSOLE"/>
  </logger>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

### Event Format

The `logstash-logback-encoder` produces structured JSON events:

```json
{
  "@timestamp": "2026-01-30T10:15:32.456Z",
  "timestamp": "2026-01-30T10:15:32.456Z",
  "principal": {
    "entityType": "User",
    "entityId": "alice",
    "cedarUid": "User::\"alice\""
  },
  "action": "Document::View",
  "resource": {
    "entityType": "Document",
    "entityId": "doc-123",
    "cedarUid": "Document::\"doc-123\""
  },
  "decision": {
    "allow": true,
    "policies": ["policy-allow-view"],
    "policiesSatisfied": ["policy-allow-view"],
    "policiesDenied": []
  },
  "allowed": true,
  "denied": false,
  "durationMs": 1.5,
  "durationNanos": 1500000,
  "sessionId": "session-abc-123",
  "requestId": "req-xyz-789",
  "context": {
    "clientIp": "192.168.1.1",
    "userAgent": "Mozilla/5.0"
  },
  "service": "cedar-authz",
  "environment": "production"
}
```

## Integration Patterns

### HTTP Frameworks

Extract HTTP context into audit events:

```scala
import cedar4s.observability.audit._
import org.http4s._
import cats.effect.IO

def createAuditInterceptor(
  auditLogger: AuditLogger[IO]
): Request[IO] => AuditInterceptor[IO] = { request =>

  AuditInterceptor.withExtractors[IO](
    logger = auditLogger,
    sessionIdExtractor = _ => {
      request.headers.get[Cookie]
        .flatMap(_.values.toList.find(_.name == "sessionId"))
        .map(_.content)
    },
    requestIdExtractor = _ => request.headers.get[`X-Request-ID`].map(_.id),
    contextExtractor = _ => Map(
      "clientIp" -> request.remoteAddr.getOrElse("unknown"),
      "userAgent" -> request.headers.get[`User-Agent`].map(_.value).getOrElse("unknown")
    )
  )
}
```

### Multi-Tenant Applications

```scala
import cedar4s.observability.audit._
import scala.concurrent.Future

case class TenantContext(tenantId: String, tier: String)

def createTenantAuditInterceptor(
  auditLogger: AuditLogger[Future],
  getTenantContext: () => TenantContext
): AuditInterceptor[Future] = {

  AuditInterceptor.withExtractors[Future](
    logger = auditLogger,
    sessionIdExtractor = _ => Some(s"tenant-${getTenantContext().tenantId}"),
    contextExtractor = _ => {
      val tenant = getTenantContext()
      Map("tenantId" -> tenant.tenantId, "tier" -> tenant.tier)
    }
  )
}
```

## Production Considerations

- **Use Async Appenders**: Configure async appenders with appropriate queue sizes and set `neverBlock=true`
- **Configure Retention Policies**: Common periods: SOC 2 (90 days), HIPAA (6 years), PCI-DSS (1 year)
- **Monitor Log Volume**: Track audit log volume and set up alerts for dropped events and disk space
- **Secure Log Files**: Use restrictive permissions, encryption at rest, and SELinux/AppArmor policies
- **Forward to SIEM**: Integrate with centralized logging systems like Splunk, Datadog, or rsyslog
- **Add Service Metadata**: Include deployment information in all events using custom fields

For detailed production deployment guides, see:
- [SLF4J Documentation](https://www.slf4j.org/manual.html)
- [Logback Configuration](https://logback.qos.ch/manual/configuration.html)
- [Async Appenders Best Practices](https://logback.qos.ch/manual/appenders.html#AsyncAppender)

