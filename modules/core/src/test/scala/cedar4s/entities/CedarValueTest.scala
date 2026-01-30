package cedar4s.entities

import munit.FunSuite
import java.time.{Duration, Instant}

/** Tests for CedarValue types including extension types.
  */
class CedarValueTest extends FunSuite {

  // ===========================================================================
  // Primitive Types
  // ===========================================================================

  test("CedarValue.string creates StringValue") {
    val value = CedarValue.string("hello")
    assertEquals(value, CedarValue.StringValue("hello"))
  }

  test("CedarValue.long creates LongValue") {
    val value = CedarValue.long(42L)
    assertEquals(value, CedarValue.LongValue(42L))
  }

  test("CedarValue.bool creates BoolValue") {
    val value = CedarValue.bool(true)
    assertEquals(value, CedarValue.BoolValue(true))
  }

  // ===========================================================================
  // Composite Types
  // ===========================================================================

  test("CedarValue.set creates SetValue") {
    val value = CedarValue.set(
      CedarValue.string("a"),
      CedarValue.string("b")
    )
    assert(value.isInstanceOf[CedarValue.SetValue])
    val setVal = value.asInstanceOf[CedarValue.SetValue]
    assertEquals(setVal.values.size, 2)
  }

  test("CedarValue.record creates RecordValue") {
    val value = CedarValue.record(
      "name" -> CedarValue.string("test"),
      "count" -> CedarValue.long(10)
    )
    assert(value.isInstanceOf[CedarValue.RecordValue])
    val recordVal = value.asInstanceOf[CedarValue.RecordValue]
    assertEquals(recordVal.fields.size, 2)
  }

  test("CedarValue.stringSet creates SetValue of strings") {
    val value = CedarValue.stringSet(Set("a", "b", "c"))
    assert(value.isInstanceOf[CedarValue.SetValue])
    val setVal = value.asInstanceOf[CedarValue.SetValue]
    assertEquals(setVal.values.size, 3)
    assert(setVal.values.contains(CedarValue.StringValue("a")))
  }

  // ===========================================================================
  // Extension Types: ipaddr
  // ===========================================================================

  test("CedarValue.ipaddr creates IpAddrValue with IPv4") {
    val value = CedarValue.ipaddr("192.168.1.1")
    assertEquals(value, CedarValue.IpAddrValue("192.168.1.1"))
  }

  test("CedarValue.ipaddr creates IpAddrValue with IPv6") {
    val value = CedarValue.ipaddr("::1")
    assertEquals(value, CedarValue.IpAddrValue("::1"))
  }

  test("CedarValue.ipaddr creates IpAddrValue with CIDR notation") {
    val value = CedarValue.ipaddr("10.0.0.0/8")
    assertEquals(value, CedarValue.IpAddrValue("10.0.0.0/8"))
  }

  // ===========================================================================
  // Extension Types: decimal
  // ===========================================================================

  test("CedarValue.decimal creates DecimalValue from BigDecimal") {
    val value = CedarValue.decimal(BigDecimal("12.34"))
    assertEquals(value, CedarValue.DecimalValue(BigDecimal("12.34")))
  }

  test("CedarValue.decimal creates DecimalValue from String") {
    val value = CedarValue.decimal("99.99")
    assertEquals(value, CedarValue.DecimalValue(BigDecimal("99.99")))
  }

  test("CedarValue.decimal handles negative decimals") {
    val value = CedarValue.decimal(BigDecimal("-123.456"))
    assertEquals(value, CedarValue.DecimalValue(BigDecimal("-123.456")))
  }

  // ===========================================================================
  // Extension Types: datetime
  // ===========================================================================

  test("CedarValue.datetime creates DatetimeValue from Instant") {
    val instant = Instant.parse("2024-01-15T10:30:00Z")
    val value = CedarValue.datetime(instant)
    assertEquals(value, CedarValue.DatetimeValue(instant))
  }

  test("CedarValue.datetime creates DatetimeValue from epoch millis") {
    val epochMillis = 1705315800000L // 2024-01-15T10:30:00Z
    val value = CedarValue.datetime(epochMillis)
    val expected = CedarValue.DatetimeValue(Instant.ofEpochMilli(epochMillis))
    assertEquals(value, expected)
  }

  test("CedarValue.datetime handles epoch") {
    val value = CedarValue.datetime(Instant.EPOCH)
    assertEquals(value, CedarValue.DatetimeValue(Instant.EPOCH))
  }

  // ===========================================================================
  // Extension Types: duration
  // ===========================================================================

  test("CedarValue.duration creates DurationValue") {
    val duration = Duration.ofHours(2)
    val value = CedarValue.duration(duration)
    assertEquals(value, CedarValue.DurationValue(duration))
  }

  test("CedarValue.durationMillis creates DurationValue from millis") {
    val value = CedarValue.durationMillis(5000L)
    assertEquals(value, CedarValue.DurationValue(Duration.ofMillis(5000L)))
  }

  test("CedarValue.duration handles zero duration") {
    val value = CedarValue.duration(Duration.ZERO)
    assertEquals(value, CedarValue.DurationValue(Duration.ZERO))
  }

  test("CedarValue.duration handles negative duration") {
    val duration = Duration.ofMinutes(-30)
    val value = CedarValue.duration(duration)
    assertEquals(value, CedarValue.DurationValue(duration))
  }

  // ===========================================================================
  // Entity References
  // ===========================================================================

  test("CedarValue.entity creates EntityValue from type and id") {
    import cedar4s.schema.CedarEntityUid
    val value = CedarValue.entity("Test::User", "user-123")
    assert(value.isInstanceOf[CedarValue.EntityValue])
    val entityVal = value.asInstanceOf[CedarValue.EntityValue]
    assertEquals(entityVal.uid.entityType, "Test::User")
    assertEquals(entityVal.uid.entityId, "user-123")
  }

  test("CedarValue.entitySet creates SetValue of entity refs") {
    val value = CedarValue.entitySet(Set("u1", "u2", "u3"), "Test::User")
    assert(value.isInstanceOf[CedarValue.SetValue])
    val setVal = value.asInstanceOf[CedarValue.SetValue]
    assertEquals(setVal.values.size, 3)

    // Check that all values are EntityValue with correct type
    setVal.values.foreach { v =>
      assert(v.isInstanceOf[CedarValue.EntityValue])
      assertEquals(v.asInstanceOf[CedarValue.EntityValue].uid.entityType, "Test::User")
    }
  }

  // ===========================================================================
  // Pattern Matching
  // ===========================================================================

  test("extension type values can be pattern matched") {
    val values: List[CedarValue] = List(
      CedarValue.ipaddr("10.0.0.1"),
      CedarValue.decimal(BigDecimal("42.0")),
      CedarValue.datetime(Instant.EPOCH),
      CedarValue.duration(Duration.ofSeconds(60))
    )

    val types = values.map {
      case CedarValue.IpAddrValue(_)   => "ipaddr"
      case CedarValue.DecimalValue(_)  => "decimal"
      case CedarValue.DatetimeValue(_) => "datetime"
      case CedarValue.DurationValue(_) => "duration"
      case _                           => "other"
    }

    assertEquals(types, List("ipaddr", "decimal", "datetime", "duration"))
  }
}
