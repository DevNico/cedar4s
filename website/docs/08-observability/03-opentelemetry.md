---
sidebar_position: 3
title: OpenTelemetry
---

# OpenTelemetry Integration

Comprehensive distributed tracing for Cedar authorization decisions using OpenTelemetry.

## Introduction

### How cedar4s Creates Traces

The `OpenTelemetryInterceptor` automatically creates spans for each authorization check:

```
HTTP Request [500ms]
  ├─ Database Query [50ms]
  ├─ cedar.authorization [15ms]  ← Automatic
  │  ├─ cedar.principal.type: User
  │  ├─ cedar.action: Document::View
  │  ├─ cedar.decision: allow
  │  └─ cedar.duration_ms: 15
  └─ Render Response [100ms]
```

Spans include timing information, principal/action/resource attributes, decision details, and performance metrics.

## Installation and Setup

### Add Dependency

Add the OpenTelemetry module to your `build.sbt`:

```scala
libraryDependencies += "io.github.devnico" %% "cedar4s-observability-otel" % "{{VERSION}}"
```

This brings in `cedar4s-client` (core authorization) and `opentelemetry-api` (span
creation). You'll also need an OpenTelemetry SDK and exporter for your backend.

### Minimal Setup

The simplest integration uses `GlobalOpenTelemetry`:

```scala
import io.opentelemetry.api.GlobalOpenTelemetry
import cedar4s.observability.otel._
import cats.effect.IO

// Get tracer from global instance
val tracer = GlobalOpenTelemetry.getTracer("my-app")

// Create interceptor
val otelInterceptor = OpenTelemetryInterceptor[IO](tracer)

// Add to runtime
val runtime = CedarRuntime(engine, store, resolver)
  .withInterceptor(otelInterceptor)

// All authorization checks now create spans automatically
val session = runtime.session(user)
session.require(Document.View(documentId))
```

For production configuration and backend setup, see the [Backend Configuration](#backend-configuration) section and [OpenTelemetry Documentation](https://opentelemetry.io/docs/).

## Configuration

### Basic OTLP Setup

OpenTelemetry Protocol (OTLP) is the vendor-neutral format for exporting traces:

```scala
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes

val resource = Resource.create(
  Attributes.of(
    ResourceAttributes.SERVICE_NAME, "my-service",
    ResourceAttributes.SERVICE_VERSION, "1.0.0",
    ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "production"
  )
)

val otlpExporter = OtlpGrpcSpanExporter.builder()
  .setEndpoint("http://localhost:4317")
  .build()

val tracerProvider = SdkTracerProvider.builder()
  .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
  .setResource(resource)
  .build()

val openTelemetry = OpenTelemetrySdk.builder()
  .setTracerProvider(tracerProvider)
  .buildAndRegisterGlobal()
```

### Resource Attributes

Resources describe your service for proper trace organization:

```scala
val resource = Resource.create(
  Attributes.of(
    ResourceAttributes.SERVICE_NAME, "authorization-service",
    ResourceAttributes.SERVICE_VERSION, "2.1.0",
    ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "production"
  )
)
```

### Sampling

Control which traces are recorded:

```scala
import io.opentelemetry.sdk.trace.samplers.Sampler

val sampler = Sampler.parentBased(Sampler.traceIdRatioBased(0.1))

val tracerProvider = SdkTracerProvider.builder()
  .setSampler(sampler)
  .addSpanProcessor(batchProcessor)
  .build()
```

**Recommendations**:
- Development: 100% sampling
- Staging: 50% sampling
- Production (low traffic): 10-25% sampling
- Production (high traffic): 1-5% sampling

## Cedar Semantic Conventions

Cedar4s follows OpenTelemetry semantic conventions for attribute naming. All Cedar
attributes use the `cedar.` namespace.

| Attribute | Description |
|-----------|-------------|
| `cedar.principal.type` | Entity type (e.g., "User") |
| `cedar.principal.id` | Entity ID (may contain PII) |
| `cedar.action` | Full Cedar action |
| `cedar.action.name` | Action name only |
| `cedar.resource.type` | Entity type (e.g., "Document") |
| `cedar.resource.id` | Entity ID (may be sensitive) |
| `cedar.decision` | "allow" or "deny" |
| `cedar.deny_reason` | Reason for denial (if denied) |
| `cedar.duration_ms` | Duration in milliseconds |
| `cedar.entities.count` | Entities loaded for decision |

## Span Naming Strategies

Span names should be **low cardinality** (few unique values) to enable grouping and aggregation.

### Default Strategy

Names all spans `cedar.authorization`:

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  spanNamingStrategy = SpanNamingStrategy.default
)
```

### By Action

Name spans by action type:

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  spanNamingStrategy = SpanNamingStrategy.byAction
)
// Produces: cedar.authorization.View, cedar.authorization.Edit, etc.
```

### By Resource Type

Name spans by resource type:

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  spanNamingStrategy = SpanNamingStrategy.byResourceType
)
// Produces: cedar.authorization.Document, cedar.authorization.Folder, etc.
```

### Custom Strategy

Implement arbitrary naming logic:

```scala
val customStrategy = SpanNamingStrategy { response =>
  val prefix = response.principal.entityType match {
    case "ServiceAccount" => "cedar.service"
    case "User" => "cedar.user"
    case _ => "cedar.other"
  }
  s"$prefix.${response.action.name}"
}
```

**Never** include high-cardinality values in span names (entity IDs, user IDs,
request IDs). Use **attributes** for high-cardinality values, not span names.

## Privacy Controls

`AttributeFilter` controls which attributes are included in spans.

### Include All (Default)

Include all standard Cedar attributes:

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  attributeFilter = AttributeFilter.includeAll
)
```

### Minimal Filter

