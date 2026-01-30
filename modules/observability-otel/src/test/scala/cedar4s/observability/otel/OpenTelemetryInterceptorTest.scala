package cedar4s.observability.otel

import cedar4s.capability.Sync
import io.opentelemetry.api.trace.{SpanKind, StatusCode, Tracer}
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import munit.FunSuite

import scala.jdk.CollectionConverters._

class OpenTelemetryInterceptorTest extends FunSuite {
  import TestFixtures._

  // ===========================================================================
  // Test Fixtures
  // ===========================================================================

  var spanExporter: InMemorySpanExporter = _
  var openTelemetry: OpenTelemetrySdk = _

  override def beforeEach(context: BeforeEach): Unit = {
    spanExporter = InMemorySpanExporter.create()
    val tracerProvider = SdkTracerProvider
      .builder()
      .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
      .build()
    openTelemetry = OpenTelemetrySdk
      .builder()
      .setTracerProvider(tracerProvider)
      .build()
  }

  override def afterEach(context: AfterEach): Unit = {
    if (openTelemetry != null) {
      openTelemetry.close()
    }
  }

  // Simple effect type for testing
  case class TestIO[A](run: () => A) {
    def map[B](f: A => B): TestIO[B] = TestIO(() => f(run()))
    def flatMap[B](f: A => TestIO[B]): TestIO[B] = TestIO(() => f(run()).run())
  }

  implicit val testSync: Sync[TestIO] = new Sync[TestIO] {
    def pure[A](a: A): TestIO[A] = TestIO(() => a)
    def flatMap[A, B](fa: TestIO[A])(f: A => TestIO[B]): TestIO[B] = fa.flatMap(f)
    def raiseError[A](e: Throwable): TestIO[A] = TestIO(() => throw e)
    def handleErrorWith[A](fa: TestIO[A])(f: Throwable => TestIO[A]): TestIO[A] =
      TestIO { () =>
        try fa.run()
        catch { case e: Throwable => f(e).run() }
      }
    def delay[A](thunk: => A): TestIO[A] = TestIO(() => thunk)
    def blocking[A](thunk: => A): TestIO[A] = TestIO(() => thunk)
  }

  def createTracer(): Tracer = openTelemetry.getTracer("test")

  def getSpans(): List[SpanData] = spanExporter.getFinishedSpanItems.asScala.toList

  // ===========================================================================
  // Basic Functionality Tests
  // ===========================================================================

  test("creates span for allowed decision") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse()

    interceptor.onResponse(response).run()

    val spans = getSpans()
    assertEquals(spans.size, 1, "Should create exactly one span")

    val span = spans.head
    assertEquals(span.getName, "cedar.authorization", "Span name should be default")
    assertEquals(span.getKind, SpanKind.INTERNAL, "Span kind should be INTERNAL")
    assertEquals(span.getStatus.getStatusCode, StatusCode.OK, "Status should be OK for allowed")
  }

  test("creates span for denied decision") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createDeniedResponse()

    interceptor.onResponse(response).run()

    val spans = getSpans()
    assertEquals(spans.size, 1)

    val span = spans.head
    assertEquals(span.getStatus.getStatusCode, StatusCode.OK, "Status should be OK (denial is valid outcome)")
  }

  // ===========================================================================
  // Attribute Tests
  // ===========================================================================

  test("adds principal attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse(principalId = "bob")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_PRINCIPAL_TYPE)),
      "User"
    )
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_PRINCIPAL_ID)),
      "bob"
    )
  }

  test("adds action attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse(actionName = "Edit")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_ACTION)),
      "Document::Action::\"Edit\""
    )
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_ACTION_NAME)),
      "Edit"
    )
  }

  test("adds resource attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse(resourceType = "Folder", resourceId = "folder-1")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_RESOURCE_TYPE)),
      "Folder"
    )
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_RESOURCE_ID)),
      "folder-1"
    )
  }

  test("adds decision attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse()

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_DECISION)),
      "allow"
    )
  }

  test("adds deny reason for denied decisions") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createDeniedResponse(Some("Insufficient permissions"))

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_DECISION)),
      "deny"
    )
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_DENY_REASON)),
      "Insufficient permissions"
    )
  }

  test("adds performance attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse()

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    val durationMs = attrs.get(io.opentelemetry.api.common.AttributeKey.longKey(SemanticConventions.CEDAR_DURATION_MS))
    assert(durationMs != null && durationMs == 5L, s"Expected 5ms duration, got $durationMs")
  }

  // ===========================================================================
  // Span Naming Tests
  // ===========================================================================

  test("uses custom span naming strategy") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      spanNamingStrategy = SpanNamingStrategy.byAction
    )
    val response = createAllowedResponse(actionName = "Edit")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    assertEquals(span.getName, "cedar.authorization.Edit")
  }

  test("byResourceType naming strategy") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      spanNamingStrategy = SpanNamingStrategy.byResourceType
    )
    val response = createAllowedResponse(resourceType = "Folder")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    assertEquals(span.getName, "cedar.authorization.Folder")
  }

  test("byActionAndResourceType naming strategy") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      spanNamingStrategy = SpanNamingStrategy.byActionAndResourceType
    )
    val response = createAllowedResponse(actionName = "Edit", resourceType = "Document")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    assertEquals(span.getName, "cedar.authorization.Document.Edit")
  }

  // ===========================================================================
  // Attribute Filter Tests
  // ===========================================================================

  test("excludes principal IDs when filtered") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      attributeFilter = AttributeFilter.excludePrincipalIds
    )
    val response = createAllowedResponse(principalId = "alice")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    // Principal type should be present
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_PRINCIPAL_TYPE)),
      "User"
    )

    // Principal ID should be absent
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_PRINCIPAL_ID)),
      null
    )
  }

  test("excludes resource IDs when filtered") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      attributeFilter = AttributeFilter.excludeResourceIds
    )
    val response = createAllowedResponse(resourceId = "doc-123")

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    // Resource type should be present
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_RESOURCE_TYPE)),
      "Document"
    )

    // Resource ID should be absent
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_RESOURCE_ID)),
      null
    )
  }

  test("minimal filter only includes essential attributes") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      attributeFilter = AttributeFilter.minimal
    )
    val response = createAllowedResponse()

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val attrs = span.getAttributes

    // Should include decision
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_DECISION)),
      "allow"
    )

    // Should NOT include principal ID
    assertEquals(
      attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(SemanticConventions.CEDAR_PRINCIPAL_ID)),
      null
    )
  }

  // ===========================================================================
  // Event Tests
  // ===========================================================================

  test("records denial event when recordDenials is true") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      recordDenials = true
    )
    val response = createDeniedResponse(Some("Policy forbids"))

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val events = span.getEvents.asScala

    assert(events.exists(_.getName == SemanticConventions.EVENT_AUTHORIZATION_DENIED))
  }

  test("does not record denial event when recordDenials is false") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](
      tracer,
      recordDenials = false
    )
    val response = createDeniedResponse()

    interceptor.onResponse(response).run()

    val span = getSpans().head
    val events = span.getEvents.asScala

    assert(!events.exists(_.getName == SemanticConventions.EVENT_AUTHORIZATION_DENIED))
  }

  // ===========================================================================
  // Error Handling Tests
  // ===========================================================================

  test("does not throw exceptions on error") {
    val tracer = createTracer()
    val interceptor = new OpenTelemetryInterceptor[TestIO](tracer)
    val response = createAllowedResponse()

    // Should not throw even if something goes wrong internally
    interceptor.onResponse(response).run()
  }
}
