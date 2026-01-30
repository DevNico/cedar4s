package cedar4s.client

import munit.FunSuite

/** Tests for PolicyValidator.
  */
class PolicyValidatorSpec extends FunSuite {

  // Helper to create a validated schema
  private def validatedSchema(schemaText: String): ValidatedSchema =
    SchemaValidator
      .validate(schemaText)
      .getOrElse(
        throw new AssertionError(s"Schema should be valid: $schemaText")
      )

  // ===========================================================================
  // Valid Policies
  // ===========================================================================

  test("Valid policies: validates a simple permit policy") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal == Test::User::"alice",
        action == Test::Action::"read",
        resource == Test::Document::"doc1"
      );
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    assertEquals(result.toOption.get.isValid, true)
    assert(result.toOption.get.errors.isEmpty)
  }

  test("Valid policies: validates a forbid policy") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "delete" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      forbid(
        principal,
        action == Test::Action::"delete",
        resource
      )
      unless { principal == Test::User::"admin" };
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    assertEquals(result.toOption.get.isValid, true)
  }

  test("Valid policies: validates policy with attribute access") {
    val schema = validatedSchema("""
      namespace Test {
        entity User = {
          "role": String,
          "department": String,
        };
        entity Document = {
          "owner": User,
          "classification": String,
        };
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal,
        action == Test::Action::"read",
        resource
      )
      when { principal.role == "admin" || resource.owner == principal };
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    assertEquals(result.toOption.get.isValid, true)
  }

  test("Valid policies: validates multiple policies") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
        
        action "write" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      // Anyone can read
      permit(
        principal,
        action == Test::Action::"read",
        resource
      );
      
      // Only owner can write (simplified - no owner attr for now)
      forbid(
        principal,
        action == Test::Action::"write",
        resource
      );
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    assertEquals(result.toOption.get.isValid, true)
  }

  // ===========================================================================
  // Invalid Policies - Type Errors
  // ===========================================================================

  test("Invalid policies - type errors: detects undefined entity type in principal") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal == Test::Admin::"alice",
        action == Test::Action::"read",
        resource
      );
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    // This should have validation errors for undefined Admin type
    assertEquals(result.toOption.get.hasErrors, true)
  }

  test("Invalid policies - type errors: detects undefined action") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal,
        action == Test::Action::"delete",
        resource
      );
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    // This should have validation errors for undefined delete action
    assertEquals(result.toOption.get.hasErrors, true)
  }

  test("Invalid policies - type errors: detects undefined attribute access") {
    val schema = validatedSchema("""
      namespace Test {
        entity User = {
          "name": String,
        };
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal,
        action == Test::Action::"read",
        resource
      )
      when { principal.nonexistent_attr == "value" };
    """

    val result = PolicyValidator.validate(policies, schema)
    assert(result.isRight)
    // This should have validation errors for undefined attribute
    assertEquals(result.toOption.get.hasErrors, true)
  }

  // ===========================================================================
  // Convenience Methods
  // ===========================================================================

  test("Convenience methods: isValid returns true for valid policies") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(principal, action, resource);
    """

    assertEquals(PolicyValidator.isValid(policies, schema), true)
  }

  test("Convenience methods: isValid returns false for invalid policies") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val policies = """
      permit(
        principal,
        action == Test::Action::"nonexistent",
        resource
      );
    """

    assertEquals(PolicyValidator.isValid(policies, schema), false)
  }

  // ===========================================================================
  // Syntax Errors
  // ===========================================================================

  test("Syntax errors: reports error for malformed policy") {
    val schema = validatedSchema("""
      namespace Test {
        entity User;
      }
    """)

    val policies = "this is not valid cedar policy syntax {"

    val result = PolicyValidator.validate(policies, schema)
    // Should return Left with error message for syntax error
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("failed"))
  }

  // ===========================================================================
  // Validation Result API
  // ===========================================================================

  test("ValidationResult API: summary provides human-readable output") {
    val result = PolicyValidationResult(
      errors = List(
        PolicyValidationError(Some("policy1"), "Unknown entity type"),
        PolicyValidationError(Some("policy2"), "Undefined attribute")
      ),
      warnings = List(
        PolicyValidationWarning(None, "Unused variable")
      )
    )

    val summary = result.summary
    assert(summary.contains("2 error(s)"))
    assert(summary.contains("policy1"))
    assert(summary.contains("policy2"))
    assert(summary.contains("1 warning(s)"))
  }

  test("ValidationResult API: empty result is valid") {
    assertEquals(PolicyValidationResult.empty.isValid, true)
    assertEquals(PolicyValidationResult.empty.hasErrors, false)
    assertEquals(PolicyValidationResult.empty.hasWarnings, false)
  }
}
