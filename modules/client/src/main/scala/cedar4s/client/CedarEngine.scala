package cedar4s.client

import cedar4s.entities.CedarEntities
import cedar4s.schema.CedarEntityUid
import cedar4s.capability.Sync

/** Low-level Cedar authorization engine.
  *
  * This trait wraps the cedar-java library, providing:
  *   - Authorization requests against loaded policies
  *   - Batch authorization for efficiency
  *   - Allowed actions computation for capability checks
  *
  * Thread-safety: Implementations should be thread-safe and can be shared across threads.
  *
  * Usage:
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  * import cedar4s.capability.instances._
  *
  * val engine = CedarEngine.fromResources[Future]("/policies", Some("/schema.cedarschema"))
  * val decision = engine.authorize(request, entities)
  * }}}
  *
  * @tparam F
  *   The effect type (Future, IO, etc.)
  */
trait CedarEngine[F[_]] {

  /** Authorize a request with entities.
    *
    * @param request
    *   The authorization request (principal, action, resource, context)
    * @param entities
    *   The entities relevant to this request
    * @return
    *   Authorization decision
    */
  def authorize(
      request: CedarRequest,
      entities: CedarEntities
  ): F[CedarDecision]

  /** Authorize a batch of requests efficiently.
    *
    * All requests share the same entity set (useful for capability checks).
    *
    * @param requests
    *   The authorization requests
    * @param entities
    *   The entities relevant to all requests
    * @return
    *   Decisions in the same order as requests
    */
  def authorizeBatch(
      requests: Seq[CedarRequest],
      entities: CedarEntities
  ): F[Seq[CedarDecision]]

  /** Get all allowed actions for a principal on a resource.
    *
    * Used for capability/allowedActions computation.
    *
    * @param principal
    *   The principal entity
    * @param resource
    *   The resource entity
    * @param actionType
    *   The entity type for actions (e.g., "MyApp::Action")
    * @param actions
    *   All possible action names to check
    * @param entities
    *   The entities for evaluation
    * @return
    *   Set of allowed action names
    */
  def getAllowedActions(
      principal: CedarEntityUid,
      resource: CedarEntityUid,
      actionType: String,
      actions: Set[String],
      entities: CedarEntities
  ): F[Set[String]]
}

object CedarEngine {

  /** Create a CedarEngine from classpath resources.
    *
    * Note: The schemaPath parameter is currently not used for validation. For schema validation, use
    * `fromResourcesValidated` instead.
    *
    * @param policiesPath
    *   Path to directory containing .cedar policy files
    * @param schemaPath
    *   Optional path to .cedarschema file (currently unused)
    * @param policyFiles
    *   List of policy filenames to load (workaround for JAR resource listing)
    * @param F
    *   Sync instance for effect type F
    * @tparam F
    *   The effect type
    */
  def fromResources[F[_]: Sync](
      policiesPath: String,
      policyFiles: Seq[String] = Seq.empty,
      schemaPath: Option[String] = None
  ): CedarEngine[F] = {
    CedarEngineImpl[F](policiesPath, policyFiles, schemaPath)
  }

  /** Create a CedarEngine from policy content strings.
    *
    * @param policies
    *   The Cedar policy content
    * @param F
    *   Sync instance for effect type F
    * @tparam F
    *   The effect type
    */
  def fromPolicies[F[_]: Sync](
      policies: String
  ): CedarEngine[F] = {
    CedarEngineImpl.fromPolicies[F](policies)
  }

  /** Create a CedarEngine with validated policies and schema.
    *
    * This factory method validates both the schema and policies before creating the engine, catching errors at startup
    * rather than runtime.
    *
    * @param policiesText
    *   The Cedar policies in human-readable format
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @param F
    *   Sync instance for effect type F
    * @tparam F
    *   The effect type
    * @return
    *   Either validation errors or a configured CedarEngine
    */
  def fromValidatedPolicies[F[_]: Sync](
      policiesText: String,
      schemaText: String
  ): Either[String, (CedarEngine[F], ValidatedSchema)] = {
    // Validate schema
    SchemaValidator.validate(schemaText) match {
      case Left(schemaError) =>
        Left(s"Schema validation failed: ${schemaError.message}")

      case Right(validatedSchema) =>
        // Validate policies against schema
        PolicyValidator.validate(policiesText, validatedSchema) match {
          case Left(policyError) =>
            Left(s"Policy validation failed: $policyError")

          case Right(result) if result.hasErrors =>
            Left(s"Policy validation errors:\n${result.summary}")

          case Right(_) =>
            // Both schema and policies are valid - create engine
            val engine = CedarEngineImpl.fromPolicies[F](policiesText)
            Right((engine, validatedSchema))
        }
    }
  }

  /** Create a CedarEngine with validated policies and schema, allowing warnings.
    *
    * Like `fromValidatedPolicies`, but returns warnings along with the engine instead of failing on warnings. Only
    * errors cause failure.
    *
    * @param policiesText
    *   The Cedar policies in human-readable format
    * @param schemaText
    *   The Cedar schema in human-readable format
    * @param F
    *   Sync instance for effect type F
    * @tparam F
    *   The effect type
    * @return
    *   Either validation errors or (CedarEngine, ValidatedSchema, warnings)
    */
  def fromValidatedPoliciesWithWarnings[F[_]: Sync](
      policiesText: String,
      schemaText: String
  ): Either[String, (CedarEngine[F], ValidatedSchema, PolicyValidationResult)] = {
    // Validate schema
    SchemaValidator.validate(schemaText) match {
      case Left(schemaError) =>
        Left(s"Schema validation failed: ${schemaError.message}")

      case Right(validatedSchema) =>
        // Validate policies against schema
        PolicyValidator.validate(policiesText, validatedSchema) match {
          case Left(policyError) =>
            Left(s"Policy validation failed: $policyError")

          case Right(result) if result.hasErrors =>
            Left(s"Policy validation errors:\n${result.summary}")

          case Right(result) =>
            // Policies are valid (may have warnings)
            val engine = CedarEngineImpl.fromPolicies[F](policiesText)
            Right((engine, validatedSchema, result))
        }
    }
  }
}
