package cedar4s.observability.otel

import cedar4s.client.{AuthInterceptor, AuthorizationResponse}
import cedar4s.capability.Sync
import io.opentelemetry.api.trace.{Span, SpanKind, StatusCode, Tracer}
import io.opentelemetry.api.common.{Attributes, AttributesBuilder}
import io.opentelemetry.context.Context

import scala.util.control.NonFatal

/** OpenTelemetry interceptor for Cedar authorization checks.
  *
  * Creates a span for each authorization decision with:
  *   - **Span name**: Determined by [[SpanNamingStrategy]]
  *   - **Attributes**: Cedar-specific semantic conventions
  *   - **Status**: OK if allowed, ERROR if denied or failed
  *   - **Events**: Logged for denials and errors
  *
  * ==Basic Usage==
  *
  * {{{
  * import io.opentelemetry.api.GlobalOpenTelemetry
  * import cedar4s.observability.otel.OpenTelemetryInterceptor
  *
  * val tracer = GlobalOpenTelemetry.getTracer("my-app")
  * val otelInterceptor = OpenTelemetryInterceptor[IO](tracer)
  *
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
  *   attributeFilter = AttributeFilter.excludePrincipalIds,
  *   recordDenials = true,
  *   recordErrors = true
  * )
  * }}}
  *
  * ==Distributed Tracing==
  *
  * The interceptor creates spans as children of the current active span. If you're using OpenTelemetry's context
  * propagation, authorization spans will automatically be linked to the parent request trace.
  *
  * {{{
  * // In an HTTP handler with OpenTelemetry auto-instrumentation
  * def handleRequest(request: Request): F[Response] = {
  *   // Current span from HTTP instrumentation is active
  *   session.require(Document.View(documentId))
  *   // ^ Creates nested "cedar.authorization" span
  * }
  * }}}
  *
  * @param tracer
  *   OpenTelemetry tracer for creating spans
  * @param spanNamingStrategy
  *   Strategy for naming spans (default: "cedar.authorization")
  * @param attributeFilter
  *   Filter for controlling which attributes are included
  * @param recordDenials
  *   Whether to log an event when authorization is denied
  * @param recordErrors
  *   Whether to log an event when authorization fails with error
  * @tparam F
  *   Effect type
  */
