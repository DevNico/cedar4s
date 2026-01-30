package cedar4s.entities

import cedar4s.Bijection
import cedar4s.capability.{Applicative, Functor, Monad}
import cedar4s.schema.CedarEntityUid

/** Principal representation for Cedar authorization.
  *
  * Contains information about the authenticated user/service making the authorization request.
  */
final case class CedarPrincipal(
    /** The principal entity UID (e.g., User::"user-123") */
    uid: CedarEntityUid,
    /** Additional entities associated with the principal (user, memberships, roles) */
    entities: CedarEntities = CedarEntities.empty
)

/** Reference to a resource for entity loading.
  *
  * This is a lightweight representation of a resource that contains just enough information to load the entity and its
  * parents.
  */
final case class ResourceRef(
    /** The Cedar entity type (e.g., "MyApp::Mission") */
    entityType: String,
    /** The entity ID (if specific resource, None for collection) */
    entityId: Option[String],
    /** Parent entity references (type -> id) for hierarchy */
    parents: List[(String, String)] = Nil
) {

  /** Get the entity UID (if entityId is present) */
  def uid: Option[CedarEntityUid] = entityId.map(id => CedarEntityUid(entityType, id))

  /** Get parent UIDs */
  def parentUids: List[CedarEntityUid] = parents.map { case (t, id) => CedarEntityUid(t, id) }
}

/** Effect-polymorphic store for loading Cedar entities.
  *
  * The EntityStore is the main entry point for loading entity data during authorization. It uses registered fetchers to
  * load entities and converts them to Cedar's representation.
  *
  * ==Usage==
  *
  * {{{
  * // Create a registry with fetchers
  * val registry = EntityRegistry[IO]()
  *   .register[Organization](new OrgFetcherImpl[IO])
  *   .register[Workspace](new WorkspaceFetcherImpl[IO])
  *
  * // Create the store
  * val store = EntityStore.fromRegistry(registry)
  *
  * // Load entities
  * val entity: IO[Option[CedarEntity]] = store.loadEntity("SaaS::Organization", "org-1")
  * }}}
  */
trait EntityStore[F[_]] {

  /** Load all entities needed for an authorization request.
    *
    * @param principal
    *   The authenticated principal with pre-built entities
    * @param resource
    *   The resource being accessed (entityType + entityId + parents)
    * @return
    *   All entities needed for Cedar evaluation
    */
  def loadForRequest(principal: CedarPrincipal, resource: ResourceRef): F[CedarEntities]

  /** Load entities for batch authorization checks.
    *
    * Optimized for checking multiple resources in a single call.
    *
    * @param principal
    *   The authenticated principal
    * @param resources
    *   The resources being accessed
    * @return
    *   All entities needed for Cedar evaluation
    */
  def loadForBatch(principal: CedarPrincipal, resources: Seq[ResourceRef]): F[CedarEntities]

  /** Load a single entity by type and ID.
    *
    * @param entityType
    *   The Cedar entity type
    * @param entityId
    *   The entity ID
    * @return
    *   The entity if found
    */
  def loadEntity(entityType: String, entityId: String): F[Option[CedarEntity]]

  /** Load multiple entities by their UIDs.
    *
    * @param uids
    *   The entity UIDs to load
    * @return
    *   Loaded entities (missing entities are not included)
    */
  def loadEntities(uids: Set[CedarEntityUid]): F[CedarEntities]

  /** Load an entity and return it with its parent chain.
    *
    * This is used by the deferred `.on(id)` pattern to resolve the full resource hierarchy from just the entity ID.
    *
    * @param entityType
    *   The Cedar entity type (e.g., "DocShare::Document")
    * @param entityId
    *   The entity ID
    * @return
    *   The entity and its parent chain as (parentType, parentId) tuples
    */
  def loadEntityWithParents(entityType: String, entityId: String): F[Option[(CedarEntity, List[(String, String)])]]
}

object EntityStore {

  /** Create a builder for configuring an EntityStore.
    */
  def builder[F[_]: Monad](): EntityStoreBuilder[F] = new EntityStoreBuilder[F]()

