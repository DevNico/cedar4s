package cedar4s.observability.otel.examples

import cedar4s.auth.{CedarAction, CedarResource, ContextSchema, Principal}
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.EntityStore
import cedar4s.observability.otel._
import cedar4s.schema.CedarEntityUid
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter

/** Example demonstrating OpenTelemetry integration with cedar4s.
  *
  * This example shows:
  *   1. Setting up OpenTelemetry SDK
  *   2. Creating a traced CedarRuntime
  *   3. Performing authorization checks with automatic tracing
  *   4. Viewing the resulting spans
  *
  * ==Setup==
  *
  * {{{
  * val otel = OpenTelemetryExample.setupOpenTelemetry()
  * val tracer = otel.getTracer("my-app")
  * }}}
  *
  * ==Basic Usage==
  *
  * {{{
  * val interceptor = OpenTelemetryInterceptor[IO](tracer)
  * val runtime = CedarRuntime(engine, store, resolver)
  *   .withInterceptor(interceptor)
  *
  * // Authorization checks now create spans automatically
  * val session = runtime.session(user)
  * session.require(Document.View("doc-1"))
  * }}}
  *
  * ==Custom Configuration==
  *
  * {{{
  * // Group spans by action type
  * val interceptor = OpenTelemetryInterceptor[IO](
  *   tracer,
  *   SpanNamingStrategy.byAction
  * )
  *
  * // Exclude sensitive IDs
  * val privacyInterceptor = OpenTelemetryInterceptor[IO](
  *   tracer,
  *   SpanNamingStrategy.byAction,
  *   AttributeFilter.excludePrincipalIds
  * )
  * }}}
  *
  * ==Production Setup==
  *
  * In production, you would typically:
  *   1. Use an exporter like OTLP, Jaeger, or Zipkin
  *   2. Configure sampling to control costs
  *   3. Add resource attributes (service.name, deployment.environment)
  *   4. Enable context propagation for distributed tracing
  *
  * {{{
  * import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
  * import io.opentelemetry.sdk.resources.Resource
  * import io.opentelemetry.semconv.ResourceAttributes
  *
  * val resource = Resource.getDefault.merge(
  *   Resource.create(
  *     Attributes.of(
  *       ResourceAttributes.SERVICE_NAME, "my-service",
  *       ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "production"
  *     )
  *   )
  * )
  *
  * val otlpExporter = OtlpGrpcSpanExporter.builder()
  *   .setEndpoint("http://localhost:4317")
  *   .build()
  *
  * val tracerProvider = SdkTracerProvider.builder()
  *   .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
  *   .setResource(resource)
  *   .build()
  *
  * val openTelemetry = OpenTelemetrySdk.builder()
  *   .setTracerProvider(tracerProvider)
  *   .buildAndRegisterGlobal()
  * }}}
  */
object OpenTelemetryExample {

  /** Setup OpenTelemetry SDK for testing/examples.
    *
    * This uses an in-memory exporter so you can inspect spans programmatically. In production, you'd use OTLP, Jaeger,
    * Zipkin, etc.
    */
  def setupOpenTelemetry(): (OpenTelemetrySdk, InMemorySpanExporter) = {
    val spanExporter = InMemorySpanExporter.create()

    val tracerProvider = SdkTracerProvider
      .builder()
      .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
      .build()

    val openTelemetry = OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      .build()

    (openTelemetry, spanExporter)
  }

  /** Example: Basic integration
    */
  def basicExample[F[_]](
      engine: CedarEngine[F],
      store: EntityStore[F],
      tracer: Tracer
  )(implicit F: cedar4s.capability.Sync[F]): CedarRuntime[F, TestUser] = {

    // Create interceptor
    val otelInterceptor = OpenTelemetryInterceptor[F](tracer)

    // Create runtime with interceptor
    val resolver = CedarRuntime.resolverFrom[F, TestUser] { principal =>
      // Resolve principal to entity
      F.pure(Some(TestUser(principal.entityId)))
    }

    CedarRuntime(engine, store, resolver)
      .withInterceptor(otelInterceptor)
  }

