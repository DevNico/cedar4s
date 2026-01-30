package cedar4s.observability.audit

import cedar4s.capability.Sync
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

/** Industry-standard SLF4J audit logger with structured logging support.
  *
  * This logger integrates with the SLF4J ecosystem, enabling:
  *   - Flexible backend configuration (Logback, Log4j2, etc.)
  *   - Structured logging with MDC/markers
  *   - JSON encoding via logstash-logback-encoder
  *   - Integration with enterprise logging infrastructure
  *
  * ==Thread Safety==
  *
  * This implementation is thread-safe via SLF4J's guarantees.
  *
  * ==Usage==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * // Create logger with default settings
  * val logger = Slf4jAuditLogger[Future]()
  *
  * // Customize logger name and level
  * val customLogger = Slf4jAuditLogger[Future](
  *   loggerName = "com.mycompany.audit",
  *   level = LogLevel.Info
  * )
  *
  * // Use with structured logging markers (requires logstash-logback-encoder)
  * val structuredLogger = Slf4jAuditLogger[Future](
  *   loggerName = "cedar4s.audit",
  *   useStructuredLogging = true
  * )
  * }}}
  *
  * ==Logback Configuration==
  *
  * Configure JSON output in logback.xml:
  *
  * {{{
  * <configuration>
  *   <appender name="AUDIT_JSON" class="ch.qos.logback.core.FileAppender">
  *     <file>audit.json</file>
  *     <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  *   </appender>
  *
  *   <logger name="cedar4s.audit" level="INFO" additivity="false">
  *     <appender-ref ref="AUDIT_JSON"/>
  *   </logger>
  * </configuration>
  * }}}
  *
  * @param loggerName
  *   SLF4J logger name (default: "cedar4s.audit")
  * @param level
  *   Log level for audit events (default: Info)
  * @param useStructuredLogging
  *   Enable structured logging with markers (requires logstash-logback-encoder)
  * @param F
  *   Effect type capability
  * @tparam F
  *   The effect type
  */
final class Slf4jAuditLogger[F[_]](
    loggerName: String = "cedar4s.audit",
    level: LogLevel = LogLevel.Info,
    useStructuredLogging: Boolean = true
)(implicit F: Sync[F])
    extends AuditLogger[F] {

  private val logger = LoggerFactory.getLogger(loggerName)

  def logDecision(event: AuthorizationEvent): F[Unit] = F.delay {
    if (useStructuredLogging && Slf4jAuditLogger.isLogstashAvailable) {
      logWithStructuredLogging(event)
    } else {
      logPlainText(event)
    }
  }

  private def logWithStructuredLogging(event: AuthorizationEvent): Unit = {
    try {
      // Use reflection to access Markers.appendEntries dynamically
      // This allows the code to compile without logstash-logback-encoder on the classpath
      val markersClass = Class.forName("net.logstash.logback.marker.Markers")
      val appendEntriesMethod = markersClass.getMethod("appendEntries", classOf[java.util.Map[?, ?]])
      val marker = appendEntriesMethod.invoke(null, eventAsJavaMap(event)).asInstanceOf[org.slf4j.Marker]

      level match {
        case LogLevel.Debug => logger.debug(marker, "Authorization decision")
        case LogLevel.Info  => logger.info(marker, "Authorization decision")
        case LogLevel.Warn  => logger.warn(marker, "Authorization decision")
      }
    } catch {
      case _: ClassNotFoundException | _: NoSuchMethodException =>
        // Fallback to plain text if logstash-logback-encoder is not available
        logPlainText(event)
    }
  }

  private def logPlainText(event: AuthorizationEvent): Unit = {
    val message = formatPlainText(event)
    level match {
      case LogLevel.Debug => logger.debug(message)
      case LogLevel.Info  => logger.info(message)
      case LogLevel.Warn  => logger.warn(message)
    }
  }

  private def formatPlainText(event: AuthorizationEvent): String = {
    val action = if (event.allowed) "ALLOWED" else "DENIED"
    val principal = event.principal.toCedarUid
    val resource = event.resource.toCedarUid
    val actionName = event.action
    val durationMs = "%.2f".format(event.durationMs)

    val baseMessage = s"$action: $principal -> $actionName on $resource (${durationMs}ms)"

    val enrichments = List(
      event.reason.map(r => s"reason=$r"),
      if (event.decision.policies.nonEmpty) Some(s"policies=${event.decision.policies.mkString(",")}") else None,
      event.sessionId.map(s => s"sessionId=$s"),
      event.requestId.map(r => s"requestId=$r")
    ).flatten

    if (enrichments.isEmpty) baseMessage
    else s"$baseMessage [${enrichments.mkString(", ")}]"
  }

  private def eventAsJavaMap(event: AuthorizationEvent): java.util.Map[String, Any] = {
    val map = new java.util.HashMap[String, Any]()

    map.put("timestamp", event.timestamp.toString)
    map.put("action", event.action)
    map.put("allowed", java.lang.Boolean.valueOf(event.allowed))
    map.put("denied", java.lang.Boolean.valueOf(event.denied))
    map.put("durationMs", java.lang.Double.valueOf(event.durationMs))
    map.put("durationNanos", java.lang.Long.valueOf(event.durationNanos))

    // Principal
    val principalMap = new java.util.HashMap[String, String]()
    principalMap.put("entityType", event.principal.entityType)
    principalMap.put("entityId", event.principal.entityId)
    principalMap.put("cedarUid", event.principal.toCedarUid)
    map.put("principal", principalMap)

    // Resource
    val resourceMap = new java.util.HashMap[String, Any]()
    resourceMap.put("entityType", event.resource.entityType)
    event.resource.entityId.foreach(id => resourceMap.put("entityId", id))
    resourceMap.put("cedarUid", event.resource.toCedarUid)
    map.put("resource", resourceMap)

    // Decision
    val decisionMap = new java.util.HashMap[String, Any]()
    decisionMap.put("allow", java.lang.Boolean.valueOf(event.decision.allow))
    decisionMap.put("policies", event.decision.policies.asJava)
    decisionMap.put("policiesSatisfied", event.decision.policiesSatisfied.asJava)
    decisionMap.put("policiesDenied", event.decision.policiesDenied.asJava)
    map.put("decision", decisionMap)

    // Optional fields
    event.reason.foreach(r => map.put("reason", r))
    event.sessionId.foreach(s => map.put("sessionId", s))
    event.requestId.foreach(r => map.put("requestId", r))

    // Context
    if (event.context.nonEmpty) {
      map.put("context", event.context.asJava)
    }

    map
  }
}

object Slf4jAuditLogger {

  /** Create an SLF4J audit logger with default settings.
    *
    * @param loggerName
    *   SLF4J logger name (default: "cedar4s.audit")
    * @param level
    *   Log level for audit events (default: Info)
    * @param useStructuredLogging
    *   Enable structured logging with markers (default: true)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def apply[F[_]](
      loggerName: String = "cedar4s.audit",
      level: LogLevel = LogLevel.Info,
      useStructuredLogging: Boolean = true
  )(implicit F: Sync[F]): Slf4jAuditLogger[F] =
    new Slf4jAuditLogger[F](loggerName, level, useStructuredLogging)

  /** Check if logstash-logback-encoder is available on the classpath.
    *
    * This enables graceful degradation to plain text logging when the structured logging library is not available.
    */
  private[audit] lazy val isLogstashAvailable: Boolean = {
    try {
      Class.forName("net.logstash.logback.marker.Markers")
      true
    } catch {
      case _: ClassNotFoundException => false
    }
  }
}
