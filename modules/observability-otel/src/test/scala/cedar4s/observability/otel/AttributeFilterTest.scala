package cedar4s.observability.otel

import munit.FunSuite

class AttributeFilterTest extends FunSuite {
  import TestFixtures._

  test("includeAll filter includes everything") {
    val filter = AttributeFilter.includeAll
    val response = createAllowedResponse()

    assert(filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DECISION, response))
  }

  test("minimal filter only includes essential attributes") {
    val filter = AttributeFilter.minimal
    val response = createAllowedResponse()

    // Should include
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DECISION, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_ACTION_NAME, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_TYPE, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DURATION_MS, response))

    // Should exclude
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_UID, response))
  }

  test("excludePrincipalIds excludes principal IDs") {
    val filter = AttributeFilter.excludePrincipalIds
    val response = createAllowedResponse()

    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_UID, response))

    // Other attributes should be included
    assert(filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_TYPE, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))
  }

  test("excludeResourceIds excludes resource IDs") {
    val filter = AttributeFilter.excludeResourceIds
    val response = createAllowedResponse()

    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_UID, response))

    // Other attributes should be included
    assert(filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_TYPE, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
  }

  test("excludeEntityIds excludes all entity IDs") {
    val filter = AttributeFilter.excludeEntityIds
    val response = createAllowedResponse()

    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_UID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_UID, response))

    // Type information should still be included
    assert(filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_TYPE, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_TYPE, response))
  }

  test("excludeDenyReasons excludes deny diagnostics") {
    val filter = AttributeFilter.excludeDenyReasons
    val response = createAllowedResponse()

    assert(!filter.shouldInclude(SemanticConventions.CEDAR_DENY_REASON, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_DENY_REASON_COUNT, response))

    // Other attributes should be included
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DECISION, response))
  }

  test("custom filter allows arbitrary logic") {
    val filter = AttributeFilter { (attr, response) =>
      attr match {
        case SemanticConventions.CEDAR_PRINCIPAL_ID if response.principal.entityId.contains("test") =>
          false // Exclude test users
        case _ => true
      }
    }

    val testResponse = createAllowedResponse(principalId = "test-user")
    val prodResponse = createAllowedResponse(principalId = "prod-user")

    // Should exclude principal ID for test users
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, testResponse))

    // Should include for prod users
    assert(filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, prodResponse))
  }

  test("and combinator requires all filters to allow") {
    val filter = AttributeFilter.and(
      AttributeFilter.excludePrincipalIds,
      AttributeFilter.excludeResourceIds
    )

    val response = createAllowedResponse()

    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_RESOURCE_ID, response))

    // Other attributes should be included (both filters allow)
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DECISION, response))
  }

  test("or combinator allows if any filter allows") {
    val onlyDecision = AttributeFilter { (attr, _) =>
      attr == SemanticConventions.CEDAR_DECISION
    }
    val onlyAction = AttributeFilter { (attr, _) =>
      attr == SemanticConventions.CEDAR_ACTION
    }

    val filter = AttributeFilter.or(onlyDecision, onlyAction)
    val response = createAllowedResponse()

    // Should include if either filter allows
    assert(filter.shouldInclude(SemanticConventions.CEDAR_DECISION, response))
    assert(filter.shouldInclude(SemanticConventions.CEDAR_ACTION, response))

    // Should exclude if neither allows
    assert(!filter.shouldInclude(SemanticConventions.CEDAR_PRINCIPAL_ID, response))
  }
}
