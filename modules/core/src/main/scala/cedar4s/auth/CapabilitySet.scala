package cedar4s.auth

/** Typed set of allowed actions for a resource.
  *
  * CapabilitySet represents the subset of actions that a principal is authorized to perform on a specific resource.
  * This is returned by `getAllowedActions` and `getAllowedActionsFor` methods on CedarSession.
  *
  * ==Usage==
  *
  * CapabilitySet is typically used to determine which UI elements or API operations should be available to the current
  * user for a specific resource:
  *
  * {{{
  * // Get capabilities for the session principal
  * val capabilities: F[CapabilitySet[DocumentAction]] =
  *   session.getAllowedActions(
  *     resource = Document.resource("folder-1", "doc-1"),
  *     actionType = "MyApp::Action",
  *     allActions = Set("view", "edit", "delete")
  *   )
  *
  * // Check if a specific action is allowed
  * val canEdit: Boolean = capabilities.allows(DocumentAction.Edit)
  *
  * // Get action names for API responses
  * val actionNames: Set[String] = capabilities.names
  * // => Set("view", "edit")
  * }}}
  *
  * ==Relationship to getAllowedActions==
  *
  * `CedarSession` provides two methods that return capabilities:
  *
  *   - `getAllowedActions(resource, actionType, allActions)` - Uses the session principal
  *   - `getAllowedActionsFor(principal, resource, actionType, allActions)` - Uses an explicit principal
  *
  * Both methods return `F[Set[String]]` (raw action names). The generated code typically wraps this in a type-safe
  * CapabilitySet by mapping action names back to typed action instances.
  *
  * ==Example: Frontend Integration==
  *
  * {{{
  * // In a Play Framework controller
  * def getDocumentWithCapabilities(docId: String) = Action.async { implicit request =>
  *   given session: CedarSession[Future] = cedarRuntime.session(currentUser)
  *
  *   for {
  *     doc <- documentDao.find(docId)
  *     capabilities <- session.getAllowedActions(
  *       Document.resource(doc.folderId, doc.id),
  *       "MyApp::Action",
  *       Set("view", "edit", "delete")
  *     )
  *   } yield Ok(Json.obj(
  *     "document" -> Json.toJson(doc),
  *     "capabilities" -> Json.toJson(capabilities)
  *   ))
  * }
  * }}}
  *
  * ==Example: Batch Capabilities==
  *
  * {{{
  * // Get capabilities for multiple documents
  * val docs: Seq[Document] = ...
  * val capabilities: F[Map[String, CapabilitySet[DocumentAction]]] =
  *   docs.traverse { doc =>
  *     session.getAllowedActions(
  *       Document.resource(doc.folderId, doc.id),
  *       "MyApp::Action",
  *       Set("view", "edit", "delete")
  *     ).map(actions => doc.id -> CapabilitySet(actions.flatMap(DocumentAction.fromName)))
  *   }.map(_.toMap)
  * }}}
  *
  * @tparam A
  *   The action type (typically a generated sealed trait)
  * @param allowed
  *   The set of actions that are allowed
  */
final case class CapabilitySet[A <: CedarAction](allowed: Set[A]) {

  /** Check if a specific action is allowed.
    *
    * @param action
    *   The action to check
    * @return
    *   true if the action is in the allowed set
    */
  def allows(action: A): Boolean = allowed.contains(action)

  /** Get the action names as strings.
    *
    * Useful for serializing capabilities to JSON or other formats.
    *
    * @return
    *   Set of action names (e.g., Set("view", "edit"))
    */
  def names: Set[String] = allowed.map(_.name)
}