  /** Create a store from a registry.
    */
  def fromRegistry[F[_]: Monad](registry: EntityRegistry[F]): EntityStore[F] =
    new BaseEntityStore[F](registry)
}

/** Builder for configuring EntityStore instances.
  *
  * Supports composable configuration of:
  *   - Entity fetchers (via registry)
  *   - Caching (via EntityCache)
  *
  * ==Basic Usage==
  *
  * {{{
  * val store = EntityStore.builder[Future]()
  *   .register(userFetcher)
  *   .register(documentFetcher)
  *   .build()
  * }}}
  *
  * ==With Caching==
  *
  * {{{
  * val cache = CaffeineEntityCache[Future](CaffeineCacheConfig.default)
  *
  * val store = EntityStore.builder[Future]()
  *   .register(userFetcher)
  *   .withCache(cache)
  *   .build()
  * }}}
  */
class EntityStoreBuilder[F[_]: Monad] {
  private var registry: EntityRegistry[F] = EntityRegistry[F]()
  private var cache: Option[EntityCache[F]] = None

  /** Set the entity registry.
    */
  def withRegistry(registry: EntityRegistry[F]): EntityStoreBuilder[F] = {
    this.registry = registry
    this
  }

  /** Register a single fetcher.
    *
    * The fetcher's Id type must match the entity's Id type (via CedarEntityType.Aux). A Bijection[String, Id] must be
    * in scope. For generated newtype IDs, this is automatic.
    */
  def register[A, Id](fetcher: EntityFetcher[F, A, Id])(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): EntityStoreBuilder[F] = {
    this.registry = this.registry.register(fetcher)
    this
  }

  /** Add a cache layer to the entity store.
    *
    * The cache is consulted before fetchers and populated after successful loads.
    *
    * For Caffeine-based caching, use the `cedar4s-caffeine` module:
    *
    * {{{
    * import cedar4s.caffeine.{CaffeineEntityCache, CaffeineCacheConfig}
    *
    * val cache = CaffeineEntityCache[IO](CaffeineCacheConfig.default)
    * builder.withCache(cache)
    * }}}
    *
    * @param cache
    *   The cache implementation to use
    * @return
    *   This builder for chaining
    */
  def withCache(cache: EntityCache[F]): EntityStoreBuilder[F] = {
    this.cache = Some(cache)
    this
  }

  /** Build the EntityStore.
    *
    * If a cache was configured, wraps the base store in a CachingEntityStoreGeneric.
    */
  def build(): EntityStore[F] = {
    val base = new BaseEntityStore[F](registry)
    cache match {
      case Some(c) => new CachingEntityStoreGeneric[F](base, c)
      case None    => base
    }
  }

  /** Build and return the store with caching capabilities exposed.
    *
    * Use this instead of `build()` when you need access to cache invalidation methods (e.g., `invalidate`,
    * `invalidateAll`).
    *
    * '''WARNING:''' This method throws `IllegalStateException` at runtime if no cache is configured. You must call
    * `withCache()` before calling `buildCaching()`.
    *
    * ==Safe Usage==
    *
    * {{{
    * val cache = CaffeineEntityCache[IO](CaffeineCacheConfig.default)
    *
    * val store = EntityStore.builder[IO]()
    *   .register(userFetcher)
    *   .withCache(cache)  // Required before buildCaching()
    *   .buildCaching()
    *
    * // Now you can use cache invalidation
    * store.invalidate(CedarEntityUid("User", "user-123"))
    * }}}
    *
    * ==Alternative==
    *
    * If you don't need cache invalidation methods, use `build()` instead, which works whether or not a cache is
    * configured:
    *
    * {{{
    * val store = EntityStore.builder[IO]()
    *   .register(userFetcher)
    *   .build()  // Works with or without cache
    * }}}
    *
    * @return
    *   A CachingEntityStoreGeneric with cache invalidation methods
    * @throws IllegalStateException
    *   if no cache is configured via `withCache()`
    */
  def buildCaching(): CachingEntityStoreGeneric[F] = {
    val base = new BaseEntityStore[F](registry)
    cache match {
      case Some(c) => new CachingEntityStoreGeneric[F](base, c)
      case None    =>
        throw new IllegalStateException(
          "Cannot buildCaching() without configuring a cache. Use withCache() first."
        )
    }
  }

