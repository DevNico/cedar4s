package example.playframework

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.test.Helpers._
import play.api.test.FakeRequest

import java.util.UUID

class ApiControllerSpec extends AnyWordSpec with Matchers with GuiceOneAppPerSuite {

  override def fakeApplication(): Application = {
    val dbName = s"cedar4s-test-${UUID.randomUUID()}"
    GuiceApplicationBuilder()
      .configure(
        "db.db.url" -> s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
      )
      .build()
  }

  private def issueToken(userId: String): String = {
    val request = FakeRequest(POST, "/auth/token")
      .withJsonBody(Json.obj("userId" -> userId))
    val response = route(app, request).getOrElse(fail("No route for /auth/token"))
    status(response) shouldBe OK
    (contentAsJson(response) \ "token").as[String]
  }

  private def authHeader(token: String): (String, String) =
    "Authorization" -> s"Bearer $token"

  private def toDocs(json: JsValue): Seq[JsObject] =
    (json \ "documents").as[Seq[JsObject]]

  "PlayFramework API" should {
    "issue a JWT for a user" in {
      val request = FakeRequest(POST, "/auth/token")
        .withJsonBody(Json.obj("userId" -> "alice"))
      val response = route(app, request).getOrElse(fail("No route for /auth/token"))
      status(response) shouldBe OK
      (contentAsJson(response) \ "token").as[String] should not be empty
    }

    "reject requests without tokens" in {
      val request = FakeRequest(GET, "/documents/doc-1")
      val response = route(app, request).getOrElse(fail("No route for /documents/doc-1"))
      status(response) shouldBe UNAUTHORIZED
    }

    "return a document with capabilities for the owner" in {
      val token = issueToken("alice")
      val request = FakeRequest(GET, "/documents/doc-1")
        .withHeaders(authHeader(token))
      val response = route(app, request).getOrElse(fail("No route for /documents/doc-1"))
      status(response) shouldBe OK
      val json = contentAsJson(response)
      (json \ "id").as[String] shouldBe "doc-1"
      (json \ "ownerId").as[String] shouldBe "alice"
      val actions = (json \ "allowedActions").as[Seq[String]].toSet
      actions should contain("Read")
      actions should contain("Write")
    }

    "return forbidden when a user cannot read a private document" in {
      val token = issueToken("bob")
      val request = FakeRequest(GET, "/documents/doc-1")
        .withHeaders(authHeader(token))
      val response = route(app, request).getOrElse(fail("No route for /documents/doc-1"))
      status(response) shouldBe FORBIDDEN
    }

    "return not found for unknown documents" in {
      val token = issueToken("alice")
      val request = FakeRequest(GET, "/documents/unknown")
        .withHeaders(authHeader(token))
      val response = route(app, request).getOrElse(fail("No route for /documents/unknown"))
      status(response) shouldBe NOT_FOUND
    }

    "list only documents allowed for the user in an org" in {
      val token = issueToken("alice")
      val request = FakeRequest(GET, "/documents?orgId=org-1")
        .withHeaders(authHeader(token))
      val response = route(app, request).getOrElse(fail("No route for /documents"))
      status(response) shouldBe OK
      val docs = toDocs(contentAsJson(response))
      val ids = docs.map(obj => (obj \ "id").as[String]).toSet
      ids shouldBe Set("doc-1", "doc-2")
    }

    "allow public documents to be listed even for non-members" in {
      val token = issueToken("service")
      val request = FakeRequest(GET, "/documents?orgId=org-1")
        .withHeaders(authHeader(token))
      val response = route(app, request).getOrElse(fail("No route for /documents"))
      status(response) shouldBe OK
      val docs = toDocs(contentAsJson(response))
      val ids = docs.map(obj => (obj \ "id").as[String]).toSet
      ids shouldBe Set("doc-2")
    }

    "create documents for org members and include capabilities" in {
      val token = issueToken("alice")
      val request = FakeRequest(POST, "/documents")
        .withHeaders(authHeader(token))
        .withJsonBody(
          Json.obj(
            "orgId" -> "org-1",
            "title" -> "Q1 Plan",
            "visibility" -> "private"
          )
        )
      val response = route(app, request).getOrElse(fail("No route for /documents"))
      status(response) shouldBe CREATED
      val json = contentAsJson(response)
      (json \ "orgId").as[String] shouldBe "org-1"
      (json \ "ownerId").as[String] shouldBe "alice"
      val actions = (json \ "allowedActions").as[Seq[String]].toSet
      actions should contain("Read")
      actions should contain("Write")
    }

    "forbid document creation for non-members" in {
      val token = issueToken("alice")
      val request = FakeRequest(POST, "/documents")
        .withHeaders(authHeader(token))
        .withJsonBody(
          Json.obj(
            "orgId" -> "org-2",
            "title" -> "Should Fail",
            "visibility" -> "private"
          )
        )
      val response = route(app, request).getOrElse(fail("No route for /documents"))
      status(response) shouldBe FORBIDDEN
    }
  }
}