final class OpenTelemetryInterceptor[F[_]](
    tracer: Tracer,
    spanNamingStrategy: SpanNamingStrategy = SpanNamingStrategy.default,
    attributeFilter: AttributeFilter = AttributeFilter.includeAll,
    recordDenials: Boolean = true,
    recordErrors: Boolean = true
)(implicit F: Sync[F])
    extends AuthInterceptor[F] {

  import SemanticConventions._

  override def onResponse(response: AuthorizationResponse): F[Unit] =
    F.delay {
      try {
        val spanName = spanNamingStrategy.spanName(response)
        val parentContext = Context.current()

        val span = tracer
          .spanBuilder(spanName)
          .setParent(parentContext)
          .setSpanKind(SpanKind.INTERNAL)
          .setStartTimestamp(response.timestamp)
          .startSpan()

        try {
          // Add attributes
          addAttributes(span, response)

          // Set span status
          setSpanStatus(span, response)

          // Record events
          if (recordDenials && response.denied) {
            recordDenialEvent(span, response)
          }

          if (recordErrors && response.errors.nonEmpty) {
            recordErrorEvent(span, response)
          }
        } finally {
          // End span with actual end timestamp
          val endTimestamp = response.timestamp.plusNanos(response.durationNanos)
          span.end(endTimestamp)
        }
      } catch {
        case NonFatal(e) =>
          // Never let interceptor errors break the authorization flow
          System.err.println(s"OpenTelemetryInterceptor error: ${e.getMessage}")
          e.printStackTrace()
      }
    }

  /** Add Cedar attributes to the span based on the response and filter.
    */
  private def addAttributes(span: Span, response: AuthorizationResponse): Unit = {
    val builder = Attributes.builder()

    // Principal attributes
    addIfAllowed(builder, CEDAR_PRINCIPAL_TYPE, response.principal.entityType, response)
    addIfAllowed(builder, CEDAR_PRINCIPAL_ID, response.principal.entityId, response)
    addIfAllowed(builder, CEDAR_PRINCIPAL_UID, response.cedarPrincipal.uid.toString, response)

    // Action attributes
    addIfAllowed(builder, CEDAR_ACTION, response.action.cedarAction, response)
    addIfAllowed(builder, CEDAR_ACTION_NAME, response.action.name, response)

    // Resource attributes
    addIfAllowed(builder, CEDAR_RESOURCE_TYPE, response.resource.entityType, response)
    response.resource.entityId.foreach { id =>
      addIfAllowed(builder, CEDAR_RESOURCE_ID, id, response)
    }
    val resourceUid = response.resource.entityId match {
      case Some(id) => s"${response.resource.entityType}::\"$id\""
      case None     => s"${response.resource.entityType}::\"__collection__\""
    }
    addIfAllowed(builder, CEDAR_RESOURCE_UID, resourceUid, response)

    // Decision attributes
    val decisionValue = if (response.decision.allow) "allow" else "deny"
    addIfAllowed(builder, CEDAR_DECISION, decisionValue, response)

    if (response.denied) {
      response.decision.denyReason.foreach { reason =>
        addIfAllowed(builder, CEDAR_DENY_REASON, reason, response)
      }
      response.decision.diagnostics.foreach { diag =>
        val deniedCount = diag.policiesDenied.size.toLong
        if (deniedCount > 0) {
          addIfAllowed(builder, CEDAR_DENY_REASON_COUNT, deniedCount, response)
        }
      }
    }

    // Performance attributes
    addIfAllowed(builder, CEDAR_DURATION_MS, response.durationMs, response)
    addIfAllowed(builder, CEDAR_ENTITIES_COUNT, response.entities.entities.size.toLong, response)

    // Context attributes
    val hasContext = response.context.attributes.nonEmpty
    addIfAllowed(builder, CEDAR_HAS_CONTEXT, hasContext, response)
    addIfAllowed(builder, CEDAR_CONTEXT_SIZE, response.context.attributes.size.toLong, response)

    // Error attributes
    if (response.errors.nonEmpty) {
      val error = response.errors.head
      addIfAllowed(builder, CEDAR_ERROR, error.message, response)
      addIfAllowed(builder, CEDAR_ERROR_TYPE, error.getClass.getSimpleName, response)
    }

    span.setAllAttributes(builder.build())
  }

  /** Set the span status based on the authorization decision.
    */
  private def setSpanStatus(span: Span, response: AuthorizationResponse): Unit = {
    if (response.errors.nonEmpty) {
      // Authorization failed with error
      span.setStatus(StatusCode.ERROR, response.errors.head.message)
    } else if (response.denied) {
      // Authorization was denied
      // Note: Some organizations prefer ERROR for denials, others prefer OK
      // Using OK here since denial is a valid business outcome, not a technical error
      span.setStatus(StatusCode.OK)
    } else {
      // Authorization was allowed
      span.setStatus(StatusCode.OK)
    }
  }

  /** Record an event when authorization is denied.
    */
  private def recordDenialEvent(span: Span, response: AuthorizationResponse): Unit = {
    val eventAttrs = Attributes.builder()

    response.decision.denyReason.foreach { reason =>
      eventAttrs.put("reason", reason)
    }

    eventAttrs.put("principal", response.cedarPrincipal.uid.toString)
    eventAttrs.put("action", response.action.cedarAction)
    eventAttrs.put("resource", response.resource.entityType)

    span.addEvent(EVENT_AUTHORIZATION_DENIED, eventAttrs.build())
  }

  /** Record an event when authorization fails with an error.
    */
  private def recordErrorEvent(span: Span, response: AuthorizationResponse): Unit = {
    response.errors.foreach { error =>
      val eventAttrs = Attributes
        .builder()
        .put("error.type", error.getClass.getSimpleName)
        .put("error.message", error.message)
        .build()

      span.addEvent("cedar.authorization.error", eventAttrs)
    }
  }

  // ===========================================================================
  // Helper Methods
  // ===========================================================================

  private def addIfAllowed(
      builder: AttributesBuilder,
      key: String,
      value: String,
      response: AuthorizationResponse
  ): Unit = {
    if (attributeFilter.shouldInclude(key, response)) {
      builder.put(key, value)
    }
  }

  private def addIfAllowed(
      builder: AttributesBuilder,
      key: String,
      value: Long,
      response: AuthorizationResponse
  ): Unit = {
    if (attributeFilter.shouldInclude(key, response)) {
      builder.put(key, value)
    }
  }

  private def addIfAllowed(
      builder: AttributesBuilder,
      key: String,
      value: Boolean,
      response: AuthorizationResponse
  ): Unit = {
    if (attributeFilter.shouldInclude(key, response)) {
      builder.put(key, value)
    }
  }
}

object OpenTelemetryInterceptor {

  /** Create an OpenTelemetryInterceptor with default settings.
    *
    * {{{
    * val tracer = GlobalOpenTelemetry.getTracer("my-app")
    * val interceptor = OpenTelemetryInterceptor[IO](tracer)
    * }}}
    */
  def apply[F[_]](
      tracer: Tracer
  )(implicit F: Sync[F]): OpenTelemetryInterceptor[F] =
    new OpenTelemetryInterceptor[F](tracer)

  /** Create an OpenTelemetryInterceptor with custom naming strategy.
    *
    * {{{
    * val interceptor = OpenTelemetryInterceptor[IO](
    *   tracer,
    *   SpanNamingStrategy.byAction
    * )
    * }}}
    */
  def apply[F[_]](
      tracer: Tracer,
      spanNamingStrategy: SpanNamingStrategy
  )(implicit F: Sync[F]): OpenTelemetryInterceptor[F] =
    new OpenTelemetryInterceptor[F](tracer, spanNamingStrategy)

  /** Create an OpenTelemetryInterceptor with custom naming and filtering.
    *
    * {{{
    * val interceptor = OpenTelemetryInterceptor[IO](
    *   tracer,
    *   SpanNamingStrategy.byAction,
    *   AttributeFilter.excludePrincipalIds
    * )
    * }}}
    */
  def apply[F[_]](
      tracer: Tracer,
      spanNamingStrategy: SpanNamingStrategy,
      attributeFilter: AttributeFilter
  )(implicit F: Sync[F]): OpenTelemetryInterceptor[F] =
    new OpenTelemetryInterceptor[F](tracer, spanNamingStrategy, attributeFilter)
}
