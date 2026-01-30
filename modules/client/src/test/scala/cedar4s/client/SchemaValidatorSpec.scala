package cedar4s.client

import scala.jdk.OptionConverters._

/** Tests for SchemaValidator.
  *
  * These tests verify that cedar-java's semantic validation catches errors that syntax parsing alone cannot detect.
  */
class SchemaValidatorSpec extends munit.FunSuite {

  // ===========================================================================
  // Valid Schemas
  // ===========================================================================

  test("Valid schemas: validates a simple schema with entities and actions") {
    val schema = """
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val validated = result.toOption.get
    val entityNames = validated.ast.allEntities.map(_.name)
    assert(entityNames.contains("User"))
    assert(entityNames.contains("Document"))
    assert(validated.ast.allActions.map(_.name).contains("read"))
  }

  test("Valid schemas: validates schema with entity hierarchy") {
    val schema = """
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location];
        
        action "view" appliesTo {
          principal: [Customer],
          resource: [Mission],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val hierarchy = result.toOption.get.ast.entityHierarchy
    val ancestors = hierarchy.ancestorsOf("Mission")
    assert(ancestors.contains("Location"))
    assert(ancestors.contains("Customer"))
  }

  test("Valid schemas: validates schema with entity attributes") {
    val schema = """
      namespace Test {
        entity User = {
          "email": String,
          "age"?: Long,
          "active": Bool,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val user = result.toOption.get.ast.findEntity("User").get
    assertEquals(user.attributes.length, 3)
    assertEquals(user.attribute("age").get.optional, true)
  }

  test("Valid schemas: validates schema with common types") {
    val schema = """
      namespace Test {
        type Address = {
          "street": String,
          "city": String,
        };
        
        entity User = {
          "home": Address,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  test("Valid schemas: validates schema with Set types") {
    val schema = """
      namespace Test {
        entity Role;
        entity User = {
          "roles": Set<Role>,
          "tags": Set<String>,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  test("Valid schemas: validates schema with multiple namespaces") {
    val schema = """
      namespace Auth {
        entity User;
      }
      
      namespace Docs {
        entity Document;
        
        action "read" appliesTo {
          principal: [Auth::User],
          resource: [Document],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  test("Valid schemas: validates empty namespace") {
    val schema = """
      namespace Empty {
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  // ===========================================================================
  // Semantic Errors - Undefined References
  // ===========================================================================

  test("Undefined reference detection: detects undefined entity parent") {
    val schema = """
      namespace Test {
        entity User in [NonExistent];
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)

    val error = result.left.toOption.get
    assert(error.isInstanceOf[SchemaValidationError.SemanticErrors])
    assert(error.message.contains("NonExistent"))
  }

  test("Undefined reference detection: detects undefined principal type in action") {
    val schema = """
      namespace Test {
        entity Document;
        
        action "read" appliesTo {
          principal: [UnknownUser],
          resource: [Document],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)

    val error = result.left.toOption.get
    assert(error.isInstanceOf[SchemaValidationError.SemanticErrors])
    assert(error.message.contains("UnknownUser"))
  }

  test("Undefined reference detection: detects undefined resource type in action") {
    val schema = """
      namespace Test {
        entity User;
        
        action "read" appliesTo {
          principal: [User],
          resource: [UnknownResource],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)

    val error = result.left.toOption.get
    assert(error.isInstanceOf[SchemaValidationError.SemanticErrors])
  }

  test("Undefined reference detection: detects undefined common type reference") {
    val schema = """
      namespace Test {
        entity User = {
          "address": UndefinedType,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)

    val error = result.left.toOption.get
    assert(error.isInstanceOf[SchemaValidationError.SemanticErrors])
  }

  test("Undefined reference detection: detects undefined entity reference in attribute") {
    val schema = """
      namespace Test {
        entity User = {
          "manager": NonExistentEntity,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)
  }

  test("Undefined reference detection: detects undefined cross-namespace reference") {
    val schema = """
      namespace Auth {
        entity User;
      }
      
      namespace Docs {
        entity Document;
        
        action "read" appliesTo {
          principal: [Auth::NonExistent],
          resource: [Document],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)
  }

  // ===========================================================================
  // Entity Hierarchy - Cedar allows cycles at schema level
  // ===========================================================================

  // NOTE: Cedar schemas ALLOW cycles in entity hierarchy.
  // The `in` clause declares *possible* parent relationships at the type level,
  // not actual instance relationships. Cycles are only prohibited at runtime
  // (an actual entity instance cannot be its own ancestor).
  // See: https://docs.cedarpolicy.com/schema/human-readable-schema.html
  // Example from docs: `entity Album in Album` is valid!

  test("Entity hierarchy: allows mutual parent relationships (not a cycle error)") {
    val schema = """
      namespace Test {
        entity A in [B];
        entity B in [A];
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val hierarchy = result.toOption.get.ast.entityHierarchy
    assert(hierarchy.parentsOf("A").contains("B"))
    assert(hierarchy.parentsOf("B").contains("A"))
  }

  test("Entity hierarchy: allows self-referential entity (entity X in [X])") {
    val schema = """
      namespace Test {
        entity Folder in [Folder];
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val hierarchy = result.toOption.get.ast.entityHierarchy
    assert(hierarchy.parentsOf("Folder").contains("Folder"))
  }

  test("Entity hierarchy: allows indirect circular references") {
    val schema = """
      namespace Test {
        entity A in [B];
        entity B in [C];
        entity C in [A];
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  test("Entity hierarchy: validates Album in Album pattern from Cedar docs") {
    // This is the exact pattern from Cedar documentation
    val schema = """
      namespace PhotoFlash {
        entity Account;
        entity Album in Album = {
          "account": Account,
          "private": Bool,
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)
  }

  // ===========================================================================
  // Convenience Methods
  // ===========================================================================

  test("Convenience methods: isValid returns true for valid schema") {
    val schema = """
      namespace Test {
        entity User;
      }
    """

    assertEquals(SchemaValidator.isValid(schema), true)
  }

  test("Convenience methods: isValid returns false for invalid schema") {
    val schema = """
      namespace Test {
        entity User in [NonExistent];
      }
    """

    assertEquals(SchemaValidator.isValid(schema), false)
  }

  test("Convenience methods: validateToAst returns just the AST") {
    val schema = """
      namespace Test {
        entity User;
        entity Document;
      }
    """

    val result = SchemaValidator.validateToAst(schema)
    assert(result.isRight)
    assertEquals(result.toOption.get.allEntities.length, 2)
  }

  test("Convenience methods: validateToJava returns just the Java schema") {
    val schema = """
      namespace Test {
        entity User;
      }
    """

    val result = SchemaValidator.validateToJava(schema)
    assert(result.isRight)
    // Schema text should be available
    assertEquals(result.toOption.get.schemaText.toScala.isDefined, true)
  }

  // ===========================================================================
  // Syntax Errors
  // ===========================================================================

  test("Syntax error handling: reports syntax error for invalid schema text") {
    val schema = "this is not a valid cedar schema {"

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)

    // Could be syntax or semantic error depending on what cedar-java reports
    val error = result.left.toOption.get
    assert(error.message.nonEmpty)
  }

  test("Syntax error handling: reports syntax error for malformed entity") {
    val schema = """
      namespace Test {
        entity User =
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isLeft)
  }

  // ===========================================================================
  // JSON Schema Validation
  // ===========================================================================

  test("JSON schema validation: validates a JSON schema") {
    val jsonSchema = """
      {
        "Test": {
          "entityTypes": {
            "User": {},
            "Document": {}
          },
          "actions": {
            "read": {
              "appliesTo": {
                "principalTypes": ["User"],
                "resourceTypes": ["Document"]
              }
            }
          }
        }
      }
    """

    val result = SchemaValidator.validateJson(jsonSchema)
    assert(result.isRight)

    // Note: cedar-java 4.2.2 doesn't support format conversion,
    // so the AST is empty for JSON schemas
    val validated = result.toOption.get
    assertEquals(validated.ast, cedar4s.schema.CedarSchema.empty)
  }

  test("JSON schema validation: detects errors in JSON schema") {
    val jsonSchema = """
      {
        "Test": {
          "entityTypes": {
            "User": {
              "memberOfTypes": ["NonExistent"]
            }
          },
          "actions": {}
        }
      }
    """

    val result = SchemaValidator.validateJson(jsonSchema)
    assert(result.isLeft)
  }

  // ===========================================================================
  // Integration with cedar4s AST
  // ===========================================================================

  test("AST integration: provides full AST for code generation") {
    // NOTE: cedar-java 4.2.2 does NOT support annotations in schemas.
    // Annotations were added in Cedar v4.3+. Our cedar4s parser supports
    // annotations, but we cannot test them through SchemaValidator since
    // it delegates to cedar-java for validation.
    val schema = """
      namespace MyApp {
        entity User = {
          "email": String,
          "active": Bool,
        };
        
        entity Document in [User] = {
          "title": String,
        };
        
        action "Document::read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """

    val result = SchemaValidator.validate(schema)
    assert(result.isRight)

    val ast = result.toOption.get.ast

    // Entities
    val user = ast.findEntity("User").get
    assertEquals(user.attributes.length, 2)

    val doc = ast.findEntity("Document").get
    assert(doc.memberOf.map(_.simple).contains("User"))

    // Actions
    val readAction = ast.findAction("Document::read").get
    assertEquals(readAction.domain, Some("Document"))
    assert(readAction.principalTypes.map(_.simple).contains("User"))

    // Hierarchy
    val hierarchy = ast.entityHierarchy
    assert(hierarchy.roots.contains("User"))
    assert(hierarchy.leaves.contains("Document"))
  }
}
