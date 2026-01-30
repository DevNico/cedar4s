package cedar4s.auth

import cedar4s.schema.CedarEntityUid

/** Type-safe, composable authorization request.
  *
  * AuthCheck represents a complete Cedar authorization request with:
  *   - Principal (who is asking)
  *   - Action (what they want to do)
  *   - Resource (on what)
  *   - Context (additional attributes)
  *   - Condition (runtime skip condition)
  *
  * This mirrors Cedar's authorization model:
  * {{{
  * authorize(principal, action, resource, context) → decision
  * }}}
  *
  * ==Type Parameters==
  *
  *   - `P`: Principal type (Nothing if not specified, uses CedarSession default)
  *   - `A`: Action type (for compile-time action/resource compatibility)
  *   - `R`: Resource type (for compile-time action/resource compatibility)
  *
  * ==Basic Usage==
  *
  * {{{
  * import myapp.cedar._
  * import myapp.cedar.ActionDsl._
  *
  * // Simple check - principal from CedarSession
  * Document.View("folder-1", "doc-1").require
  *
  * // With context
  * Document.View("folder-1", "doc-1")
  *   .withContext(ViewContext().withRequestTime(now))
  *   .require
  *
  * // With explicit principal (compile-time checked!)
  * Document.View("folder-1", "doc-1")
  *   .asPrincipal(User("alice"))
  *   .require
  * }}}
  *
  * ==Composition==
  *
  * {{{
  * // AND - all must pass
  * val both = Document.View("f", "d") & Folder.View("f")
  *
  * // OR - at least one must pass
  * val either = Document.Edit("f", "d") | Admin.Override("f")
  * }}}
  *
  * @tparam P
  *   Principal type (Nothing if not specified)
  * @tparam A
  *   Action type
  * @tparam R
  *   Resource type
  * @see
  *   [[CedarSession]] for executing requests
  * @see
  *   [[Principal.CanPerform]] for compile-time principal validation
  */
sealed trait AuthCheck[+P <: Principal, +A <: CedarAction, +R <: CedarResource] {

  /** Combine with another request (AND semantics). Both must pass for the combined request to succeed.
    */
  def &[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
      other: AuthCheck[P2, A2, R2]
  ): AuthCheck[Principal, CedarAction, CedarResource] =
    AuthCheck.combineAnd(this, other)

  /** Combine with another request (OR semantics). At least one must pass for the combined request to succeed.
    */
  def |[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
      other: AuthCheck[P2, A2, R2]
  ): AuthCheck[Principal, CedarAction, CedarResource] =
    AuthCheck.combineOr(this, other)

  /** Execute this authorization request using the provided runner.
    *
    * @param runner
    *   The effect-specific runner
    * @return
    *   Effect containing either an error or unit on success
    */
  def run[F[_]](implicit session: CedarSession[F]): F[Either[CedarAuthError, Unit]] =
    session.run(this)

  /** Execute this authorization request, requiring success.
    *
    * Raises [[CedarAuthError]] in the effect on failure.
    *
    * @param runner
    *   The effect-specific runner
    * @return
    *   Effect containing unit on success
    */
  def require[F[_]](implicit session: CedarSession[F]): F[Unit] =
    session.require(this)

  /** Execute this authorization request, returning a boolean.
    *
    * Useful for filtering or conditional logic without failing.
    *
    * @param runner
    *   The effect-specific runner
    * @return
    *   Effect containing true if authorized, false otherwise
    */
  def isAllowed[F[_]](implicit session: CedarSession[F]): F[Boolean] =
    session.isAllowed(this)
}

object AuthCheck {

