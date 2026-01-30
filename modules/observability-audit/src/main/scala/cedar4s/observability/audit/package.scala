package cedar4s.observability

/** Audit logging for Cedar authorization decisions.
  *
  * This package provides comprehensive audit logging capabilities for tracking authorization decisions in production
  * systems. It includes:
  *
  *   - [[audit.AuditLogger]] - Core trait for audit loggers
  *   - [[audit.AuthorizationEvent]] - Event model capturing authorization decisions
  *   - [[audit.AuditInterceptor]] - Integration with CedarRuntime
  *   - [[audit.Slf4jAuditLogger]] - Industry-standard SLF4J logging (recommended)
  *   - [[audit.JsonAuditLogger]] - Simple JSON output to streams
  *   - [[audit.FileAuditLogger]] - Rotating file-based logging
  *   - [[audit.NoOpAuditLogger]] - No-op implementation for testing
  *
  * ==Quick Start (SLF4J - Recommended)==
  *
  * {{{
  * import cedar4s.observability.audit._
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * // Create an SLF4J audit logger
  * val logger = Slf4jAuditLogger[Future]()
  *
  * // Integrate with Cedar runtime
  * val interceptor = AuditInterceptor(logger)
  * val runtime = CedarRuntime(engine, store, resolver)
  *   .withInterceptor(interceptor)
  * }}}
  *
  * ==SLF4J Integration==
  *
  * SLF4J is the recommended approach for production deployments. It provides:
  *
  *   - **Flexible Backends**: Use Logback, Log4j2, or any SLF4J-compatible framework
  *   - **Structured Logging**: JSON output via logstash-logback-encoder
  *   - **Performance**: Async appenders, buffering, and optimized I/O
  *   - **Enterprise Features**: Rotation, compression, retention policies
  *   - **Observability**: Integration with monitoring and SIEM systems
  *
  * ===Logback Configuration===
  *
  * Add to your logback.xml:
  *
  * {{{
  * <configuration>
  *   <appender name="AUDIT_JSON" class="ch.qos.logback.core.FileAppender">
  *     <file>/var/log/cedar/audit.json</file>
  *     <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  *   </appender>
  *
  *   <logger name="cedar4s.audit" level="INFO" additivity="false">
  *     <appender-ref ref="AUDIT_JSON"/>
  *   </logger>
  * </configuration>
  * }}}
  *
  * ===Log4j2 Configuration===
  *
  * Add to your log4j2.xml:
  *
  * {{{
  * <Configuration>
  *   <Appenders>
  *     <RollingFile name="AuditJson" fileName="/var/log/cedar/audit.json">
  *       <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json"/>
  *     </RollingFile>
  *   </Appenders>
  *   <Loggers>
  *     <Logger name="cedar4s.audit" level="info">
  *       <AppenderRef ref="AuditJson"/>
  *     </Logger>
  *   </Loggers>
  * </Configuration>
  * }}}
  *
  * ==Simple JSON Logging==
  *
  * For simpler use cases without SLF4J configuration:
  *
  * {{{
  * // Write JSON to stdout
  * val logger = JsonAuditLogger.stdout[Future]()
  *
  * // Write to custom stream
  * val fileStream = new FileOutputStream("/var/log/cedar-audit.jsonl")
  * val fileLogger = JsonAuditLogger.fromStream[Future](fileStream)
  * }}}
  *
  * ==Event Format==
  *
  * Events are captured with comprehensive context:
  *
  * {{{
  * {
  *   "timestamp": "2026-01-29T12:34:56.789Z",
  *   "principal": {
  *     "entityType": "User",
  *     "entityId": "alice",
  *     "cedarUid": "User::\"alice\""
  *   },
  *   "action": "Document::View",
  *   "resource": {
  *     "entityType": "Document",
  *     "entityId": "doc-123",
  *     "cedarUid": "Document::\"doc-123\""
  *   },
  *   "decision": {
  *     "allow": true,
  *     "policies": ["policy-1"],
  *     "policiesSatisfied": ["policy-1"],
  *     "policiesDenied": []
  *   },
  *   "reason": null,
  *   "durationMs": 1.5,
  *   "durationNanos": 1500000,
  *   "context": {},
  *   "sessionId": null,
  *   "requestId": null,
  *   "allowed": true,
  *   "denied": false
  * }
  * }}}
  *
  * ==Migration from JsonAuditLogger==
  *
  * If you're currently using JsonAuditLogger, migrating to Slf4jAuditLogger is simple:
  *
  * {{{
  * // Before (JsonAuditLogger)
  * val oldLogger = JsonAuditLogger.stdout[Future]()
  *
  * // After (Slf4jAuditLogger)
  * val newLogger = Slf4jAuditLogger[Future]()
  * }}}
  *
  * Then configure your logging backend (Logback/Log4j2) to output JSON. See the example configurations in
  * src/test/resources/ for reference.
  */
package object audit
