package cedar4s.auth

import cedar4s.entities.EntityStore

/** Typeclass for executing Cedar authorization requests in a specific effect type.
  *
  * This is the core abstraction that allows the authorization DSL to work with any effect system (Future, IO, DBIO,
  * ZIO, etc.) without cedar4s needing to depend on those libraries.
  *
  * ==Key Responsibilities==
  *
  *   1. **Default Principal**: Provides the principal for requests without explicit `.asPrincipal()`
  *   2. **Request Execution**: Handles Single, All, and AnyOf request types
  *   3. **Batch Operations**: Efficient multi-resource authorization
  *
  * ==Implementation==
  *
  * Applications implement this typeclass to provide the actual authorization logic:
  *
  * {{{
  * import scala.concurrent.{ExecutionContext, Future}
  *
  * class FutureCedarSession(
  *   cedarEngine: CedarEngine,
  *   currentUser: Principal
  * )(implicit ec: ExecutionContext) extends CedarSession[Future] {
  *
  *   val principal: Principal = currentUser
  *
  *   override def run(request: AuthCheck[_, _, _]): Future[Either[CedarAuthError, Unit]] = {
  *     request match {
  *       case AuthCheck.Single(principal, action, resource, context, condition) =>
  *         if (!condition.forall(_()))
  *           Future.successful(Right(()))  // Skip if condition is false
  *         else {
  *           val p = principal.getOrElse(sessionPrincipal)
  *           // Build CedarRequest, call engine...
  *         }
  *       case AuthCheck.All(requests) =>
  *         // Run all, fail on first failure
  *         ...
  *       case AuthCheck.AnyOf(requests) =>
  *         // Run until first success
  *         ...
  *     }
  *   }
  * }
  * }}}
  *
  * ==Usage==
  *
  * Once an `CedarSession` is in scope, authorization requests can be executed:
  *
  * {{{
  * given CedarSession[Future] = FutureCedarSession(engine, User("alice"))
  *
  * // Principal from runner
  * Document.View("folder-1", "doc-1").require
  *
  * // Explicit principal (compile-time checked)
  * Document.View("folder-1", "doc-1").asPrincipal(ServiceAccount("bot")).require
  * }}}
  *
  * @tparam F
  *   The effect type (e.g., Future, IO, DBIO)
  */
trait CedarSession[F[_]] {

  /** The session principal for requests without explicit `.asPrincipal()`.
    *
    * This is typically the "current user" from request context (session, JWT, etc.).
    */
  def principal: Principal

  /** Session-level context merged into each authorization check's context.
    *
    * This context is automatically merged with any request-specific context when executing authorization checks. It's
    * typically used for session-wide attributes like IP address, user agent, or environment settings.
    *
    * ==Implementation Contract==
    *
    * Implementers should return `ContextSchema.empty` if no session-level context is needed. This method will be called
    * for each authorization check, so avoid expensive computations here.
    *
    * ==Example==
    *
    * {{{
    * class MyCedarSession(
    *   override val principal: Principal,
    *   ipAddress: String,
    *   userAgent: String
    * ) extends CedarSession[Future] {
    *
    *   override def context: ContextSchema = {
    *     ContextSchema.empty
    *       .withAttribute("ipAddress", ipAddress)
    *       .withAttribute("userAgent", userAgent)
    *   }
    *
    *   // ... other methods
    * }
    * }}}
    *
    * @return
    *   The session-level context to merge into all authorization checks
    */
  def context: ContextSchema

  /** The EntityStore for this session.
    *
    * Used by deferred authorization checks and capability methods to resolve entity hierarchies. By exposing this
    * through the session, we eliminate the need for a separate implicit EntityStore parameter.
    */
  def entityStore: EntityStore[F]

  /** Return a new session with additional context merged in.
    */
  def withContext(ctx: ContextSchema): CedarSession[F]

  /** Execute an authorization request, returning Either.
    *
    * @param request
    *   The authorization request to execute
    * @return
    *   F containing either an error or unit on success
    */
  def run(request: AuthCheck[_, _, _]): F[Either[CedarAuthError, Unit]]

  /** Execute an authorization request, raising errors in the effect.
    *
    * This is useful for imperative-style code where you want to short-circuit on authorization failure.
    *
    * @param request
    *   The authorization request to execute
    * @return
    *   F containing unit on success, or failing with CedarAuthError
    */
  def require(request: AuthCheck[_, _, _]): F[Unit]

  /** Execute an authorization request, returning a boolean.
    *
    * Useful for filtering operations where you want to check permission without failing the entire operation.
    *
    * @param request
    *   The authorization request to execute
    * @return
    *   F containing true if authorized, false otherwise
    */
  def isAllowed(request: AuthCheck[_, _, _]): F[Boolean]

  // ===========================================================================
  // Batch Operations (Limitation #8)
  // ===========================================================================

  /** Execute multiple requests, returning results for each.
    *
    * Default implementation runs sequentially; override for parallel execution or batched Cedar calls.
    *
    * {{{
    * val checks = documents.map(d => Document.View.on(d.id))
    * val results: Future[Seq[Either[CedarAuthError, Unit]]] = runner.batchRun(checks)
    * }}}
    */
  def batchRun(requests: Seq[AuthCheck[_, _, _]]): F[Seq[Either[CedarAuthError, Unit]]]

  /** Check multiple requests, returning boolean for each.
    *
    * {{{
    * val checks = documents.map(d => Document.View.on(d.id))
    * val allowed: Future[Seq[Boolean]] = runner.batchIsAllowed(checks)
    * }}}
    */
  def batchIsAllowed(requests: Seq[AuthCheck[_, _, _]]): F[Seq[Boolean]]

  /** Filter items to only those the principal is allowed to access.
    *
    * This is a common pattern for listing resources:
    *
    * {{{
    * val allDocs: Seq[Document] = loadAllDocuments()
    * val viewable: Future[Seq[Document]] = runner.filterAllowed(allDocs) { doc =>
    *   Document.View("folder-1", doc.id)
    * }
    * }}}
    *
    * @param items
    *   Items to filter
    * @param toRequest
    *   Function to create an auth request for each item
    * @return
    *   F containing only the items where authorization succeeded
    */
  def filterAllowed[A](items: Seq[A])(toRequest: A => AuthCheck[_, _, _]): F[Seq[A]]

  /** Get all allowed actions for a resource using the session principal.
    *
    * @param resource
    *   The Cedar resource to check permissions on
    * @param actionType
    *   The Cedar action entity type (e.g., "MyApp::Action")
    * @param allActions
    *   All possible action names to check
    * @return
    *   F containing the subset of actions that are allowed
    */
  def getAllowedActions(
      resource: CedarResource,
      actionType: String,
      allActions: Set[String]
  ): F[Set[String]]

  /** Get allowed actions for a specific principal (overriding the session principal).
    *
    * @param principal
    *   The principal to check capabilities for
    * @param resource
    *   The Cedar resource to check permissions on
    * @param actionType
    *   The Cedar action entity type
    * @param allActions
    *   All possible action names to check
    * @return
    *   F containing the subset of actions that are allowed
    */
  def getAllowedActionsFor(
      principal: Principal,
      resource: CedarResource,
      actionType: String,
      allActions: Set[String]
  ): F[Set[String]]
}

object CedarSession {

  /** Summon a CedarSession from implicit scope */
  def apply[F[_]](implicit session: CedarSession[F]): CedarSession[F] = session
}
