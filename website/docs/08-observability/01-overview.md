# Observability Overview

Cedar4s provides production-ready observability through two complementary approaches:

- **Audit Logging** (`observability-audit`) - Structured event logging for compliance and security analysis
- **OpenTelemetry** (`observability-otel`) - Distributed tracing for performance monitoring and debugging

Both modules are **effect-agnostic**, using only `cedar4s.capability` typeclasses to maintain framework flexibility.

## Quick Decision Guide

| Use Case | Recommendation |
|----------|---------------|
| Compliance requirements (SOC2, HIPAA, PCI-DSS) | Audit Logging |
| Security incident investigation | Audit Logging |
| Access pattern analysis | Audit Logging |
| Performance debugging | OpenTelemetry |
| Distributed tracing across services | OpenTelemetry |
| Request correlation | OpenTelemetry |
| Combining both for comprehensive observability | Both (recommended) |

## Audit Logging vs OpenTelemetry

### Audit Logging

**Purpose**: Permanent record of authorization decisions for compliance, security, and forensics.

**When to use**:
- Compliance requirements mandate access logging
- Security incident investigation and response
- Long-term retention requirements (months to years)

**Characteristics**:
- Every decision is logged (100% sampling)
- Permanent storage with retention policies
- Integration with SIEM systems

### OpenTelemetry

**Purpose**: Observability and performance monitoring through distributed tracing.

**When to use**:
- Understanding authorization check performance
- Debugging slow requests across distributed systems
- Performance optimization

**Characteristics**:
- Creates spans for each authorization check
- Sampled (typically &lt;10% in production)
- Short-term retention (hours to days)

## Complementary Use

Use both for comprehensive observability. The interceptors work independently:
- **Audit logs** capture every decision for compliance
- **Traces** provide sampled performance visibility

## Performance Impact

Both modules are designed for minimal performance overhead:

**Audit Logging**:
- Async appenders prevent blocking
- Typical overhead: &lt;1ms per decision

**OpenTelemetry**:
- Lightweight span creation
- Typical overhead: &lt;0.5ms per decision
- Further reduced by sampling in production

## Effect Type Support

Both modules work with any effect type through the `cedar4s.capability` system:

```scala
// Cats Effect
import cats.effect.IO
import cedar4s.capability.instances.catsEffect._

val auditLogger = Slf4jAuditLogger[IO]()
val otelInterceptor = OpenTelemetryInterceptor[IO](tracer)
```

Works identically with ZIO, Future, Monix, etc.

## Privacy Considerations

### Audit Logging

Audit logs may contain sensitive data (user identifiers, resource identifiers, context attributes).

**Recommendations**:
- Encrypt audit logs at rest and in transit
- Apply appropriate access controls
- Consider GDPR/privacy requirements for PII
- Implement data retention policies
- Use secure log aggregation systems

### OpenTelemetry

Traces may be exported to third-party services. Use `AttributeFilter` to control
which attributes are included in spans. See
[AttributeFilter documentation](./03-opentelemetry.md#privacy-controls) for details.

## Next Steps

- [Audit Logging Guide](./02-audit-logging.md) - Complete guide to audit logging with SLF4J
- [OpenTelemetry Guide](./03-opentelemetry.md) - Complete guide to distributed tracing

## Dependencies

Add to your `build.sbt`:

```scala
// Audit logging
libraryDependencies += "io.github.devnico" %% "cedar4s-observability-audit" % "{{VERSION}}"

// OpenTelemetry
libraryDependencies += "io.github.devnico" %% "cedar4s-observability-otel" % "{{VERSION}}"
```

Both modules have minimal dependencies:
- **observability-audit**: Circe, SLF4J API
- **observability-otel**: OpenTelemetry API

Backend configuration (Logback, OTLP exporters) is up to you.