Include only essential attributes (excludes all entity IDs, full Cedar UIDs, deny reasons, and context information):

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  attributeFilter = AttributeFilter.minimal
)
```

### Exclude Entity IDs

Hide both principal and resource IDs:

```scala
val interceptor = OpenTelemetryInterceptor[IO](
  tracer,
  attributeFilter = AttributeFilter.excludeEntityIds
)
```

### Custom Filter

Implement arbitrary filtering logic:

```scala
val customFilter = AttributeFilter { (attributeName, response) =>
  attributeName match {
    case SemanticConventions.CEDAR_PRINCIPAL_ID if isProd => false
    case SemanticConventions.CEDAR_RESOURCE_ID =>
      !response.resource.entityType.contains("Sensitive")
    case _ => true
  }
}
```

Additional filters available: `excludePrincipalIds`, `excludeResourceIds`, `excludeDenyReasons`.

## Integration Patterns

### HTTP Request Traces

Nest authorization spans within HTTP request traces:

```scala
import org.http4s._
import cats.effect.IO

def documentRoutes(
  session: CedarSession[IO],
  tracer: Tracer
): HttpRoutes[IO] = HttpRoutes.of[IO] {

  case GET -> Root / "documents" / documentId =>
    for {
      _ <- session.require(Document.View(documentId))
      document <- fetchDocument(documentId)
      response <- Ok(document)
    } yield response
}

// Trace structure:
// HTTP GET /documents/doc-123 [150ms]
//   ├─ cedar.authorization [5ms]
//   ├─ database.query [50ms]
//   └─ render [95ms]
```

### Cross-Service Tracing

OpenTelemetry automatically propagates trace context via HTTP headers when using auto-instrumented HTTP clients:

```scala
// Service A
def callServiceB[F[_]: Sync](
  client: Client[F],
  session: CedarSession[F]
): F[Response] = {
  for {
    _ <- session.require(External.CallServiceB())
    response <- client.get("http://service-b/api/data")(identity)
  } yield response
}

// Complete trace across services:
// Service A: call_service_b [200ms]
//   ├─ cedar.authorization [5ms]
//   └─ HTTP POST /api/data [195ms]
//      ├─ cedar.authorization [3ms]
//      └─ database.query [192ms]
```

### Batch Operations

Track batch authorization checks:

```scala
def batchAuthorize[F[_]: Sync](
  documentIds: List[String],
  session: CedarSession[F],
  tracer: Tracer
): F[List[Boolean]] = {
  val span = tracer.spanBuilder("batch_authorize")
    .setAttribute("batch.size", documentIds.size.toLong)
    .startSpan()
  val scope = span.makeCurrent()

  try {
    for {
      results <- documentIds.traverse { id =>
        session.check(Document.View(id))
      }
    } yield {
      val allowed = results.count(identity)
      span.setAttribute("batch.allowed", allowed.toLong)
      results
    }
  } finally {
    scope.close()
    span.end()
  }
}
```

## Backend Configuration

Cedar4s uses standard OTLP exporters that work with any OpenTelemetry-compatible backend.

### Generic OTLP Configuration

```scala
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter

val exporter = OtlpGrpcSpanExporter.builder()
  .setEndpoint("http://your-collector:4317")
  .addHeader("authorization", "Bearer YOUR_API_KEY")
  .build()
```

### Popular Backends

| Backend | Endpoint | Documentation |
|---------|----------|---------------|
| **Jaeger** | `http://localhost:4317` | [Jaeger OTLP](https://www.jaegertracing.io/docs/latest/apis/#opentelemetry-protocol-stable) |
| **Zipkin** | `http://localhost:9411/api/v2/spans` | [Zipkin OpenTelemetry](https://zipkin.io/pages/instrumenting.html#opentelemetry) |
| **Honeycomb** | `https://api.honeycomb.io/v1/traces` | [Honeycomb OTLP](https://docs.honeycomb.io/send-data/opentelemetry/) |
| **Datadog** | `http://localhost:4318/v1/traces` | [Datadog OTLP](https://docs.datadoghq.com/opentelemetry/) |
| **New Relic** | `https://otlp.nr-data.net:4318/v1/traces` | [New Relic OTLP](https://docs.newrelic.com/docs/opentelemetry/get-started/opentelemetry-set-up-your-app/) |
| **AWS X-Ray** | via OTel Collector | [X-Ray OpenTelemetry](https://aws-otel.github.io/docs/getting-started/x-ray) |
| **Google Cloud Trace** | Uses Cloud Trace exporter | [GCP Trace](https://cloud.google.com/trace/docs/setup/opentelemetry) |

## Production Best Practices

- **Use Batch Span Processor**: Always use batch processing for async export, never `SimpleSpanProcessor`
- **Configure Appropriate Sampling**: Use parent-based sampling, typically 1-10% in production
- **Minimize Attribute Cardinality**: Use `AttributeFilter` to reduce span size
- **Graceful Shutdown**: Ensure spans are exported before shutdown with `Resource.make`
- **Environment-Specific Configuration**: Use different sampling and filtering per environment

## Troubleshooting

| Problem | Solutions |
|---------|-----------|
| **Spans not appearing** | Verify SDK is initialized; Check interceptor is added; Verify sampling; Check exporter configuration |
| **Missing attributes** | Check attribute filter; Temporarily use `AttributeFilter.includeAll` |
| **High cardinality warnings** | Use low-cardinality span names; Move high-cardinality data to attributes; Use `AttributeFilter.excludeEntityIds` |
| **Performance impact** | Use `BatchSpanProcessor`; Reduce sampling rate; Use `AttributeFilter.minimal` |
| **Context not propagating** | Verify W3C Trace Context propagators; Check HTTP headers contain `traceparent` |

