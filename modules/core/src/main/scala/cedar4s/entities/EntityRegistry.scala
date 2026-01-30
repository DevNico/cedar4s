package cedar4s.entities

import cedar4s.Bijection
import cedar4s.capability.{Applicative, Functor}

import scala.collection.concurrent.TrieMap

/** Registry for entity fetchers.
  *
  * The registry maintains a mapping from entity type names to their fetchers, allowing the EntityStore to dynamically
  * look up the appropriate fetcher for any entity type.
  *
  * ==Thread Safety==
  *
  * The registry is thread-safe and can be modified concurrently. However, in typical usage, all fetchers are registered
  * during application startup before any authorization requests are made.
  *
  * ==Usage==
  *
  * {{{
  * // With generated newtype IDs, the bijection is automatically available
  * val registry = EntityRegistry[IO]()
  *   .register(orgFetcher)      // EntityFetcher[IO, Organization, OrganizationId]
  *   .register(customerFetcher) // EntityFetcher[IO, Customer, CustomerId]
  *
  * val store = EntityStore.fromRegistry(registry)
  * }}}
  */
trait EntityRegistry[F[_]] {

  /** Register a fetcher for an entity type.
    *
    * The fetcher's Id type must match the entity's Id type (via CedarEntityType.Aux). A Bijection[String, Id] instance
    * must be in scope to enable String conversion. For generated newtype IDs, this bijection is automatically provided.
    *
    * @param fetcher
    *   The fetcher to register
    * @return
    *   This registry for fluent chaining
    */
  def register[A, Id](fetcher: EntityFetcher[F, A, Id])(implicit
      ev: CedarEntityType.Aux[A, Id],
      bij: Bijection[String, Id]
  ): EntityRegistry[F]

  /** Get the fetcher and type info for an entity type.
    *
    * @param entityType
    *   The Cedar entity type name
    * @return
    *   The fetcher and CedarEntityType if registered, None otherwise
    */
  def get(entityType: String): Option[RegisteredFetcher[F, _]]

  /** Check if a fetcher is registered for an entity type.
    */
  def contains(entityType: String): Boolean

  /** Get all registered entity types.
    */
  def entityTypes: Set[String]

  /** Get the number of registered fetchers.
    */
  def size: Int
}

/** A registered fetcher with its type information.
  *
  * This wraps an EntityFetcher and handles the conversion between Cedar's string-based IDs and the entity's typed ID.
  */
trait RegisteredFetcher[F[_], A] {
  def entityType: CedarEntityType[A]

  /** Fetch and convert to CedarEntity.
    *
    * The String ID is converted to the entity's typed ID internally.
    */
  def fetchEntity(id: String)(implicit F: Functor[F]): F[Option[CedarEntity]]

  /** Fetch multiple entities by ID and convert to CedarEntities.
    *
    * This uses the fetcher's batch implementation for efficiency. String IDs are converted to typed IDs internally.
    */
  def fetchBatch(ids: Set[String])(implicit F: Applicative[F]): F[Seq[CedarEntity]]

  /** Fetch and get parent IDs.
    *
    * The String ID is converted to the entity's typed ID internally.
    */
  def fetchWithParents(id: String)(implicit F: Functor[F]): F[Option[(CedarEntity, List[(String, String)])]]
}

object EntityRegistry {

  /** Create a new empty registry.
    */
  def apply[F[_]](): EntityRegistry[F] = new DefaultEntityRegistry[F]()

  /** Create a registry with initial fetchers.
    */
  def of[F[_]]: RegistryBuilder[F] = new RegistryBuilder[F]

  class RegistryBuilder[F[_]] {
    private val registry = new DefaultEntityRegistry[F]()

    def add[A, Id](fetcher: EntityFetcher[F, A, Id])(implicit
        ev: CedarEntityType.Aux[A, Id],
        bij: Bijection[String, Id]
    ): RegistryBuilder[F] = {
      registry.register(fetcher)
      this
    }

    def build: EntityRegistry[F] = registry
  }

  /** Default implementation using a concurrent map.
    */
  private class DefaultEntityRegistry[F[_]] extends EntityRegistry[F] {
    private val fetchers = TrieMap.empty[String, RegisteredFetcher[F, _]]

    override def register[A, Id](fetcher: EntityFetcher[F, A, Id])(implicit
        ev: CedarEntityType.Aux[A, Id],
        bij: Bijection[String, Id]
    ): EntityRegistry[F] = {
      val registered = new RegisteredFetcherImpl[F, A, Id](fetcher, ev, bij)
      fetchers.put(ev.entityType, registered)
      this
    }

    override def get(entityType: String): Option[RegisteredFetcher[F, _]] =
      fetchers.get(entityType)

    override def contains(entityType: String): Boolean =
      fetchers.contains(entityType)

    override def entityTypes: Set[String] =
      fetchers.keySet.toSet

    override def size: Int =
      fetchers.size
  }

  /** Internal implementation of RegisteredFetcher that bridges typed IDs to String.
    */
  private class RegisteredFetcherImpl[F[_], A, Id](
      fetcher: EntityFetcher[F, A, Id],
      ev: CedarEntityType[A],
      bij: Bijection[String, Id]
  ) extends RegisteredFetcher[F, A] {

    val entityType: CedarEntityType[A] = ev

    def fetchEntity(id: String)(implicit F: Functor[F]): F[Option[CedarEntity]] =
      F.map(fetcher.fetch(bij.to(id)))(_.map(ev.toCedarEntity))

    def fetchBatch(ids: Set[String])(implicit F: Applicative[F]): F[Seq[CedarEntity]] = {
      val typedIds = ids.map(bij.to)
      F.map(fetcher.fetchBatch(typedIds))(_.values.map(ev.toCedarEntity).toSeq)
    }

    def fetchWithParents(id: String)(implicit F: Functor[F]): F[Option[(CedarEntity, List[(String, String)])]] =
      F.map(fetcher.fetch(bij.to(id)))(_.map(a => (ev.toCedarEntity(a), ev.getParentIds(a))))
  }
}
