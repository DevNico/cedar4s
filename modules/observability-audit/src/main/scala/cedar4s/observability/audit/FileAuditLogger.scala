package cedar4s.observability.audit

import cedar4s.capability.Sync
import io.circe.syntax._

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/** Audit logger that writes events to rotating log files.
  *
  * Files are rotated based on size and/or time. Old files are renamed with timestamps to preserve audit history.
  *
  * ==Rotation Strategy==
  *
  * Files rotate when:
  *   - Size exceeds maxSizeBytes (if specified)
  *   - Current log file is from a different day (daily rotation)
  *
  * ==File Naming==
  *
  *   - Active log: `<baseFileName>.jsonl`
  *   - Rotated logs: `<baseFileName>-<timestamp>.jsonl`
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
  * import java.nio.file.Paths
  *
  * // Simple file logger
  * val logger = FileAuditLogger[Future](
  *   logDir = Paths.get("/var/log/cedar"),
  *   baseFileName = "audit"
  * )
  *
  * // With size-based rotation (10 MB)
  * val rotatingLogger = FileAuditLogger[Future](
  *   logDir = Paths.get("/var/log/cedar"),
  *   baseFileName = "audit",
  *   maxSizeBytes = Some(10 * 1024 * 1024)
  * )
  * }}}
  *
  * @param logDir
  *   Directory to write log files to
  * @param baseFileName
  *   Base name for log files (without extension)
  * @param maxSizeBytes
  *   Maximum size before rotation (None for no size limit)
  * @param F
  *   Effect type capability
  * @tparam F
  *   The effect type
  */
final class FileAuditLogger[F[_]](
    logDir: Path,
    baseFileName: String,
    maxSizeBytes: Option[Long] = None
)(implicit F: Sync[F])
    extends AuditLogger[F] {

  import JsonAuditLogger._

  private val dateFormatter = DateTimeFormatter
    .ofPattern("yyyyMMdd-HHmmss")
    .withZone(ZoneId.systemDefault())

  private var writer: PrintWriter = null
  private var currentDate: String = null
  private var bytesWritten: Long = 0L

  // Initialize log directory and writer
  private def initialize(): Unit = {
    if (!Files.exists(logDir)) {
      Files.createDirectories(logDir)
    }
    currentDate = getCurrentDate
    openWriter()
  }

  initialize()

  private def getCurrentDate: String = {
    DateTimeFormatter
      .ofPattern("yyyy-MM-dd")
      .withZone(ZoneId.systemDefault())
      .format(Instant.now())
  }

  private def currentLogPath: Path = logDir.resolve(s"$baseFileName.jsonl")

  private def openWriter(): Unit = {
    val file = currentLogPath.toFile
    bytesWritten = if (file.exists()) file.length() else 0L
    writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true)), true)
  }

  private def rotateFile(): Unit = {
    if (writer != null) {
      writer.close()
    }

    val timestamp = dateFormatter.format(Instant.now())
    val rotatedPath = logDir.resolve(s"$baseFileName-$timestamp.jsonl")

    val currentFile = currentLogPath.toFile
    if (currentFile.exists()) {
      Files.move(currentLogPath, rotatedPath, StandardCopyOption.ATOMIC_MOVE)
    }

    currentDate = getCurrentDate
    openWriter()
  }

  private def shouldRotate: Boolean = {
    val dateMismatch = getCurrentDate != currentDate
    val sizeLimitExceeded = maxSizeBytes.exists(bytesWritten >= _)
    dateMismatch || sizeLimitExceeded
  }

  def logDecision(event: AuthorizationEvent): F[Unit] = F.delay {
    synchronized {
      if (shouldRotate) {
        rotateFile()
      }

      val json = event.asJson.noSpaces
      writer.println(json)
      bytesWritten += json.length + 1 // +1 for newline
    }
  }

  /** Close the logger and release file handles.
    *
    * Call this when shutting down the application to ensure all events are flushed.
    */
  def close(): Unit = synchronized {
    if (writer != null) {
      writer.close()
      writer = null
    }
  }
}

object FileAuditLogger {

  /** Create a file audit logger with default settings.
    *
    * @param logDir
    *   Directory to write log files to (will be created if it doesn't exist)
    * @param baseFileName
    *   Base name for log files (default: "cedar-audit")
    * @param maxSizeBytes
    *   Maximum size before rotation (default: 100 MB)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def apply[F[_]: Sync](
      logDir: Path,
      baseFileName: String = "cedar-audit",
      maxSizeBytes: Option[Long] = Some(100L * 1024 * 1024)
  ): FileAuditLogger[F] =
    new FileAuditLogger[F](logDir, baseFileName, maxSizeBytes)

  /** Create a file audit logger from a string path.
    *
    * @param logDirPath
    *   Path to log directory
    * @param baseFileName
    *   Base name for log files (default: "cedar-audit")
    * @param maxSizeBytes
    *   Maximum size before rotation (default: 100 MB)
    * @param F
    *   Effect type capability
    * @tparam F
    *   The effect type
    */
  def fromPath[F[_]: Sync](
      logDirPath: String,
      baseFileName: String = "cedar-audit",
      maxSizeBytes: Option[Long] = Some(100L * 1024 * 1024)
  ): FileAuditLogger[F] =
    new FileAuditLogger[F](Paths.get(logDirPath), baseFileName, maxSizeBytes)
}
