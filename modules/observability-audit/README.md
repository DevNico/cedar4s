# cedar4s-observability-audit

Comprehensive audit logging for Cedar authorization decisions with structured JSON output.

[Full Documentation →](https://devnico.github.io/cedar4s/observability/audit)

## Installation

```scala
libraryDependencies ++= Seq(
  "io.github.devnico" %% "cedar4s-observability-audit" % "0.1.0-SNAPSHOT",
  "ch.qos.logback" % "logback-classic" % "1.5.15",
  "net.logstash.logback" % "logstash-logback-encoder" % "8.0"
)
```

## Quick Start

```scala
import cedar4s.observability.audit._

// Create SLF4J audit logger
val auditLogger = Slf4jAuditLogger[Future]()

// Integrate with Cedar runtime
val interceptor = AuditInterceptor(auditLogger)
val runtime = CedarRuntime(engine, store, resolver)
  .withInterceptor(interceptor)

// All authorization checks are automatically logged
val session = runtime.session(user)
session.check(action, resource).require
```

