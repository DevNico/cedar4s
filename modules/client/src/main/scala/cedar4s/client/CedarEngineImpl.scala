package cedar4s.client

import com.cedarpolicy.AuthorizationEngine
import com.cedarpolicy.BasicAuthorizationEngine
import com.cedarpolicy.model.AuthorizationRequest as JAuthorizationRequest
import com.cedarpolicy.model.AuthorizationResponse as JAuthorizationResponse
import com.cedarpolicy.model.entity.Entity as JEntity
import com.cedarpolicy.model.policy.PolicySet as JPolicySet
import com.cedarpolicy.value.EntityUID as JEntityUID
import com.cedarpolicy.value.{
  CedarList,
  CedarMap,
  PrimBool,
  PrimLong,
  PrimString,
  Value as JValue,
  IpAddress as JIpAddress,
  Decimal as JDecimal,
  DateTime as JDateTime,
  Duration as JDuration
}
import cedar4s.capability.Sync
import cedar4s.entities.{CedarEntities, CedarEntity, CedarValue}
import cedar4s.schema.CedarEntityUid

import java.util.{HashMap as JHashMap, HashSet as JHashSet}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.{Try, Using}

/** Implementation of CedarEngine wrapping the cedar-java library.
  *
  * This class handles all Java ↔ Scala type conversions and provides a clean Scala API for Cedar authorization.
  */
private[client] class CedarEngineImpl[F[_]] private (
    policies: JPolicySet
)(implicit F: Sync[F])
    extends CedarEngine[F] {

  private val engine: AuthorizationEngine = new BasicAuthorizationEngine()

  // ============================================================================
  // Public API
  // ============================================================================

  override def authorize(
      request: CedarRequest,
      entities: CedarEntities
  ): F[CedarDecision] = F.blocking {
    val jRequest = toJavaRequest(request)
    val jEntities = toJavaEntities(entities)
    val response = engine.isAuthorized(jRequest, policies, jEntities)
    fromJavaResponse(response)
  }

  override def authorizeBatch(
      requests: Seq[CedarRequest],
      entities: CedarEntities
  ): F[Seq[CedarDecision]] = F.blocking {
    val jEntities = toJavaEntities(entities)
    requests.map { request =>
      val jRequest = toJavaRequest(request)
      val response = engine.isAuthorized(jRequest, policies, jEntities)
      fromJavaResponse(response)
    }
  }

  override def getAllowedActions(
      principal: CedarEntityUid,
      resource: CedarEntityUid,
      actionType: String,
      actions: Set[String],
      entities: CedarEntities
  ): F[Set[String]] = {
    val requests = actions.map { actionName =>
      CedarRequest(
        principal = principal,
        action = CedarEntityUid(actionType, actionName),
        resource = resource,
        context = CedarContext.empty
      )
    }.toSeq

    F.flatMap(authorizeBatch(requests, entities)) { decisions =>
      F.pure(
        requests
          .zip(decisions)
          .filter(_._2.allow)
          .map(_._1.action.entityId)
          .toSet
      )
    }
  }

  // ============================================================================
  // Java Conversion Helpers
  // ============================================================================

  private def toJavaRequest(request: CedarRequest): JAuthorizationRequest = {
    val principal = toJavaEntityUid(request.principal)
    val action = toJavaEntityUid(request.action)
    val resource = toJavaEntityUid(request.resource)
    val context = toJavaContext(request.context)

    new JAuthorizationRequest(principal, action, resource, context)
  }

  private def toJavaEntityUid(uid: CedarEntityUid): JEntityUID = {
    JEntityUID.parse(uid.toCedarString).toScala.getOrElse {
      throw new IllegalArgumentException(s"Failed to parse EntityUID: ${uid.toCedarString}")
    }
  }

  private def toJavaEntities(entities: CedarEntities): java.util.Set[JEntity] = {
    val jEntities = new JHashSet[JEntity]()
    entities.entities.foreach { entity =>
      jEntities.add(toJavaEntity(entity))
    }
    jEntities
  }

  private def toJavaEntity(entity: CedarEntity): JEntity = {
    val uid = toJavaEntityUid(entity.uid)
    val parents = new JHashSet[JEntityUID]()
    entity.parents.foreach(p => parents.add(toJavaEntityUid(p)))

    val attrs = new JHashMap[String, JValue]()
    entity.attributes.foreach { case (k, v) =>
      attrs.put(k, toJavaValue(v))
    }

    new JEntity(uid, attrs, parents)
  }

  private def toJavaContext(context: CedarContext): java.util.Map[String, JValue] = {
    val attrs = new JHashMap[String, JValue]()
    context.attributes.foreach { case (k, v) =>
      attrs.put(k, toJavaValue(v))
    }
    attrs
  }

  private def toJavaValue(value: CedarValue): JValue = {
    value match {
      case CedarValue.StringValue(s)   => new PrimString(s)
      case CedarValue.LongValue(l)     => new PrimLong(l)
      case CedarValue.BoolValue(b)     => new PrimBool(b)
      case CedarValue.SetValue(values) =>
        val jList = new java.util.ArrayList[JValue]()
        values.foreach(v => jList.add(toJavaValue(v)))
        new CedarList(jList)
      case CedarValue.RecordValue(fields) =>
        val jMap = new JHashMap[String, JValue]()
        fields.foreach { case (k, v) => jMap.put(k, toJavaValue(v)) }
        new CedarMap(jMap)
      case CedarValue.EntityValue(uid) =>
        toJavaEntityUid(uid)
      // Extension types
      case CedarValue.IpAddrValue(ip) => new JIpAddress(ip)
      case CedarValue.DecimalValue(d) => new JDecimal(d.toString)
      // DateTime and Duration use cedar-java 4.8.0+ native classes
      case CedarValue.DatetimeValue(instant) =>
        // Cedar datetime format is ISO-8601 (e.g. "2024-01-15T12:30:00Z")
        new JDateTime(instant.toString)
      case CedarValue.DurationValue(duration) =>
        // Cedar duration format is ISO-8601 duration (e.g. "PT1H30M" for 1h30m)
        new JDuration(duration.toString)
    }
  }

  private def fromJavaResponse(response: JAuthorizationResponse): CedarDecision = {
    response.`type` match {
      case JAuthorizationResponse.SuccessOrFailure.Success =>
        val success = response.success.toScala.getOrElse {
          throw new IllegalStateException("Success response missing success field")
        }
        val allow = success.isAllowed

        // Extract diagnostics
        val reason = Option(success.getReason).map(_.asScala.toSet)
        val errors =
          Option(success.getErrors).map(_.asScala.map(_.toString).toList).getOrElse(Nil)

        val diagnostics = Some(
          CedarDiagnostics(
            reason = reason.map(_.mkString(", ")).filter(_.nonEmpty),
            errors = errors,
            policiesSatisfied = reason.getOrElse(Set.empty).toList,
            policiesDenied = Nil
          )
        )

        CedarDecision(allow, diagnostics)

      case JAuthorizationResponse.SuccessOrFailure.Failure =>
        val errors = response.errors.toScala
          .map(_.asScala.map(_.toString).toList)
          .getOrElse(Nil)

        CedarDecision(
          allow = false,
          diagnostics = Some(
            CedarDiagnostics(
              reason = Some("Authorization request failed"),
              errors = errors
            )
          )
        )
    }
  }
}

