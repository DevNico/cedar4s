package cedar4s.observability.otel

import cedar4s.client.AuthorizationResponse

/** Strategy for naming OpenTelemetry spans created for authorization checks.
  *
  * Span names should be:
  *   - **Low cardinality**: Avoid high-cardinality values like entity IDs
  *   - **Meaningful**: Describe the operation being performed
  *   - **Consistent**: Use a predictable naming convention
  *
  * ==Default Strategy==
  *
  * The default strategy uses a simple "cedar.authorization" name for all spans. This is safe and low-cardinality.
  *
  * ==Custom Strategies==
  *
  * You can create custom strategies to group spans by action type, resource type, or other low-cardinality attributes:
  *
  * {{{
  * // Group by action
  * val byAction = SpanNamingStrategy.byAction
  * // Produces: "cedar.authorization.View", "cedar.authorization.Edit", etc.
  *
  * // Group by action and resource type
  * val byActionAndResource = SpanNamingStrategy.byActionAndResourceType
  * // Produces: "cedar.authorization.Document.View", "cedar.authorization.Folder.Create", etc.
  *
  * // Custom strategy
  * val custom = SpanNamingStrategy { response =>
  *   if (response.action.name.contains("Admin")) {
  *     "cedar.authorization.admin"
  *   } else {
  *     "cedar.authorization.user"
  *   }
  * }
  * }}}
  *
  * @see
  *   [[SemanticConventions]] for attribute naming conventions
  */
trait SpanNamingStrategy {

  /** Generate a span name for an authorization response.
    *
    * @param response
    *   The authorization response
    * @return
    *   The span name to use
    */
  def spanName(response: AuthorizationResponse): String
}

object SpanNamingStrategy {

  /** Default strategy: all spans named "cedar.authorization".
    *
    * This is the safest option with minimal cardinality.
    */
  def default: SpanNamingStrategy = new SpanNamingStrategy {
    def spanName(response: AuthorizationResponse): String = "cedar.authorization"
  }

  /** Name spans by action: "cedar.authorization.{ActionName}".
    *
    * Examples:
    *   - "cedar.authorization.View"
    *   - "cedar.authorization.Edit"
    *   - "cedar.authorization.Delete"
    *
    * Use this when you want to group traces by action type. Ensure your action names have reasonable cardinality.
    */
  def byAction: SpanNamingStrategy = new SpanNamingStrategy {
    def spanName(response: AuthorizationResponse): String = {
      val actionName = extractActionName(response.action.cedarAction)
      s"cedar.authorization.$actionName"
    }
  }

  /** Name spans by resource type: "cedar.authorization.{ResourceType}".
    *
    * Examples:
    *   - "cedar.authorization.Document"
    *   - "cedar.authorization.Folder"
    *   - "cedar.authorization.User"
    *
    * Use this when you want to group traces by resource type.
    */
  def byResourceType: SpanNamingStrategy = new SpanNamingStrategy {
    def spanName(response: AuthorizationResponse): String = {
      s"cedar.authorization.${response.resource.entityType}"
    }
  }

  /** Name spans by action and resource type: "cedar.authorization.{ResourceType}.{ActionName}".
    *
    * Examples:
    *   - "cedar.authorization.Document.View"
    *   - "cedar.authorization.Folder.Create"
    *   - "cedar.authorization.User.Update"
    *
    * This provides good grouping while keeping cardinality reasonable. Only use if both resource types and action names
    * have low cardinality.
    */
  def byActionAndResourceType: SpanNamingStrategy = new SpanNamingStrategy {
    def spanName(response: AuthorizationResponse): String = {
      val actionName = extractActionName(response.action.cedarAction)
      s"cedar.authorization.${response.resource.entityType}.$actionName"
    }
  }

  /** Create a custom naming strategy from a function.
    *
    * {{{
    * val custom = SpanNamingStrategy { response =>
    *   val prefix = if (response.principal.entityType == "ServiceAccount") {
    *     "cedar.service"
    *   } else {
    *     "cedar.user"
    *   }
    *   s"\$prefix.${extractActionName(response.action.cedarAction)}"
    * }
    * }}}
    *
    * @param f
    *   Function that generates span name from response
    * @return
    *   A new naming strategy
    */
  def apply(f: AuthorizationResponse => String): SpanNamingStrategy =
    new SpanNamingStrategy {
      def spanName(response: AuthorizationResponse): String = f(response)
    }

  // ===========================================================================
  // Helper Methods
  // ===========================================================================

  /** Extract action name from Cedar action string.
    *
    * Handles formats like:
    *   - Document::Action::"View" -> "View"
    *   - Action::"Edit" -> "Edit"
    *   - "Delete" -> "Delete"
    */
  private def extractActionName(cedarAction: String): String = {
    val parts = cedarAction.split("::")
    val lastPart = parts.lastOption.getOrElse(cedarAction)
    // Remove surrounding quotes if present
    lastPart.stripPrefix("\"").stripSuffix("\"")
  }
}
