package cedar4s.auth

import cedar4s.entities.{EntityStore, ResourceRef}

/** A deferred authorization request that resolves the resource from EntityStore before checking.
  *
  * This represents an authorization request that needs to look up the entity and its parent chain from the data store
  * before it can be executed. It's created via the `.on(id)` syntax on actions.
  *
  * ==Why Deferred?==
  *
  * Cedar authorization requires the full resource hierarchy (parent IDs). Often you only have the leaf entity ID and
  * need to look up parents:
  *
  * {{{
  * // Without deferred - must look up parents manually
  * for {
  *   doc <- documentDao.find(documentId)
  *   _ <- Document.View(doc.folderId, doc.id).require
  * } yield ()
  *
  * // With deferred - automatic parent resolution via EntityStore
  * Document.View.on(documentId).require
  * }}}
  *
  * ==How It Works==
  *
  *   1. You call `Action.on(entityId)` which creates a `DeferredAuthCheck`
  *   2. When you call `.require`, `.run`, or `.isAllowed`:
  *      a. The `EntityStore` fetches the entity using the registered `EntityFetcher`
  *      b. The `EntityFetcher.getParentIds` extracts the parent chain
  *      c. A `ResourceRef` is built with the entity type, ID, and parents
  *      d. The authorization request is executed with the resolved resource
  *
  * ==Usage==
  *
  * {{{
  * // Register EntityFetcher for each entity type
  * val registry = EntityRegistry.builder()
  *   .register(new DocumentFetcher(dataStore))
  *   .register(new FolderFetcher(dataStore))
  *   .build()
  *
  * given EntityStore = EntityStore.builder().withRegistry(registry).build()
  *
  * // Use deferred syntax - EntityStore resolves the parent chain
  * Document.View.on("doc-123").require
  * }}}
  *
  * @tparam F
  *   The effect type
  * @tparam Id
  *   The entity ID type
  * @tparam A
  *   The action type
  * @tparam R
  *   The resource type
  */
trait DeferredAuthCheck[F[_], Id, A <: CedarAction, R <: CedarResource] {

  /** The entity ID to resolve context for */
  def id: Id

  /** The action to authorize */
  def action: A

  /** Resolve context and create the authorization request.
    *
    * Use this when you need to compose the request with other requests before executing.
    */
  def resolve: F[AuthCheck.Single[Nothing, A, R]]

  /** Resolve context and execute, returning Either.
    */
  def run(implicit runner: CedarSession[F]): F[Either[CedarAuthError, Unit]]

  /** Resolve context and execute, raising errors in the effect.
    *
    * This is the most common way to use deferred requests:
    * {{{
    * Document.View.on(docId).require
    * }}}
    */
  def require(implicit runner: CedarSession[F]): F[Unit]

  /** Resolve context and check if allowed, returning boolean.
    */
  def isAllowed(implicit runner: CedarSession[F]): F[Boolean]
}

/** Factory for creating DeferredAuthCheck instances.
  *
  * This is used by generated code to create deferred requests that resolve their resource context via EntityStore.
  */
object DeferredAuthCheck {

  /** Create a deferred request that resolves via EntityStore.
    *
    * @param entityType
    *   The Cedar entity type (e.g., "DocShare::Document")
    * @param entityId
    *   The entity ID to resolve
    * @param actionValue
    *   The action to authorize
    * @param buildResource
    *   Function to build the typed resource from ResourceRef
    * @param session
    *   The CedarSession providing the EntityStore
    * @param flatMap
    *   FlatMap instance for the effect type
    */
  def apply[F[_], Id, A <: CedarAction, R <: CedarResource](
      entityType: String,
      entityId: Id,
      actionValue: A,
      buildResource: ResourceRef => R
  )(implicit session: CedarSession[F], flatMap: FlatMap[F]): DeferredAuthCheck[F, Id, A, R] =
    new DeferredAuthCheckImpl(entityType, entityId, actionValue, buildResource, session.entityStore, flatMap)

  private class DeferredAuthCheckImpl[F[_], Id, A <: CedarAction, R <: CedarResource](
      entityType: String,
      entityId: Id,
      actionValue: A,
      buildResource: ResourceRef => R,
      store: EntityStore[F],
      flatMapInstance: FlatMap[F]
  ) extends DeferredAuthCheck[F, Id, A, R] {

    override def id: Id = entityId
    override def action: A = actionValue

    override def resolve: F[AuthCheck.Single[Nothing, A, R]] = {
      val entityIdStr = entityId.toString

      // resolveResourceRef returns F[ResourceRef], so just flatMap it
      flatMapInstance.flatMap(resolveResourceRef(entityIdStr)) { resourceRef =>
        val resource = buildResource(resourceRef)
        flatMapInstance.pure(AuthCheck.single(actionValue, resource))
      }
    }

    override def run(implicit runner: CedarSession[F]): F[Either[CedarAuthError, Unit]] =
      flatMapInstance.flatMap(resolve)(runner.run)

    override def require(implicit runner: CedarSession[F]): F[Unit] =
      flatMapInstance.flatMap(resolve)(runner.require)

    override def isAllowed(implicit runner: CedarSession[F]): F[Boolean] =
      flatMapInstance.flatMap(resolve)(runner.isAllowed)

    /** Resolve the resource reference from the entity store.
      *
      * This method loads the entity and its parent chain, then constructs a ResourceRef. If the entity is not found, it
      * returns a ResourceRef with no parents, allowing Cedar to make the authorization decision based on the missing
      * entity.
      *
      * @param id
      *   The entity ID to resolve
      * @return
      *   F[ResourceRef] with the entity and its parent chain, or just the ID if not found
      */
    private def resolveResourceRef(id: String): F[ResourceRef] = {
      flatMapInstance.map(store.loadEntityWithParents(entityType, id)) {
        case Some((entity, parentIds)) =>
          ResourceRef(entityType, Some(id), parentIds)
        case None =>
          // Return a ResourceRef with no parents when entity not found.
          // Cedar will evaluate policies based on this partial information,
          // typically resulting in a Deny decision due to missing entity data.
          ResourceRef(entityType, Some(id), Nil)
      }
    }
  }
}
