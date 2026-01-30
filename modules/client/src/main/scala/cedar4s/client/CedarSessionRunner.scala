package cedar4s.client

import cedar4s.auth._
import cedar4s.entities.{CedarEntities, CedarPrincipal, EntityStore, ResourceRef}
import cedar4s.schema.CedarEntityUid
import cedar4s.capability.Sync
import cedar4s.capability.Functor.FunctorOps
import cedar4s.capability.Monad.MonadOps
import scala.util.control.NonFatal

/** Default CedarSession implementation backed by CedarEngine + EntityStore.
  *
  * @param F
  *   Sync capability required for effect type F
  */
final class CedarSessionRunner[F[_]](
    sessionPrincipal: Principal,
    sessionContext: ContextSchema,
    engine: CedarEngine[F],
    store: EntityStore[F],
    resolvePrincipal: Principal => F[CedarPrincipal],
    interceptor: AuthInterceptor[F]
)(implicit F: Sync[F])
    extends CedarSession[F] {

  override def principal: Principal = sessionPrincipal
  override def context: ContextSchema = sessionContext
  override def entityStore: EntityStore[F] = store

  override def withContext(ctx: ContextSchema): CedarSession[F] =
    new CedarSessionRunner[F](
      sessionPrincipal,
      sessionContext ++ ctx,
      engine,
      store,
      resolvePrincipal,
      interceptor
    )

  override def run(request: AuthCheck[_, _, _]): F[Either[CedarAuthError, Unit]] =
    request match {
      case single: AuthCheck.Single[_, _, _] =>
        runSingle(single)
      case AuthCheck.All(requests) =>
        runAll(requests)
      case AuthCheck.AnyOf(requests) =>
        runAnyOf(requests)
    }

  override def require(request: AuthCheck[_, _, _]): F[Unit] =
    F.flatMap(run(request)) {
      case Right(()) => F.pure(())
      case Left(err) => F.raiseError(err)
    }

  override def isAllowed(request: AuthCheck[_, _, _]): F[Boolean] =
    F.map(run(request))(_.isRight)

  override def batchRun(requests: Seq[AuthCheck[_, _, _]]): F[Seq[Either[CedarAuthError, Unit]]] =
    sequenceF(requests.map(run))

  override def batchIsAllowed(requests: Seq[AuthCheck[_, _, _]]): F[Seq[Boolean]] =
    sequenceF(requests.map(isAllowed))

  override def filterAllowed[A](items: Seq[A])(toRequest: A => AuthCheck[_, _, _]): F[Seq[A]] =
    F.map(batchIsAllowed(items.map(toRequest))) { allowed =>
      items.zip(allowed).collect { case (item, true) => item }
    }

  override def getAllowedActions(
      resource: CedarResource,
      actionType: String,
      allActions: Set[String]
  ): F[Set[String]] =
    getAllowedActionsFor(sessionPrincipal, resource, actionType, allActions)

  override def getAllowedActionsFor(
      principal: Principal,
      resource: CedarResource,
      actionType: String,
      allActions: Set[String]
  ): F[Set[String]] = {
    for {
      cedarPrincipal <- resolvePrincipal(principal)
      resourceRef = toResourceRef(resource)
      entities <- store.loadForRequest(cedarPrincipal, resourceRef)
      resourceUid = resource.entityId match {
        case Some(id) => CedarEntityUid(resource.entityType, id)
        case None     => CedarEntityUid(resource.entityType, "__collection__")
      }
      allowed <- engine.getAllowedActions(
        principal = cedarPrincipal.uid,
        resource = resourceUid,
        actionType = actionType,
        actions = allActions,
        entities = entities
      )
    } yield allowed
  }

  // =========================================================================
  // Private Implementation
  // =========================================================================

  private def runSingle(
      request: AuthCheck.Single[_ <: Principal, _ <: CedarAction, _ <: CedarResource]
  ): F[Either[CedarAuthError, Unit]] = {
    if (!request.condition.forall(_()))
      return F.pure(Right(()))

    val principal = request.principal.getOrElse(sessionPrincipal)
    val action = request.action
    val resource = request.resource

    val startTime = System.nanoTime()
    val timestamp = java.time.Instant.now()

    val result = for {
      cedarPrincipal <- resolvePrincipal(principal)
      resourceRef = toResourceRef(resource)
      entities <- store.loadForRequest(cedarPrincipal, resourceRef)
      cedarRequest = buildCedarRequest(cedarPrincipal.uid, action, resource, request.context)
      decision <- engine.authorize(cedarRequest, entities)
    } yield {
      val endTime = System.nanoTime()
      val durationNanos = endTime - startTime

      val authResponse = AuthorizationResponse(
        timestamp = timestamp,
        durationNanos = durationNanos,
        principal = principal,
        cedarPrincipal = cedarPrincipal,
        action = action,
        resource = resource,
        context = cedarRequest.context,
        entities = entities,
        decision = decision,
        errors = if (decision.allow) Nil else List(toUnauthorizedError(action, resource, decision))
      )

      // Fire-and-forget interceptor call - errors are suppressed to avoid failing auth checks
      F.handleErrorWith(interceptor.onResponse(authResponse)) { _ =>
        F.pure(())
      }

      if (decision.allow) Right(())
      else Left(toUnauthorizedError(action, resource, decision): CedarAuthError)
    }

    F.handleErrorWith(result) { ex =>
      val endTime = System.nanoTime()
      val durationNanos = endTime - startTime

      val error = ex match {
        case e: CedarAuthError => e
        case e                 => CedarAuthError.AuthorizationFailed(e.getMessage, Some(e))
      }

      try {
        val authResponse = AuthorizationResponse(
          timestamp = timestamp,
          durationNanos = durationNanos,
          principal = principal,
          cedarPrincipal = CedarPrincipal(principal.toCedarEntity, CedarEntities.empty),
          action = action,
          resource = resource,
          context = CedarContext.empty,
          entities = CedarEntities.empty,
          decision = CedarDecision(allow = false, diagnostics = Some(CedarDiagnostics(reason = Some(error.message)))),
          errors = List(error)
        )
        F.handleErrorWith(interceptor.onResponse(authResponse))(_ => F.pure(()))
      } catch {
        case NonFatal(_) => ()
      }

      F.pure(Left(error))
    }
  }

  private def runAll(requests: Seq[AuthCheck[_, _, _]]): F[Either[CedarAuthError, Unit]] = {
    def loop(remaining: List[AuthCheck[_, _, _]]): F[Either[CedarAuthError, Unit]] =
      remaining match {
        case Nil          => F.pure(Right(()))
        case head :: tail =>
          F.flatMap(run(head)) {
            case Right(()) => loop(tail)
            case left      => F.pure(left)
          }
      }
    loop(requests.toList)
  }

  private def runAnyOf(requests: Seq[AuthCheck[_, _, _]]): F[Either[CedarAuthError, Unit]] = {
    def loop(
        remaining: List[AuthCheck[_, _, _]],
        errors: List[String]
    ): F[Either[CedarAuthError, Unit]] =
      remaining match {
        case Nil =>
          F.pure(Left(CedarAuthError.Unauthorized(s"None granted: ${errors.mkString("; ")}")))
        case head :: tail =>
          F.flatMap(run(head)) {
            case Right(()) => F.pure(Right(()))
            case Left(err) => loop(tail, errors :+ err.message)
          }
      }
    loop(requests.toList, Nil)
  }

  private def toResourceRef(resource: CedarResource): ResourceRef =
    ResourceRef(
      entityType = resource.entityType,
      entityId = resource.entityId,
      parents = resource.parents
    )

  private def buildCedarRequest(
      principalUid: CedarEntityUid,
      action: CedarAction,
      resource: CedarResource,
      requestContext: ContextSchema
  ): CedarRequest = {
    val resourceUid = resource.entityId match {
      case Some(id) => CedarEntityUid(resource.entityType, id)
      case None     => CedarEntityUid(resource.entityType, "__collection__")
    }

    val actionUid = CedarEntityUid.parse(action.cedarAction).getOrElse {
      throw new IllegalArgumentException(s"Invalid cedarAction format: ${action.cedarAction}")
    }

    CedarRequest(
      principal = principalUid,
      action = actionUid,
      resource = resourceUid,
      context = CedarContext(sessionContext.toMap) ++ CedarContext(requestContext.toMap)
    )
  }

  private def toUnauthorizedError(
      action: CedarAction,
      resource: CedarResource,
      decision: CedarDecision
  ): CedarAuthError.Unauthorized = {
    val resourceDesc = resource.entityId match {
      case Some(id) => s"${resource.entityType}::\"$id\""
      case None     => s"${resource.entityType} collection"
    }
    CedarAuthError.Unauthorized(
      message = s"Permission denied: ${action.cedarAction} on $resourceDesc",
      denyReason = decision.denyReason
    )
  }

  private def sequenceF[A](fas: Seq[F[A]]): F[Seq[A]] = {
    fas.foldRight(F.pure(Seq.empty[A])) { (fa, acc) =>
      F.flatMap(fa)(a => F.map(acc)(seq => a +: seq))
    }
  }
}
