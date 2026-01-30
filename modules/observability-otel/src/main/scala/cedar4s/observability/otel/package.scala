package cedar4s.observability

/** OpenTelemetry integration for cedar4s.
  *
  * This package provides distributed tracing support for Cedar authorization checks using OpenTelemetry. It creates
  * spans for each authorization decision with:
  *
  *   - **Cedar-specific attributes**: Principal, action, resource, decision, timing
  *   - **Flexible span naming**: Group spans by action, resource type, or custom logic
  *   - **Privacy controls**: Filter sensitive attributes from traces
  *   - **Event recording**: Log denials and errors as span events
  *   - **Context propagation**: Automatic integration with distributed traces
  *
  * ==Quick Start==
  *
  * {{{
  * import io.opentelemetry.api.GlobalOpenTelemetry
  * import cedar4s.observability.otel._
  *
  * // Create tracer
  * val tracer = GlobalOpenTelemetry.getTracer("my-app")
  *
  * // Create interceptor
  * val otelInterceptor = OpenTelemetryInterceptor[IO](tracer)
  *
  * // Add to runtime
  * val runtime = CedarRuntime(engine, store, resolver)
  *   .withInterceptor(otelInterceptor)
  * }}}
  *
  * ==Custom Configuration==
  *
  * {{{
  * val interceptor = OpenTelemetryInterceptor[IO](
  *   tracer = tracer,
  *   spanNamingStrategy = SpanNamingStrategy.byAction,
  *   attributeFilter = AttributeFilter.excludePrincipalIds
  * )
  * }}}
  *
  * @see
  *   [[OpenTelemetryInterceptor]] for the main interceptor
  * @see
  *   [[SpanNamingStrategy]] for span naming options
  * @see
  *   [[AttributeFilter]] for privacy controls
  * @see
  *   [[SemanticConventions]] for attribute names
  */
package object otel {
  // Package object provides a namespace for convenient imports
}
