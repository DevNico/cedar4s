package cedar4s.schema

import cedar4s.{Bijection, Newtype}

/** Tests for Cedar schema runtime types: ShapeId, Hints, ShapeTag, Bijection, Newtype.
  */
class RuntimeTypesSpec extends munit.FunSuite {

  // ===========================================================================
  // ShapeId Tests
  // ===========================================================================

  test("ShapeId: creates from namespace and name") {
    val id = ShapeId("Robotsecurity", "Customer")
    assertEquals(id.namespace, "Robotsecurity")
    assertEquals(id.name, "Customer")
    assertEquals(id.fullName, "Robotsecurity::Customer")
  }

  test("ShapeId: formats as Cedar entity type") {
    val id = ShapeId("Robotsecurity", "Customer")
    assertEquals(id.toCedarType, "Robotsecurity::Customer")
  }

  test("ShapeId: formats as Cedar action reference") {
    val id = ShapeId("Robotsecurity", "Mission::read")
    assertEquals(id.toCedarAction, """Robotsecurity::Action::"Mission::read"""")
  }

  test("ShapeId: parses qualified name") {
    assertEquals(ShapeId.parse("Robotsecurity::Customer"), Some(ShapeId("Robotsecurity", "Customer")))
    assertEquals(ShapeId.parse("Invalid"), None)
  }

  test("ShapeId: creates cedar namespace id") {
    val id = ShapeId.cedar("ownership")
    assertEquals(id.namespace, "cedar")
    assertEquals(id.name, "ownership")
  }

  // ===========================================================================
  // Hints Tests
  // ===========================================================================

  test("Hints: starts empty") {
    assertEquals(Hints.empty.isEmpty, true)
    assertEquals(Hints.empty.size, 0)
  }

  test("Hints: stores and retrieves values by hint key") {
    val hints = Hints.empty + CedarHints.ownership(OwnershipType.Root)

    assertEquals(hints.get(CedarHints.ownership), Some(OwnershipType.Root))
    assertEquals(hints.contains(CedarHints.ownership), true)
  }

  test("Hints: returns None for missing hints") {
    val hints = Hints.empty
    assertEquals(hints.get(CedarHints.ownership), None)
    assertEquals(hints.contains(CedarHints.ownership), false)
  }

  test("Hints: supports getOrElse with default") {
    val hints = Hints.empty
    assertEquals(hints.getOrElse(CedarHints.doc, "default"), "default")

    val hintsWithDoc = hints + CedarHints.doc("documentation")
    assertEquals(hintsWithDoc.getOrElse(CedarHints.doc, "default"), "documentation")
  }

  test("Hints: combines with ++") {
    val h1 = Hints(CedarHints.ownership(OwnershipType.Root))
    val h2 = Hints(CedarHints.doc("Customer entity"))

    val combined = h1 ++ h2
    assertEquals(combined.get(CedarHints.ownership), Some(OwnershipType.Root))
    assertEquals(combined.get(CedarHints.doc), Some("Customer entity"))
  }

  test("Hints: right side takes precedence in ++") {
    val h1 = Hints(CedarHints.ownership(OwnershipType.Root))
    val h2 = Hints(CedarHints.ownership(OwnershipType.Direct))

    val combined = h1 ++ h2
    assertEquals(combined.get(CedarHints.ownership), Some(OwnershipType.Direct))
  }

  test("Hints: creates from multiple bindings") {
    val hints = Hints(
      CedarHints.ownership(OwnershipType.Indirect),
      CedarHints.parents(List("Customer", "Location")),
      CedarHints.doc("Mission entity")
    )

    assertEquals(hints.size, 3)
    assertEquals(hints.get(CedarHints.ownership), Some(OwnershipType.Indirect))
    assertEquals(hints.get(CedarHints.parents), Some(List("Customer", "Location")))
    assertEquals(hints.get(CedarHints.doc), Some("Mission entity"))
  }

  // ===========================================================================
  // ShapeTag Tests
  // ===========================================================================

  test("ShapeTag: creates with id and hints") {
    val tag = ShapeTag[String](
      ShapeId("Test", "MyString"),
      Hints(CedarHints.doc("A string type"))
    )

    assertEquals(tag.id, ShapeId("Test", "MyString"))
    assertEquals(tag.hints.get(CedarHints.doc), Some("A string type"))
  }

  test("ShapeTag: equals based on ShapeId") {
    val tag1 = ShapeTag[String](ShapeId("Test", "Same"))
    val tag2 = ShapeTag[Int](ShapeId("Test", "Same")) // Different type param!
    val tag3 = ShapeTag[String](ShapeId("Test", "Different"))

    // Use .equals for cross-type comparison (tag1 and tag2 have different type params)
    assert(tag1.equals(tag2), "Tags with same ShapeId should be equal")
    assertNotEquals(tag1, tag3) // Different ShapeId
  }

  test("ShapeTag: hashCode based on ShapeId") {
    val tag1 = ShapeTag[String](ShapeId("Test", "Same"))
    val tag2 = ShapeTag[Int](ShapeId("Test", "Same"))

    assertEquals(tag1.hashCode, tag2.hashCode)
  }

  // ===========================================================================
  // ShapeMap Tests
  // ===========================================================================

  // Define test tags
  object CustomerTag extends ShapeTag.Companion[String] {
    val id = ShapeId("Test", "Customer")
    val hints = Hints.empty
  }

  object LocationTag extends ShapeTag.Companion[Int] {
    val id = ShapeId("Test", "Location")
    val hints = Hints.empty
  }

  test("ShapeMap: starts empty") {
    assertEquals(ShapeMap.empty.isEmpty, true)
    assertEquals(ShapeMap.empty.size, 0)
  }

  test("ShapeMap: stores and retrieves type-safe values") {
    val map = ShapeMap.empty
      .put(CustomerTag, "customer-123")
      .put(LocationTag, 42)

    assertEquals(map.get(CustomerTag), Some("customer-123"))
    assertEquals(map.get(LocationTag), Some(42))
  }

  test("ShapeMap: returns None for missing keys") {
    val map = ShapeMap.empty.put(CustomerTag, "value")
    assertEquals(map.get(LocationTag), None)
  }

  test("ShapeMap: apply throws for missing keys") {
    val map = ShapeMap.empty
    intercept[NoSuchElementException] {
      map(CustomerTag)
    }
  }

  test("ShapeMap: removes entries") {
    val map = ShapeMap.empty
      .put(CustomerTag, "value")
      .remove(CustomerTag)

    assertEquals(map.contains(CustomerTag), false)
  }

  test("ShapeMap: combines with ++") {
    val map1 = ShapeMap(CustomerTag, "customer")
    val map2 = ShapeMap(LocationTag, 99)

    val combined = map1 ++ map2
    assertEquals(combined.get(CustomerTag), Some("customer"))
    assertEquals(combined.get(LocationTag), Some(99))
  }

  // ===========================================================================
  // Bijection Tests (replaced CedarEntityId)
  // ===========================================================================

  test("Bijection: String identity bijection works both ways") {
    val bij = Bijection.identity[String]
    assertEquals(bij.to("hello"), "hello")
    assertEquals(bij.from("world"), "world")
  }

  test("Bijection: creates bijection from functions") {
    case class CustomerId(value: String)

    val bij = Bijection[String, CustomerId](CustomerId.apply, _.value)

    assertEquals(bij.to("cust-123"), CustomerId("cust-123"))
    assertEquals(bij.from(CustomerId("cust-456")), "cust-456")
  }

  test("Bijection: compose bijections with imapTarget") {
    case class Inner(s: String)
    case class Outer(inner: Inner)

    val innerBij = Bijection[String, Inner](Inner.apply, _.s)
    val outerBij = Bijection[Inner, Outer](Outer.apply, _.inner)
    val composed = innerBij.imapTarget(outerBij)

    assertEquals(composed.to("hello"), Outer(Inner("hello")))
    assertEquals(composed.from(Outer(Inner("world"))), "world")
  }

  // ===========================================================================
  // Newtype Tests
  // ===========================================================================

  object TestUserId extends Newtype[String]
  type TestUserId = TestUserId.Type

  test("Newtype: wraps and unwraps values") {
    val id = TestUserId("user-123")
    val TestUserId(raw) = id
    assertEquals(raw, "user-123")
  }

  test("Newtype: provides bijection") {
    val bij = TestUserId.bijection
    assertEquals(bij.to("user-456"), TestUserId("user-456"))
    assertEquals(bij.from(TestUserId("user-789")), "user-789")
  }

  test("Newtype: unapply pattern matching works") {
    val id = TestUserId("test-id")
    id match {
      case TestUserId(value) => assertEquals(value, "test-id")
    }
  }

  // ===========================================================================
  // CedarHints Built-in Hints Tests
  // ===========================================================================

  test("CedarHints: ownership hint stores OwnershipType") {
    val hints = Hints(CedarHints.ownership(OwnershipType.Direct))
    assertEquals(hints.get(CedarHints.ownership), Some(OwnershipType.Direct))
  }

  test("CedarHints: parents hint stores parent chain") {
    val hints = Hints(CedarHints.parents(List("Customer", "Location")))
    assertEquals(hints.get(CedarHints.parents), Some(List("Customer", "Location")))
  }

  test("CedarHints: doc hint stores documentation") {
    val hints = Hints(CedarHints.doc("This is a customer"))
    assertEquals(hints.get(CedarHints.doc), Some("This is a customer"))
  }

  test("CedarHints: collectionAction hint stores boolean") {
    val hints = Hints(CedarHints.collectionAction(true))
    assertEquals(hints.get(CedarHints.collectionAction), Some(true))
  }
}
