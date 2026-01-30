package cedar4s.bench

import cedar4s.schema.CedarSchema
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

/** JMH benchmarks for Cedar schema parsing performance.
  *
  * These benchmarks measure schema parsing time which affects:
  *   - SBT compile times (codegen runs on each compile)
  *   - Application startup (if schemas are parsed at runtime)
  *
  * Run with: sbt "bench/Jmh/run -i 10 -wi 5 -f 2 -t 1 SchemaParserBench"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class SchemaParserBench {

  var tinySchema: String = uninitialized
  var smallSchema: String = uninitialized
  var mediumSchema: String = uninitialized
  var largeSchema: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    tinySchema = generateSchema(entities = 1, actionsPerEntity = 2, attrsPerEntity = 2)
    smallSchema = generateSchema(entities = 5, actionsPerEntity = 4, attrsPerEntity = 5)
    mediumSchema = generateSchema(entities = 20, actionsPerEntity = 6, attrsPerEntity = 8)
    largeSchema = generateSchema(entities = 50, actionsPerEntity = 8, attrsPerEntity = 10)
  }

  // ===========================================================================
  // Parse Benchmarks
  // ===========================================================================

  @Benchmark
  def parseTinySchema(): CedarSchema = {
    CedarSchema.parseUnsafe(tinySchema)
  }

  @Benchmark
  def parseSmallSchema(): CedarSchema = {
    CedarSchema.parseUnsafe(smallSchema)
  }

  @Benchmark
  def parseMediumSchema(): CedarSchema = {
    CedarSchema.parseUnsafe(mediumSchema)
  }

  @Benchmark
  def parseLargeSchema(): CedarSchema = {
    CedarSchema.parseUnsafe(largeSchema)
  }

  // ===========================================================================
  // Schema Generation Helpers
  // ===========================================================================

  private def generateSchema(entities: Int, actionsPerEntity: Int, attrsPerEntity: Int): String = {
    val entityDefs = (1 to entities)
      .map { i =>
        val attrs = (1 to attrsPerEntity)
          .map { j =>
            val attrType = j % 4 match {
              case 0 => "String"
              case 1 => "Long"
              case 2 => "Bool"
              case 3 => s"Set<Entity${(j % entities) + 1}>"
            }
            s"""    "attr$j": $attrType"""
          }
          .mkString(",\n")

        val parent = if (i > 1) s" in [Entity${i - 1}]" else ""

        s"""  entity Entity$i$parent = {
$attrs
  };"""
      }
      .mkString("\n\n")

    val actionDefs = (1 to entities)
      .flatMap { entityIdx =>
        (1 to actionsPerEntity).map { actionIdx =>
          val actionName = actionIdx match {
            case 1 => "create"
            case 2 => "read"
            case 3 => "update"
            case 4 => "delete"
            case 5 => "list"
            case 6 => "share"
            case 7 => "admin"
            case _ => s"action$actionIdx"
          }
          s"""  action "Entity$entityIdx::$actionName" appliesTo {
    principal: [User],
    resource: [Entity$entityIdx]
  };"""
        }
      }
      .mkString("\n\n")

    s"""namespace Bench {
  // Principal type
  entity User = {
    "name": String,
    "email": String
  };

$entityDefs

$actionDefs
}
"""
  }
}
