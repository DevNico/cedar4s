# cedar4s-observability-otel

OpenTelemetry distributed tracing integration for Cedar authorization decisions.

[Full Documentation →](https://devnico.github.io/cedar4s/observability/otel)

## Installation

```scala
libraryDependencies += "io.github.devnico" %% "cedar4s-observability-otel" % "0.1.0-SNAPSHOT"
```

## Quick Start

```scala
import io.opentelemetry.api.GlobalOpenTelemetry
import cedar4s.observability.otel._

// Create tracer
val tracer = GlobalOpenTelemetry.getTracer("my-app")

// Create interceptor
val otelInterceptor = OpenTelemetryInterceptor[IO](tracer)

// Add to runtime
val runtime = CedarRuntime(engine, store, resolver)
  .withInterceptor(otelInterceptor)

// Authorization checks now create spans automatically
val session = runtime.session(user)
session.require(Document.View(documentId))
```

