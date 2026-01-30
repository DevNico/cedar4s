package cedar4s.entities

import cedar4s.capability.Applicative

/** Effect-polymorphic fetcher for Cedar entities with typed IDs.
  *
  * This is the core trait for loading entities from your data store. It works with any effect type F[_] (Future, IO,
  * Task, etc.) and supports typed entity IDs (String, UUID, custom newtypes, etc.).
  *
  * ==Type Parameters==
  *
  * @tparam F
  *   The effect type (e.g., Future, IO)
  * @tparam A
  *   The entity type (generated Cedar entity class)
  * @tparam Id
  *   The entity ID type (e.g., String, CustomerId, UUID)
  *
  * ==Example Implementation==
  *
  * Extend the generated type alias for your entity:
  *
  * {{{
  * // Generated: type CustomerFetcher[F[_]] = EntityFetcher[F, Customer, CustomerId]
  *
  * class CustomerFetcherImpl(db: Database)(implicit ec: ExecutionContext)
  *     extends CustomerFetcher[Future] {
  *
  *   def fetch(id: CustomerId): Future[Option[Customer]] =
  *     db.customers.findById(id)  // id is CustomerId - direct use!
  *
  *   // Optional: override for optimized batch loading
  *   override def fetchBatch(ids: Set[CustomerId])(implicit ev: Applicative[Future]) =
  *     db.customers.findByIds(ids).map(_.map(c => c.id -> c).toMap)
  * }
  * }}}
  *
  * For simple cases, use the factory method:
  *
  * {{{
  * // String IDs (most common)
  * val orgFetcher = EntityFetcher[Future, Organization, String] { id =>
  *   db.organizations.findById(id)
  * }
  *
  * // Custom ID types
  * val customerFetcher = EntityFetcher[Future, Customer, CustomerId] { id =>
  *   db.customers.findById(id)
  * }
  * }}}
  */
trait EntityFetcher[F[_], A, Id] {

  /** Fetch a single entity by ID.
    *
    * @param id
    *   The entity ID
    * @return
    *   F[Option[A]] - the entity if found, None if not found
    */
  def fetch(id: Id): F[Option[A]]

  /** Fetch multiple entities by ID.
    *
    * Override this method for optimized batch loading (e.g., SQL IN clause). The default implementation calls fetch()
    * for each ID.
    *
    * @param ids
    *   Set of entity IDs to fetch
    * @return
    *   F[Map[Id, A]] - map of found entities (missing IDs omitted)
    */
  def fetchBatch(ids: Set[Id])(implicit F: Applicative[F]): F[Map[Id, A]] =
    F.map(F.traverse(ids.toSeq)(id => F.map(fetch(id))(_.map(id -> _))))(_.flatten.toMap)
}

object EntityFetcher {

  /** Create a fetcher with typed IDs.
    *
    * This is the primary factory method for creating entity fetchers. Works with any ID type including String, UUID, or
    * custom newtypes.
    *
    * {{{
    * // String IDs (most common)
    * val fetcher = EntityFetcher[Future, Organization, String] { id =>
    *   Future.successful(organizations.get(id))
    * }
    *
    * // Custom ID types
    * val customerFetcher = EntityFetcher[Future, Customer, CustomerId] { id =>
    *   db.customers.findById(id)
    * }
    * }}}
    */
  def apply[F[_], A, Id](f: Id => F[Option[A]]): EntityFetcher[F, A, Id] =
    new EntityFetcher[F, A, Id] {
      def fetch(id: Id): F[Option[A]] = f(id)
    }

  /** Create a fetcher with typed IDs and custom batch implementation.
    *
    * Use this when you can optimize batch fetching (e.g., SQL IN clause).
    *
    * {{{
    * val fetcher = EntityFetcher.withBatch[Future, Organization, String](
    *   id => db.organizations.findById(id),
    *   ids => db.organizations.findByIds(ids).map(_.map(o => o.id -> o).toMap)
    * )
    * }}}
    */
  def withBatch[F[_], A, Id](
      f: Id => F[Option[A]],
      batch: Set[Id] => F[Map[Id, A]]
  ): EntityFetcher[F, A, Id] = new EntityFetcher[F, A, Id] {
    def fetch(id: Id): F[Option[A]] = f(id)
    override def fetchBatch(ids: Set[Id])(implicit ev: Applicative[F]): F[Map[Id, A]] = batch(ids)
  }
}
