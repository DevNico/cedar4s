package cedar4s.client

import com.cedarpolicy.BasicAuthorizationEngine
import com.cedarpolicy.model.{ValidationRequest, ValidationResponse}
import com.cedarpolicy.model.policy.PolicySet
import com.cedarpolicy.model.exception.AuthException

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal

/** Error from policy validation.
  *
  * @param policyId
  *   The ID of the policy that has the error (if available)
  * @param message
  *   The error message
  * @param details
  *   Additional error details from Cedar
  */
final case class PolicyValidationError(
    policyId: Option[String],
    message: String,
    details: Option[String] = None
)

/** Warning from policy validation.
  *
  * @param policyId
  *   The ID of the policy that has the warning (if available)
  * @param message
  *   The warning message
  * @param details
  *   Additional details from Cedar
  */
final case class PolicyValidationWarning(
    policyId: Option[String],
    message: String,
    details: Option[String] = None
)

/** Result of policy validation against a schema.
  *
  * @param errors
  *   List of validation errors (these would cause authorization failures)
  * @param warnings
  *   List of validation warnings (informational, non-blocking)
  */
final case class PolicyValidationResult(
    errors: List[PolicyValidationError],
    warnings: List[PolicyValidationWarning]
) {

  /** True if validation passed with no errors */
  def isValid: Boolean = errors.isEmpty

  /** True if there are any errors */
  def hasErrors: Boolean = errors.nonEmpty

  /** True if there are any warnings */
  def hasWarnings: Boolean = warnings.nonEmpty

  /** Format a human-readable summary */
  def summary: String = {
    val errorSummary =
      if (errors.isEmpty) "No errors"
      else
        s"${errors.size} error(s):\n${errors.map(e => s"  - ${e.policyId.getOrElse("unknown")}: ${e.message}").mkString("\n")}"
    val warnSummary =
      if (warnings.isEmpty) "No warnings"
      else
        s"${warnings.size} warning(s):\n${warnings.map(w => s"  - ${w.policyId.getOrElse("unknown")}: ${w.message}").mkString("\n")}"
    s"$errorSummary\n$warnSummary"
  }
}

object PolicyValidationResult {
  val empty: PolicyValidationResult = PolicyValidationResult(Nil, Nil)
}

/** Policy validator that validates Cedar policies against a schema.
  *
  * This uses cedar-java's validation API to check that:
  *   - Policies reference entity types defined in the schema
  *   - Policies use correct attribute names and types
  *   - Action names match those defined in the schema
  *   - Expressions are type-correct according to the schema
  *
  * ==Usage==
  * {{{
  * // First, validate the schema
  * val schema = SchemaValidator.validate(schemaText).getOrElse(throw ...)
  *
  * // Then validate policies against it
  * val policies = """
  *   permit(
  *     principal == User::"alice",
  *     action == Action::"read",
  *     resource == Document::"doc1"
  *   );
  * """
  *
  * PolicyValidator.validate(policies, schema) match {
  *   case Right(result) if result.isValid =>
  *     println("Policies are valid!")
  *   case Right(result) =>
  *     println(s"Validation issues: ${result.summary}")
  *   case Left(error) =>
  *     println(s"Failed to validate: $error")
  * }
  * }}}
  */
object PolicyValidator {

  private val engine = new BasicAuthorizationEngine()

  /** Validate Cedar policies against a schema.
    *
    * @param policiesText
    *   The Cedar policies in human-readable format
    * @param schema
    *   The validated schema to check against
    * @return
    *   Either an error message or the validation result
    */
  def validate(
      policiesText: String,
      schema: ValidatedSchema
  ): Either[String, PolicyValidationResult] = {
    try {
      // Parse policies
      val policySet = PolicySet.parsePolicies(policiesText)

      // Create validation request
      val request = new ValidationRequest(schema.javaSchema, policySet)

      // Run validation
      val response = engine.validate(request)

      // Convert response to our types
      Right(fromJavaResponse(response))
    } catch {
      case e: AuthException =>
        Left(s"Validation failed: ${e.getMessage}")
      case NonFatal(e) =>
        Left(s"Unexpected error during validation: ${e.getMessage}")
    }
  }

  /** Validate Cedar policies against a schema, returning true/false.
    *
    * @param policiesText
    *   The Cedar policies in human-readable format
    * @param schema
    *   The validated schema to check against
    * @return
    *   true if policies are valid, false otherwise
    */
  def isValid(policiesText: String, schema: ValidatedSchema): Boolean =
    validate(policiesText, schema).exists(_.isValid)

  /** Validate policies from a PolicySet object against a schema.
    *
    * This is useful when you already have a parsed PolicySet.
    *
    * @param policies
    *   The pre-parsed PolicySet
    * @param schema
    *   The validated schema to check against
    * @return
    *   Either an error message or the validation result
    */
  def validatePolicySet(
      policies: PolicySet,
      schema: ValidatedSchema
  ): Either[String, PolicyValidationResult] = {
    try {
      val request = new ValidationRequest(schema.javaSchema, policies)
      val response = engine.validate(request)
      Right(fromJavaResponse(response))
    } catch {
      case e: AuthException =>
        Left(s"Validation failed: ${e.getMessage}")
      case NonFatal(e) =>
        Left(s"Unexpected error during validation: ${e.getMessage}")
    }
  }

  private def fromJavaResponse(response: ValidationResponse): PolicyValidationResult = {
    response.`type` match {
      case ValidationResponse.SuccessOrFailure.Success =>
        val successResponse = response.success.toScala.getOrElse {
          throw new IllegalStateException("Success response missing success field")
        }

        // Extract validation errors
        val errors = successResponse.validationErrors.asScala.toList.map { ve =>
          PolicyValidationError(
            policyId = Option(ve.getPolicyId),
            message = ve.getError.message,
            details = ve.getError.help.toScala
          )
        }

        // Extract validation warnings
        val warnings = successResponse.validationWarnings.asScala.toList.map { vw =>
          PolicyValidationWarning(
            policyId = Option(vw.getPolicyId),
            message = vw.getError.message,
            details = vw.getError.help.toScala
          )
        }

        PolicyValidationResult(errors, warnings)

      case ValidationResponse.SuccessOrFailure.Failure =>
        // On failure, extract errors from the failure response
        val errors = response.errors.toScala
          .map(_.asScala.toList)
          .getOrElse(Nil)
          .map { de =>
            PolicyValidationError(
              policyId = None,
              message = de.message,
              details = de.help.toScala
            )
          }

        // Also include any warnings
        val warnings = response.warnings.asScala.toList.map { de =>
          PolicyValidationWarning(
            policyId = None,
            message = de.message,
            details = de.help.toScala
          )
        }

        PolicyValidationResult(errors, warnings)
    }
  }
}