  /** Single authorization request.
    *
    * @param principal
    *   Optional explicit principal (None = use CedarSession default)
    * @param action
    *   The action to authorize
    * @param resource
    *   The resource to authorize against
    * @param context
    *   Additional context attributes
    * @param condition
    *   Optional runtime condition (skip check if evaluates to false)
    */
  final case class Single[P <: Principal, A <: CedarAction, R <: CedarResource](
      principal: Option[P],
      action: A,
      resource: R,
      context: ContextSchema,
      condition: Option[() => Boolean]
  ) extends AuthCheck[P, A, R] {

    /** Override principal for this specific request.
      *
      * Requires compile-time evidence that the principal type can perform this action (from [[Principal.CanPerform]]
      * instances).
      *
      * {{{
      * // OK if User can perform View
      * Document.View("f", "d").asPrincipal(User("alice"))
      *
      * // Compile error if ServiceAccount cannot perform Edit
      * // Document.Edit("f", "d").asPrincipal(ServiceAccount("bot"))
      * }}}
      */
    def asPrincipal[P2 <: Principal](p: P2)(implicit ev: Principal.CanPerform[P2, A]): Single[P2, A, R] =
      copy(principal = Some(p))

    /** Add context attributes.
      *
      * Context is merged with existing context (new values override).
      */
    def withContext(ctx: ContextSchema): Single[P, A, R] =
      copy(context = context ++ ctx)

    /** Only run this check when condition is true.
      *
      * If condition evaluates to false at runtime, the check is skipped and succeeds automatically.
      *
      * {{{
      * // Only check production deployments
      * Deployment.Create("env-id")
      *   .when(environment.name == "production")
      *   .require
      * }}}
      */
    def when(cond: => Boolean): Single[P, A, R] =
      copy(condition = Some(() => cond))

    /** Check if the condition (if any) allows this request to proceed. Returns true if there's no condition or if the
      * condition is satisfied.
      */
    def shouldRun: Boolean = condition.forall(_())

    // Re-implement trait methods for proper return types
    override def &[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineAnd(this, other)

    override def |[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineOr(this, other)
  }

  /** Combined request where ALL must pass (AND semantics).
    *
    * Created via the `&` operator:
    * {{{
    * val check = Document.View("f", "d") & Folder.View("f")
    * }}}
    */
  final case class All(
      requests: List[AuthCheck[Principal, CedarAction, CedarResource]]
  ) extends AuthCheck[Principal, CedarAction, CedarResource] {

    override def &[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineAnd(this, other)

    override def |[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineOr(this, other)
  }

  /** Alternative request where AT LEAST ONE must pass (OR semantics).
    *
    * Created via the `|` operator:
    * {{{
    * val check = Document.Edit("f", "d") | Admin.Override("f")
    * }}}
    */
  final case class AnyOf(
      requests: List[AuthCheck[Principal, CedarAction, CedarResource]]
  ) extends AuthCheck[Principal, CedarAction, CedarResource] {

    override def &[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineAnd(this, other)

    override def |[P2 <: Principal, A2 <: CedarAction, R2 <: CedarResource](
        other: AuthCheck[P2, A2, R2]
    ): AuthCheck[Principal, CedarAction, CedarResource] =
      AuthCheck.combineOr(this, other)
  }

  // ===========================================================================
  // Combination Logic
  // ===========================================================================

  /** Combine two requests with AND semantics (both must pass).
    *
    * Note: This method uses `asInstanceOf` to widen the type parameters to the base types. This is safe because:
    *
    *   1. AuthCheck is a sealed trait with only covariant type parameters (+P, +A, +R)
    *   2. All subtypes (Single, All, AnyOf) are immutable and have no contravariant positions
    *   3. The result type is always widened to the base types (Principal, CedarAction, CedarResource)
    *   4. The cast is only used to enable variance - we never narrow the types afterward
    *
    * Without this cast, Scala's type system cannot prove that combining two AuthCheck instances with different specific
    * types (e.g., AuthCheck[User, ViewAction, Document] and AuthCheck[ServiceAccount, EditAction, Folder]) is safe,
    * even though the covariant type parameters make it semantically safe to widen to the common base types.
    */
  private[auth] def combineAnd(
      left: AuthCheck[_, _, _],
      right: AuthCheck[_, _, _]
  ): AuthCheck[Principal, CedarAction, CedarResource] = {
    // Safe cast due to covariance and sealed trait structure (see scaladoc above)
    val l = left.asInstanceOf[AuthCheck[Principal, CedarAction, CedarResource]]
    val r = right.asInstanceOf[AuthCheck[Principal, CedarAction, CedarResource]]

    (l, r) match {
      case (All(a), All(b)) => All(a ++ b)
      case (All(a), b)      => All(a :+ b)
      case (a, All(b))      => All(a +: b)
      case (a, b)           => All(List(a, b))
    }
  }

  /** Combine two requests with OR semantics (at least one must pass).
    *
    * Note: This method uses `asInstanceOf` for the same type variance reasons as `combineAnd`. See the scaladoc on
    * `combineAnd` for a detailed explanation of why this cast is safe.
    */
  private[auth] def combineOr(
      left: AuthCheck[_, _, _],
      right: AuthCheck[_, _, _]
  ): AuthCheck[Principal, CedarAction, CedarResource] = {
    // Safe cast due to covariance and sealed trait structure (see combineAnd scaladoc)
    val l = left.asInstanceOf[AuthCheck[Principal, CedarAction, CedarResource]]
    val r = right.asInstanceOf[AuthCheck[Principal, CedarAction, CedarResource]]

    (l, r) match {
      case (AnyOf(a), AnyOf(b)) => AnyOf(a ++ b)
      case (AnyOf(a), b)        => AnyOf(a :+ b)
      case (a, AnyOf(b))        => AnyOf(a +: b)
      case (a, b)               => AnyOf(List(a, b))
    }
  }

  // ===========================================================================
  // Convenience Constructors
  // ===========================================================================

  /** Create a single request from action and resource.
    *
    * This is the primary entry point, typically called from generated DSL.
    */
  def single[A <: CedarAction, R <: CedarResource](action: A, resource: R): Single[Nothing, A, R] =
    Single(
      principal = None,
      action = action,
      resource = resource,
      context = ContextSchema.empty,
      condition = None
    )

  /** Combine multiple requests with AND semantics */
  def all(requests: AuthCheck[_, _, _]*): AuthCheck[Principal, CedarAction, CedarResource] =
    // Safe cast due to covariance and sealed trait structure (see combineAnd scaladoc)
    All(requests.toList.asInstanceOf[List[AuthCheck[Principal, CedarAction, CedarResource]]])

  /** Combine multiple requests with OR semantics */
  def anyOf(requests: AuthCheck[_, _, _]*): AuthCheck[Principal, CedarAction, CedarResource] =
    // Safe cast due to covariance and sealed trait structure (see combineAnd scaladoc)
    AnyOf(requests.toList.asInstanceOf[List[AuthCheck[Principal, CedarAction, CedarResource]]])
}
