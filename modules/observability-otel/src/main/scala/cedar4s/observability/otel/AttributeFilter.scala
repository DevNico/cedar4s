package cedar4s.observability.otel

import cedar4s.client.AuthorizationResponse

/** Filter for controlling which attributes are added to OpenTelemetry spans.
  *
  * Use this to prevent sensitive information from being added to traces, or to reduce cardinality by excluding
  * high-cardinality attributes.
  *
  * ==Default Filter==
  *
  * The default filter includes all standard Cedar attributes. This is appropriate when your tracing backend is secure
  * and you want full observability.
  *
  * ==Security Considerations==
  *
  * Consider filtering:
  *   - **Entity IDs**: If they contain sensitive information (e.g., email addresses, SSNs)
  *   - **Context attributes**: May contain PII or business-sensitive data
  *   - **Deny reasons**: May expose policy implementation details
  *
  * ==Custom Filters==
  *
  * {{{
  * // Exclude entity IDs for privacy
  * val privacyFilter = AttributeFilter.excludePrincipalIds
  *
  * // Only include decision and action
  * val minimalFilter = AttributeFilter.minimal
  *
  * // Custom filter
  * val custom = AttributeFilter { case attr =>
  *   attr match {
  *     case SemanticConventions.CEDAR_PRINCIPAL_ID => false // exclude
  *     case SemanticConventions.CEDAR_RESOURCE_ID => false  // exclude
  *     case _ => true // include everything else
  *   }
  * }
  * }}}
  */
trait AttributeFilter {

  /** Determine whether an attribute should be included in the span.
    *
    * @param attributeName
    *   The attribute name (from SemanticConventions)
    * @param response
    *   The authorization response (for context-aware filtering)
    * @return
    *   true if the attribute should be included, false to exclude
    */
  def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean
}

object AttributeFilter {

  /** Default filter: include all attributes.
    *
    * This provides maximum observability but may include sensitive data.
    */
  def includeAll: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean = true
  }

  /** Minimal filter: only include decision, action name, and resource type.
    *
    * This excludes all entity IDs and other potentially sensitive information. Good for high-security environments.
    *
    * Included attributes:
    *   - cedar.decision
    *   - cedar.action.name
    *   - cedar.resource.type
    *   - cedar.duration_ms
    */
  def minimal: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      attributeName match {
        case SemanticConventions.CEDAR_DECISION      => true
        case SemanticConventions.CEDAR_ACTION_NAME   => true
        case SemanticConventions.CEDAR_RESOURCE_TYPE => true
        case SemanticConventions.CEDAR_DURATION_MS   => true
        case _                                       => false
      }
  }

  /** Exclude principal IDs from spans.
    *
    * Use this when principal IDs contain PII or should not be logged. Other attributes are included.
    */
  def excludePrincipalIds: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      attributeName match {
        case SemanticConventions.CEDAR_PRINCIPAL_ID  => false
        case SemanticConventions.CEDAR_PRINCIPAL_UID => false
        case _                                       => true
      }
  }

  /** Exclude resource IDs from spans.
    *
    * Use this when resource IDs contain sensitive information. Other attributes are included.
    */
  def excludeResourceIds: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      attributeName match {
        case SemanticConventions.CEDAR_RESOURCE_ID  => false
        case SemanticConventions.CEDAR_RESOURCE_UID => false
        case _                                      => true
      }
  }

  /** Exclude all entity IDs (both principal and resource).
    *
    * Keeps type information but removes specific identifiers.
    */
  def excludeEntityIds: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      attributeName match {
        case SemanticConventions.CEDAR_PRINCIPAL_ID  => false
        case SemanticConventions.CEDAR_PRINCIPAL_UID => false
        case SemanticConventions.CEDAR_RESOURCE_ID   => false
        case SemanticConventions.CEDAR_RESOURCE_UID  => false
        case _                                       => true
      }
  }

  /** Exclude deny reasons from spans.
    *
    * Use this when deny reasons might expose policy implementation details or sensitive business logic.
    */
  def excludeDenyReasons: AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      attributeName match {
        case SemanticConventions.CEDAR_DENY_REASON       => false
        case SemanticConventions.CEDAR_DENY_REASON_COUNT => false
        case _                                           => true
      }
  }

  /** Create a custom filter from a function.
    *
    * {{{
    * val customFilter = AttributeFilter { (attr, response) =>
    *   // Only include IDs for non-production principals
    *   attr match {
    *     case SemanticConventions.CEDAR_PRINCIPAL_ID =>
    *       !response.principal.entityId.contains("prod")
    *     case _ => true
    *   }
    * }
    * }}}
    */
  def apply(f: (String, AuthorizationResponse) => Boolean): AttributeFilter =
    new AttributeFilter {
      def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
        f(attributeName, response)
    }

  /** Combine multiple filters with AND logic.
    *
    * All filters must allow the attribute for it to be included.
    *
    * {{{
    * val combined = AttributeFilter.and(
    *   AttributeFilter.excludePrincipalIds,
    *   AttributeFilter.excludeDenyReasons
    * )
    * }}}
    */
  def and(filters: AttributeFilter*): AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      filters.forall(_.shouldInclude(attributeName, response))
  }

  /** Combine multiple filters with OR logic.
    *
    * At least one filter must allow the attribute for it to be included.
    */
  def or(filters: AttributeFilter*): AttributeFilter = new AttributeFilter {
    def shouldInclude(attributeName: String, response: AuthorizationResponse): Boolean =
      filters.exists(_.shouldInclude(attributeName, response))
  }
}