private[client] object CedarEngineImpl {

  /** Create a CedarEngine from classpath resources.
    *
    * Loads Cedar policies from resource files and creates an engine instance.
    *
    * @param policiesPath
    *   Base path for policy resources
    * @param policyFiles
    *   Specific policy files to load
    * @param schemaPath
    *   Optional schema path (currently unused but reserved for future use)
    * @param F
    *   Sync capability for effect type F
    * @tparam F
    *   Effect type
    * @return
    *   CedarEngine instance
    */
  def apply[F[_]](
      policiesPath: String,
      policyFiles: Seq[String],
      schemaPath: Option[String]
  )(implicit F: Sync[F]): CedarEngine[F] = {
    val policies = loadPolicies(policiesPath, policyFiles)
    new CedarEngineImpl[F](policies)
  }

  /** Create a CedarEngine from policy content strings.
    *
    * Parses Cedar policy text and creates an engine instance.
    *
    * @param policyContent
    *   Cedar policy text in Cedar language format
    * @param F
    *   Sync capability for effect type F
    * @tparam F
    *   Effect type
    * @return
    *   CedarEngine instance
    */
  def fromPolicies[F[_]](
      policyContent: String
  )(implicit F: Sync[F]): CedarEngine[F] = {
    val policies = JPolicySet.parsePolicies(policyContent)
    new CedarEngineImpl[F](policies)
  }

  private def loadPolicies(policiesPath: String, policyFiles: Seq[String]): JPolicySet = {
    val policyContent = if (policyFiles.nonEmpty) {
      policyFiles.flatMap { filename =>
        val fullPath = s"$policiesPath/$filename"
        Try(loadResourceFile(fullPath)).toOption
      }
    } else {
      // Try to load common policy files as fallback
      val defaultFiles = Seq("policies.cedar", "common.cedar")
      defaultFiles.flatMap { filename =>
        val fullPath = s"$policiesPath/$filename"
        Try(loadResourceFile(fullPath)).toOption
      }
    }

    if (policyContent.isEmpty) {
      throw new IllegalStateException(s"No Cedar policy files found in $policiesPath")
    }

    val combinedPolicies = policyContent.mkString("\n\n")
    JPolicySet.parsePolicies(combinedPolicies)
  }

  private def loadResourceFile(path: String): String = {
    val resourcePath = if (path.startsWith("/")) path else s"/$path"
    val stream = getClass.getResourceAsStream(resourcePath)
    if (stream == null) {
      throw new IllegalArgumentException(s"Resource not found: $resourcePath")
    }
    Using.resource(stream) { s =>
      Source.fromInputStream(s).mkString
    }
  }
}
