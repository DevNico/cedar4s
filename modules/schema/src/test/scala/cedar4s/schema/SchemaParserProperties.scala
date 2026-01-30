package cedar4s.schema

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.forAll

/** Property-based tests for Cedar schema parsing.
  *
  * These tests use ScalaCheck generators to create arbitrary valid schemas and verify parser behavior with random
  * inputs.
  */
class SchemaParserProperties extends ScalaCheckSuite {

  // ===========================================================================
  // Generators
  // ===========================================================================

  /** Cedar reserved keywords that cannot be used as identifiers */
  private val cedarKeywords: Set[String] = Set(
    "if",
    "then",
    "else",
    "in",
    "is",
    "like",
    "has",
    "true",
    "false",
    "permit",
    "forbid",
    "when",
    "unless",
    "principal",
    "action",
    "resource",
    "context",
    "entity",
    "namespace",
    "type",
    "Set",
    "String",
    "Long",
    "Bool",
    "Record",
    "Entity",
    "Extension",
    "Action"
  )

  /** Generate valid Cedar identifiers (not keywords) */
  val identifierGen: Gen[String] = (for {
    first <- Gen.alphaChar
    restLen <- Gen.choose(1, 15)
    rest <- Gen.listOfN(restLen, Gen.alphaNumChar)
  } yield (first +: rest).mkString).suchThat(!cedarKeywords.contains(_))

  /** Generate valid namespace names (optionally qualified) */
  val namespaceNameGen: Gen[String] = for {
    parts <- Gen.choose(1, 3).flatMap(n => Gen.listOfN(n, identifierGen))
  } yield parts.mkString("::")

  /** Generate primitive type expressions */
  val primitiveTypeGen: Gen[String] = Gen.oneOf("String", "Long", "Bool")

  /** Generate entity reference type */
  def entityRefTypeGen(entityNames: List[String]): Gen[String] =
    if (entityNames.isEmpty) Gen.const("String")
    else Gen.oneOf(entityNames)

  /** Generate simple type expressions (primitive or entity ref) */
  def simpleTypeGen(entityNames: List[String]): Gen[String] =
    Gen.frequency(
      3 -> primitiveTypeGen,
      1 -> entityRefTypeGen(entityNames)
    )

  /** Generate type expressions including containers */
  def typeExprGen(entityNames: List[String]): Gen[String] =
    Gen.frequency(
      5 -> simpleTypeGen(entityNames),
      2 -> simpleTypeGen(entityNames).map(t => s"Set<$t>"),
      1 -> Gen.const("ipaddr"),
      1 -> Gen.const("decimal"),
      1 -> Gen.const("datetime"),
      1 -> Gen.const("duration")
    )

  /** Generate an attribute definition */
  def attributeGen(entityNames: List[String]): Gen[String] = for {
    name <- identifierGen
    typeExpr <- typeExprGen(entityNames)
    optional <- Gen.oneOf("", "?")
  } yield s""""$name"$optional: $typeExpr"""

  /** Generate entity definition */
  def entityGen(availableParents: List[String], allEntityNames: List[String]): Gen[String] = for {
    name <- identifierGen
    numParents <- Gen.choose(0, Math.min(2, availableParents.size))
    parents <- Gen.pick(numParents, availableParents).map(_.toList)
    numAttrs <- Gen.choose(0, 5)
    attrs <- Gen.listOfN(numAttrs, attributeGen(allEntityNames))
    parentClause = if (parents.isEmpty) "" else s" in [${parents.mkString(", ")}]"
    attrBlock = if (attrs.isEmpty) ";" else s" = {\n    ${attrs.mkString(",\n    ")}\n  };"
  } yield s"  entity $name$parentClause$attrBlock"

  /** Generate action definition */
  def actionGen(entityNames: List[String]): Gen[String] = for {
    actionName <- Gen.oneOf("create", "read", "update", "delete", "list", "share")
    entityName <- if (entityNames.isEmpty) identifierGen else Gen.oneOf(entityNames)
    fullName = s"$entityName::$actionName"
  } yield s"""  action "$fullName" appliesTo {
    principal: [User],
    resource: [$entityName]
  };"""

  /** Generate a complete namespace with entities and actions */
  val namespaceGen: Gen[String] = for {
    nsName <- identifierGen
    numEntities <- Gen.choose(1, 5)
    entityNames <- Gen.listOfN(numEntities, identifierGen).map(_.distinct)
    // Build entities sequentially so parents exist before children
    entities <- entityNames.zipWithIndex.foldLeft(Gen.const(List.empty[String])) { case (acc, (name, idx)) =>
      acc.flatMap { existing =>
        val availableParents = entityNames.take(idx)
        for {
          numParents <- Gen.choose(0, Math.min(1, availableParents.size))
          parents <-
            if (availableParents.isEmpty) Gen.const(Nil) else Gen.pick(numParents, availableParents).map(_.toList)
          numAttrs <- Gen.choose(0, 3)
          attrs <- Gen.listOfN(numAttrs, attributeGen(entityNames))
          parentClause = if (parents.isEmpty) "" else s" in [${parents.mkString(", ")}]"
          attrBlock = if (attrs.isEmpty) ";" else s" = {\n    ${attrs.mkString(",\n    ")}\n  };"
        } yield existing :+ s"  entity $name$parentClause$attrBlock"
      }
    }
    // Always add a User entity for principals
    userEntity = "  entity User = { \"name\": String, \"email\": String };"
    numActions <- Gen.choose(1, 3)
    // Generate distinct actions to avoid cedar-java rejecting duplicates
    actions <- Gen.listOfN(numActions, actionGen(entityNames)).map(_.distinct)
  } yield s"""namespace $nsName {
$userEntity
${entities.mkString("\n")}

${actions.mkString("\n")}
}"""