  /** Example: Custom span naming and filtering
    */
  def customConfigExample[F[_]](
      engine: CedarEngine[F],
      store: EntityStore[F],
      tracer: Tracer
  )(implicit F: cedar4s.capability.Sync[F]): CedarRuntime[F, TestUser] = {

    // Group spans by action type for better organization
    val spanNaming = SpanNamingStrategy.byAction

    // Exclude principal IDs for privacy
    val attributeFilter = AttributeFilter.excludePrincipalIds

    val otelInterceptor = OpenTelemetryInterceptor[F](
      tracer,
      spanNaming,
      attributeFilter
    )

    val resolver = CedarRuntime.resolverFrom[F, TestUser] { principal =>
      F.pure(Some(TestUser(principal.entityId)))
    }

    CedarRuntime(engine, store, resolver)
      .withInterceptor(otelInterceptor)
  }

  /** Example: Advanced filtering based on environment
    */
  def environmentAwareExample[F[_]](
      engine: CedarEngine[F],
      store: EntityStore[F],
      tracer: Tracer,
      environment: String
  )(implicit F: cedar4s.capability.Sync[F]): CedarRuntime[F, TestUser] = {

    // Different filtering strategies per environment
    val attributeFilter = environment match {
      case "production" =>
        // Production: exclude all entity IDs for privacy
        AttributeFilter.excludeEntityIds

      case "staging" =>
        // Staging: exclude principal IDs but allow resource IDs for debugging
        AttributeFilter.excludePrincipalIds

      case _ =>
        // Development: include everything for full observability
        AttributeFilter.includeAll
    }

    val otelInterceptor = OpenTelemetryInterceptor[F](
      tracer,
      SpanNamingStrategy.byActionAndResourceType,
      attributeFilter
    )

    val resolver = CedarRuntime.resolverFrom[F, TestUser] { principal =>
      F.pure(Some(TestUser(principal.entityId)))
    }

    CedarRuntime(engine, store, resolver)
      .withInterceptor(otelInterceptor)
  }

  /** Example: Combining multiple interceptors
    */
  def multipleInterceptorsExample[F[_]](
      engine: CedarEngine[F],
      store: EntityStore[F],
      tracer: Tracer
  )(implicit F: cedar4s.capability.Sync[F]): CedarRuntime[F, TestUser] = {

    // OpenTelemetry tracing
    val otelInterceptor = OpenTelemetryInterceptor[F](tracer)

    // Custom audit logger (example - you'd implement this)
    val auditLogger = new cedar4s.client.AuthInterceptor[F] {
      def onResponse(response: cedar4s.client.AuthorizationResponse): F[Unit] = {
        if (response.denied) {
          F.delay {
            println(s"AUDIT: Denied ${response.action.name} on ${response.resource.entityType}")
          }
        } else {
          F.pure(())
        }
      }
    }

    val resolver = CedarRuntime.resolverFrom[F, TestUser] { principal =>
      F.pure(Some(TestUser(principal.entityId)))
    }

    // Combine both interceptors
    CedarRuntime(engine, store, resolver)
      .withInterceptor(otelInterceptor)
      .withInterceptor(auditLogger)
  }

  // ===========================================================================
  // Test Domain
  // ===========================================================================

  case class TestUser(id: String)

  implicit val testUserEntityType: cedar4s.entities.CedarEntityType[TestUser] =
    new cedar4s.entities.CedarEntityType[TestUser] {
      type Id = String
      def entityType: String = "User"
      def toCedarEntity(entity: TestUser): cedar4s.entities.CedarEntity = {
        cedar4s.entities.CedarEntity(
          entityType = "User",
          entityId = entity.id,
          attributes = Map.empty,
          parents = Set.empty
        )
      }
      def getParentIds(entity: TestUser): List[(String, String)] = Nil
    }
}
