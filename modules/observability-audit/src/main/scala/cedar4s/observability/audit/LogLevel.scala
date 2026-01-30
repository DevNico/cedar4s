package cedar4s.observability.audit

/** Log level for audit events.
  *
  * Controls the severity level used when logging audit events to SLF4J. Choose based on your organization's logging
  * practices:
  *
  *   - **Debug**: Verbose logging for development/troubleshooting
  *   - **Info**: Standard audit trail (recommended for production)
  *   - **Warn**: High-visibility audit events (e.g., failed authorization)
  *
  * ==Example==
  *
  * {{{
  * // Use Info level for allowed requests, Warn for denied
  * val logger = Slf4jAuditLogger[Future](
  *   loggerName = "cedar4s.audit",
  *   level = LogLevel.Info
  * )
  * }}}
  */
sealed trait LogLevel

object LogLevel {

  /** Debug level - verbose logging for development */
  case object Debug extends LogLevel

  /** Info level - standard audit trail (recommended) */
  case object Info extends LogLevel

  /** Warn level - high-visibility audit events */
  case object Warn extends LogLevel
}
