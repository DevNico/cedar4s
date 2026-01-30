package cedar4s.observability.audit

import cedar4s.capability.Sync
import io.circe.{Encoder, Json}
import io.circe.syntax._

import java.io.{OutputStream, PrintStream}
import java.time.Instant

/** Simple audit logger that writes events as JSON to an output stream.
  *
  * This logger is designed for simple use cases where you want direct control over the output stream without
  * configuring a full logging framework.
  *
  * For production deployments, consider using [[Slf4jAuditLogger]] instead, which provides:
  *   - Flexible backend configuration (Logback, Log4j2)
  *   - Async appenders for better performance
  *   - Advanced features (rotation, compression, filtering)
  *   - Integration with enterprise logging infrastructure
  *
  * JSON format enables easy integration with log aggregation systems (e.g., ELK Stack, Splunk, Datadog) and structured
  * log analysis.
  *
  * ==Thread Safety==
  *
  * This implementation synchronizes writes to ensure thread-safe operation.
  *
  * ==Example==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * // Write to stdout
  * val logger = JsonAuditLogger.stdout[Future]()
  *
  * // Write to stderr
  * val errorLogger = JsonAuditLogger.stderr[Future]()
  *
  * // Write to custom stream
  * val fileStream = new FileOutputStream("/var/log/cedar-audit.jsonl")
  * val fileLogger = JsonAuditLogger.fromStream[Future](fileStream)
  * }}}
  *
  * ==Migration to SLF4J==
  *
  * To migrate to SLF4J for production use:
  *
  * {{{
  * // Replace this:
  * val logger = JsonAuditLogger.stdout[Future]()
  *
  * // With this:
  * val logger = Slf4jAuditLogger[Future]()
  *
  * // Then configure Logback/Log4j2 for JSON output
  * }}}
  *
  * @param out
  *   The output stream to write JSON to
  * @param pretty
  *   Whether to pretty-print JSON (default: false for compact logs)
  * @param F
  *   Effect type capability
  * @tparam F
  *   The effect type
  */
final class JsonAuditLogger[F[_]](
    out: PrintStream,
    pretty: Boolean = false
)(implicit F: Sync[F])
    extends AuditLogger[F] {

  import JsonAuditLogger._

  def logDecision(event: AuthorizationEvent): F[Unit] = F.delay {
    val json = if (pretty) event.asJson.spaces2 else event.asJson.noSpaces
    // Synchronize to ensure thread-safe writes
    out.synchronized {
      out.println(json)
      out.flush()
    }
  }
}

object JsonAuditLogger {

  /** Create a logger that writes to stdout.
    *
    * @param pretty
    *   Whether to pretty-print JSON (default: false)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def stdout[F[_]](pretty: Boolean = false)(implicit F: Sync[F]): JsonAuditLogger[F] =
    new JsonAuditLogger[F](System.out, pretty)

  /** Create a logger that writes to stderr.
    *
    * @param pretty
    *   Whether to pretty-print JSON (default: false)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def stderr[F[_]](pretty: Boolean = false)(implicit F: Sync[F]): JsonAuditLogger[F] =
    new JsonAuditLogger[F](System.err, pretty)

  /** Create a logger that writes to a custom output stream.
    *
    * @param out
    *   The output stream
    * @param pretty
    *   Whether to pretty-print JSON (default: false)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def fromStream[F[_]](out: OutputStream, pretty: Boolean = false)(implicit F: Sync[F]): JsonAuditLogger[F] =
    new JsonAuditLogger[F](new PrintStream(out, true), pretty)

  // Circe encoders for audit event types

  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)

  implicit val principalInfoEncoder: Encoder[PrincipalInfo] = Encoder.instance { p =>
    Json.obj(
      "entityType" -> p.entityType.asJson,
      "entityId" -> p.entityId.asJson,
      "cedarUid" -> p.toCedarUid.asJson
    )
  }

  implicit val resourceInfoEncoder: Encoder[ResourceInfo] = Encoder.instance { r =>
    Json.obj(
      "entityType" -> r.entityType.asJson,
      "entityId" -> r.entityId.asJson,
      "cedarUid" -> r.toCedarUid.asJson
    )
  }

  implicit val decisionEncoder: Encoder[Decision] = Encoder.instance { d =>
    Json.obj(
      "allow" -> d.allow.asJson,
      "policies" -> d.policies.asJson,
      "policiesSatisfied" -> d.policiesSatisfied.asJson,
      "policiesDenied" -> d.policiesDenied.asJson
    )
  }

  implicit val authorizationEventEncoder: Encoder[AuthorizationEvent] = Encoder.instance { e =>
    Json.obj(
      "timestamp" -> e.timestamp.asJson,
      "principal" -> e.principal.asJson,
      "action" -> e.action.asJson,
      "resource" -> e.resource.asJson,
      "decision" -> e.decision.asJson,
      "reason" -> e.reason.asJson,
      "durationMs" -> e.durationMs.asJson,
      "durationNanos" -> e.durationNanos.asJson,
      "context" -> e.context.asJson,
      "sessionId" -> e.sessionId.asJson,
      "requestId" -> e.requestId.asJson,
      "allowed" -> e.allowed.asJson,
      "denied" -> e.denied.asJson
    )
  }
}