  // ===========================================================================
  // Properties
  // ===========================================================================

  property("valid identifiers are parsed as entity names") {
    forAll(identifierGen) { name =>
      val schema = s"""namespace Test { entity $name; }"""
      val result = CedarSchema.parse(schema)
      result.isRight
    }
  }

  property("primitive types are recognized") {
    forAll(primitiveTypeGen) { typeName =>
      val schema = s"""namespace Test { entity Foo = { "attr": $typeName }; }"""
      val result = CedarSchema.parse(schema)
      result.isRight
    }
  }

  property("Set types are recognized") {
    forAll(primitiveTypeGen) { innerType =>
      val schema = s"""namespace Test { entity Foo = { "items": Set<$innerType> }; }"""
      val result = CedarSchema.parse(schema)
      result.isRight
    }
  }

  property("entity hierarchies are parsed correctly") {
    forAll(Gen.choose(2, 5)) { depth =>
      val entityNames = (1 to depth).map(i => s"Entity$i")
      val entities = entityNames.zipWithIndex.map { case (name, idx) =>
        if (idx == 0) s"  entity $name;"
        else s"  entity $name in [${entityNames(idx - 1)}];"
      }
      val schema = s"""namespace Test {
${entities.mkString("\n")}
}"""
      val result = CedarSchema.parse(schema)
      result match {
        case Right(parsed) =>
          val hierarchy = EntityHierarchy.build(parsed)
          // First entity should be root
          hierarchy.roots.contains(entityNames.head) &&
          // Last entity should be leaf
          hierarchy.leaves.contains(entityNames.last) &&
          // Depth should be correct
          hierarchy.depthOf(entityNames.last) == depth - 1
        case Left(_) => false
      }
    }
  }

  property("generated namespaces parse successfully") {
    forAll(namespaceGen) { schema =>
      val result = CedarSchema.parse(schema)
      result.isRight
    }
  }

  property("parsing is deterministic") {
    forAll(namespaceGen) { schema =>
      val result1 = CedarSchema.parse(schema)
      val result2 = CedarSchema.parse(schema)
      result1 == result2
    }
  }

  property("entity names are preserved during parsing") {
    forAll(Gen.listOfN(3, identifierGen).map(_.distinct).suchThat(_.size >= 2)) { names =>
      val entities = names.map(n => s"  entity $n;").mkString("\n")
      val schema = s"""namespace Test {
$entities
}"""
      val result = CedarSchema.parse(schema)
      result match {
        case Right(parsed) =>
          names.forall(n => parsed.findEntity(n).isDefined)
        case Left(_) => false
      }
    }
  }

  property("attribute names are preserved during parsing") {
    forAll(Gen.listOfN(3, identifierGen).map(_.distinct).suchThat(_.size >= 2)) { attrNames =>
      val attrs = attrNames.map(n => s""""$n": String""").mkString(",\n    ")
      val schema = s"""namespace Test {
  entity Foo = {
    $attrs
  };
}"""
      val result = CedarSchema.parse(schema)
      result match {
        case Right(parsed) =>
          val entity = parsed.findEntity("Foo")
          entity.exists(e => attrNames.forall(n => e.attribute(n).isDefined))
        case Left(_) => false
      }
    }
  }

  property("optional attributes are marked optional") {
    forAll(identifierGen) { attrName =>
      val schema = s"""namespace Test {
  entity Foo = {
    "$attrName"?: String
  };
}"""
      val result = CedarSchema.parse(schema)
      result match {
        case Right(parsed) =>
          parsed.findEntity("Foo").exists(_.attribute(attrName).exists(_.optional))
        case Left(_) => false
      }
    }
  }

  // ===========================================================================
  // Edge Cases
  // ===========================================================================

  test("empty namespace parses") {
    val schema = "namespace Empty {}"
    val result = CedarSchema.parse(schema)
    assert(result.isRight)
  }

  test("single-line comments are ignored") {
    // Note: Cedar schema parser only supports single-line // comments, not /* */ block comments
    val schema = """namespace Test {
  // This is a comment
  entity Foo;
  // Another comment
  entity Bar;
}"""
    val result = CedarSchema.parse(schema)
    assert(result.isRight)
    assertEquals(result.map(_.allEntities.size), Right(2))
  }

  test("qualified namespace names work") {
    val schema = "namespace Acme::Corp::App { entity User; }"
    val result = CedarSchema.parse(schema)
    assert(result.isRight)
  }
}
