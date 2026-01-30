package cedar4s.observability.otel

import munit.FunSuite

class SpanNamingStrategyTest extends FunSuite {
  import TestFixtures._

  test("default strategy returns cedar.authorization") {
    val strategy = SpanNamingStrategy.default
    val response = createAllowedResponse()

    assertEquals(strategy.spanName(response), "cedar.authorization")
  }

  test("byAction strategy includes action name") {
    val strategy = SpanNamingStrategy.byAction
    val response = createAllowedResponse(actionName = "Edit")

    assertEquals(strategy.spanName(response), "cedar.authorization.Edit")
  }

  test("byAction extracts action name from Cedar format") {
    val strategy = SpanNamingStrategy.byAction
    val response = createAllowedResponse(actionName = "View")

    assertEquals(strategy.spanName(response), "cedar.authorization.View")
  }

  test("byResourceType strategy includes resource type") {
    val strategy = SpanNamingStrategy.byResourceType
    val response = createAllowedResponse(resourceType = "Folder")

    assertEquals(strategy.spanName(response), "cedar.authorization.Folder")
  }

  test("byActionAndResourceType combines both") {
    val strategy = SpanNamingStrategy.byActionAndResourceType
    val response = createAllowedResponse(actionName = "Edit", resourceType = "Document")

    assertEquals(strategy.spanName(response), "cedar.authorization.Document.Edit")
  }

  test("custom strategy allows arbitrary logic") {
    val strategy = SpanNamingStrategy { response =>
      if (response.allowed) {
        "cedar.allowed"
      } else {
        "cedar.denied"
      }
    }

    val allowedResponse = createAllowedResponse()
    assertEquals(strategy.spanName(allowedResponse), "cedar.allowed")

    val deniedResponse = createDeniedResponse()
    assertEquals(strategy.spanName(deniedResponse), "cedar.denied")
  }
}