  /** Get the configured registry.
    */
  def getRegistry: EntityRegistry[F] = registry

  /** Check if caching is configured.
    */
  def hasCaching: Boolean = cache.isDefined
}

/** Base implementation of EntityStore.
  *
  * Uses the registry to look up fetchers and loads entities.
  */
class BaseEntityStore[F[_]](registry: EntityRegistry[F])(implicit F: Monad[F]) extends EntityStore[F] {

  override def loadForRequest(principal: CedarPrincipal, resource: ResourceRef): F[CedarEntities] = {
    F.map(loadResourceWithParents(resource)) { resourceEntities =>
      principal.entities ++ resourceEntities
    }
  }

  override def loadForBatch(principal: CedarPrincipal, resources: Seq[ResourceRef]): F[CedarEntities] = {
    val allUids = resources.flatMap { r =>
      r.uid.toList ++ r.parentUids
    }.toSet

    F.map(loadEntities(allUids))(principal.entities ++ _)
  }

  override def loadEntity(entityType: String, entityId: String): F[Option[CedarEntity]] = {
    registry.get(entityType) match {
      case Some(registered) =>
        registered.fetchEntity(entityId)
      case None =>
        F.pure(None)
    }
  }

  override def loadEntities(uids: Set[CedarEntityUid]): F[CedarEntities] = {
    // Group by entity type for batch loading
    val byType = uids.groupBy(_.entityType)

    F.map(F.traverse(byType.toSeq) { case (entityType, typeUids) =>
      registry.get(entityType) match {
        case Some(registered) =>
          // Use fetchBatch for efficient batch loading instead of per-entity fetch
          registered.fetchBatch(typeUids.map(_.entityId).toSet)
        case None =>
          F.pure(Seq.empty[CedarEntity])
      }
    }) { results =>
      CedarEntities.fromSet(results.flatten.toSet)
    }
  }

  override def loadEntityWithParents(
      entityType: String,
      entityId: String
  ): F[Option[(CedarEntity, List[(String, String)])]] = {
    registry.get(entityType) match {
      case Some(registered) =>
        registered.fetchWithParents(entityId)
      case None =>
        F.pure(None)
    }
  }

  /** Load a resource and all its parent entities.
    */
  private def loadResourceWithParents(resource: ResourceRef): F[CedarEntities] = {
    // Load the resource entity itself
    val resourceF = resource.entityId match {
      case Some(id) => loadEntity(resource.entityType, id)
      case None     => F.pure(None)
    }

    // Load all parent entities recursively
    val parentsF = F.traverse(resource.parents) { case (parentType, parentId) =>
      loadEntityAndParentsRecursive(parentType, parentId)
    }

    F.flatMap(resourceF) { resourceOpt =>
      F.map(parentsF) { parentEntitiesSeq =>
        val resourceEntities = resourceOpt.map(e => CedarEntities(e)).getOrElse(CedarEntities.empty)
        resourceEntities ++ parentEntitiesSeq.foldLeft(CedarEntities.empty)(_ ++ _)
      }
    }
  }

  /** Load an entity and recursively load its parents.
    */
  private def loadEntityAndParentsRecursive(entityType: String, entityId: String): F[CedarEntities] = {
    registry.get(entityType) match {
      case Some(registered) =>
        F.flatMap(registered.fetchWithParents(entityId)) {
          case Some((entity, parentIds)) =>
            // Recursively load parents
            F.map(F.traverse(parentIds) { case (pType, pId) =>
              loadEntityAndParentsRecursive(pType, pId)
            }) { parentEntitiesList =>
              CedarEntities(entity) ++ parentEntitiesList.foldLeft(CedarEntities.empty)(_ ++ _)
            }
          case None =>
            F.pure(CedarEntities.empty)
        }
      case None =>
        F.pure(CedarEntities.empty)
    }
  }
}
