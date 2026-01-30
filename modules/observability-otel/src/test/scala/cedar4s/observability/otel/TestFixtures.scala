package cedar4s.observability.otel

import cedar4s.auth.{CedarAction, CedarResource, Principal}
import cedar4s.client.{AuthorizationResponse, CedarContext, CedarDecision, CedarDiagnostics}
import cedar4s.entities.{CedarEntities, CedarEntity, CedarPrincipal}
import cedar4s.schema.CedarEntityUid

import java.time.Instant

object TestFixtures {

  case class TestPrincipal(
      override val entityType: String,
      override val entityId: String
  ) extends Principal

  case class TestAction(
      override val name: String,
      override val cedarAction: String,
      override val isCollectionAction: Boolean = false
  ) extends CedarAction

  case class TestResource(
      override val entityType: String,
      override val entityId: Option[String],
      override val parents: List[(String, String)] = Nil
  ) extends CedarResource {
    override def toCedarEntity: String = entityId match {
      case Some(id) => s"$entityType::\"$id\""
      case None     => s"$entityType::\"__collection__\""
    }
  }

  def createAllowedResponse(
      principalId: String = "alice",
      actionName: String = "View",
      resourceType: String = "Document",
      resourceId: String = "doc-123"
  ): AuthorizationResponse = {
    val principal = TestPrincipal("User", principalId)
    val action = TestAction(actionName, s"Document::Action::\"$actionName\"")
    val resource = TestResource(resourceType, Some(resourceId))

    AuthorizationResponse(
      timestamp = Instant.now(),
      durationNanos = 5_000_000, // 5ms
      principal = principal,
      cedarPrincipal = CedarPrincipal(
        CedarEntityUid("User", principalId),
        CedarEntities.empty
      ),
      action = action,
      resource = resource,
      context = CedarContext.empty,
      entities = CedarEntities.empty,
      decision = CedarDecision(allow = true, diagnostics = None),
      errors = Nil
    )
  }

  def createDeniedResponse(denyReason: Option[String] = Some("Policy forbids")): AuthorizationResponse = {
    val response = createAllowedResponse()
    response.copy(
      decision = CedarDecision(
        allow = false,
        diagnostics = Some(CedarDiagnostics(reason = denyReason))
      )
    )
  }
}
