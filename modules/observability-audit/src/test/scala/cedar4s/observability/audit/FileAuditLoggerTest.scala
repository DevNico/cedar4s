package cedar4s.observability.audit

import munit.FunSuite

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cedar4s.capability.instances._
import scala.jdk.CollectionConverters._

class FileAuditLoggerTest extends FunSuite {

  val tempDir = FunFixture[java.nio.file.Path](
    setup = { _ =>
      Files.createTempDirectory("cedar-audit-test")
    },
    teardown = { dir =>
      // Clean up temp directory
      if (Files.exists(dir)) {
        Files
          .walk(dir)
          .iterator()
          .asScala
          .toSeq
          .reverse
          .foreach(Files.deleteIfExists)
      }
    }
  )

  tempDir.test("FileAuditLogger creates log file") { dir =>
    val logger = FileAuditLogger[Future](dir, "test-audit")

    val event = AuthorizationEvent(
      timestamp = Instant.now(),
      principal = PrincipalInfo("User", "alice"),
      action = "Document::View",
      resource = ResourceInfo("Document", Some("doc-123")),
      decision = Decision(allow = true),
      reason = None,
      durationNanos = 1_000_000
    )

    Await.result(logger.logDecision(event), 5.seconds)
    logger.close()

    val logFile = dir.resolve("test-audit.jsonl")
    assert(Files.exists(logFile))

    val content = Files.readString(logFile)
    assert(content.nonEmpty)
    assert(content.contains("alice"))
    assert(content.contains("Document::View"))
  }

  tempDir.test("FileAuditLogger appends multiple events") { dir =>
    val logger = FileAuditLogger[Future](dir, "test-audit")

    val events = (1 to 5).map { i =>
      AuthorizationEvent(
        timestamp = Instant.now(),
        principal = PrincipalInfo("User", s"user-$i"),
        action = "Document::View",
        resource = ResourceInfo("Document", Some(s"doc-$i")),
        decision = Decision(allow = true),
        reason = None,
        durationNanos = 1_000_000 * i
      )
    }

    events.foreach { event =>
      Await.result(logger.logDecision(event), 5.seconds)
    }
    logger.close()

    val logFile = dir.resolve("test-audit.jsonl")
    val lines = Files.readString(logFile).split("\n").filter(_.nonEmpty)
    assertEquals(lines.length, 5)

    // Check that all events are present
    (1 to 5).foreach { i =>
      assert(lines.exists(_.contains(s"user-$i")))
    }
  }

  tempDir.test("FileAuditLogger rotates on size limit") { dir =>
    // Small size limit to force rotation
    val logger = FileAuditLogger[Future](dir, "test-audit", maxSizeBytes = Some(500))

    // Write enough events to exceed size limit
    val events = (1 to 10).map { i =>
      AuthorizationEvent(
        timestamp = Instant.now(),
        principal = PrincipalInfo("User", s"user-$i"),
        action = "Document::View",
        resource = ResourceInfo("Document", Some(s"doc-$i")),
        decision = Decision(allow = true),
        reason = None,
        durationNanos = 1_000_000 * i
      )
    }

    events.foreach { event =>
      Await.result(logger.logDecision(event), 5.seconds)
    }
    logger.close()

    // Should have rotated file (at least 2 files)
    val files = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.startsWith("test-audit"))
      .toList

    assert(files.length >= 2, s"Expected at least 2 files but got ${files.length}")
  }

  tempDir.test("FileAuditLogger handles empty directory") { dir =>
    assert(Files.exists(dir))
    assert(Files.isDirectory(dir))

    val logger = FileAuditLogger[Future](dir, "test-audit")
    logger.close()

    val logFile = dir.resolve("test-audit.jsonl")
    assert(Files.exists(logFile))
  }

  tempDir.test("FileAuditLogger creates directory if missing") { dir =>
    val subDir = dir.resolve("subdir")
    assert(!Files.exists(subDir))

    val logger = FileAuditLogger[Future](subDir, "test-audit")
    logger.close()

    assert(Files.exists(subDir))
    assert(Files.isDirectory(subDir))

    val logFile = subDir.resolve("test-audit.jsonl")
    assert(Files.exists(logFile))
  }
}
