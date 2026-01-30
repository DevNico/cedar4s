package cedar4s.entities

import cedar4s.schema.CedarEntityUid

/** Typeclass for types that can be converted to Cedar entities.
  *
  * This typeclass is automatically derived for generated entity classes. It provides the conversion logic from domain
  * types to Cedar's entity representation, as well as the ID type for the entity.
  *
  * ==Type Member: Id==
  *
  * Each entity type has an associated `Id` type, which defaults to `String`. When configured with refinements, the `Id`
  * type can be a custom wrapper type (e.g., `CustomerId`, `UUID`, `ULID`). This is used by codegen to generate the
  * correct `EntityFetcher` type alias.
  *
  * ==Generated Instance==
  *
  * {{{
  * // Generated in Entities.scala
  * final case class Customer(id: CustomerId, name: String, ...)
  *
  * object Customer {
  *   given CedarEntityType[Customer] with {
  *     type Id = CustomerId
  *     val entityType = "SaaS::Customer"
  *     def toCedarEntity(cust: Customer) = CedarEntity(...)
  *     def getParentIds(cust: Customer) = Nil
  *   }
  * }
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * import cedar4s.entities.CedarEntityType._
  *
  * val customer: Customer = ...
  * val entity: CedarEntity = customer.toCedarEntity
  * val parents: List[(String, String)] = customer.getParentIds
  * }}}
  */
trait CedarEntityType[A] {

  /** The ID type for this entity.
    *
    * Defaults to `String` for entities without refinement configuration. Can be configured to use refined types like
    * `CustomerId`, `UUID`, etc. Used by codegen to generate the correct EntityFetcher type alias.
    */
  type Id

  /** The Cedar entity type name (e.g., "SaaS::Organization") */
  def entityType: String

  /** Convert the value to a CedarEntity */
  def toCedarEntity(a: A): CedarEntity

  /** Get parent entity IDs for hierarchy resolution */
  def getParentIds(a: A): List[(String, String)]
}

object CedarEntityType {

  /** Auxiliary type for constraining the Id type.
    *
    * Used when registering fetchers to ensure the fetcher's Id matches the entity's Id:
    * {{{
    * def register[A, Id](fetcher: EntityFetcher[F, A, Id])(
    *   using ev: CedarEntityType.Aux[A, Id]
    * ): EntityRegistry[F]
    * }}}
    */
  type Aux[A, Id0] = CedarEntityType[A] { type Id = Id0 }

  /** Summon an instance */
  def apply[A](implicit ev: CedarEntityType[A]): CedarEntityType[A] = ev

  /** Extension methods for types with CedarEntityType instances */
  implicit class CedarEntityTypeOps[A](private val a: A)(implicit ev: CedarEntityType[A]) {
    def toCedarEntity: CedarEntity = ev.toCedarEntity(a)
    def getParentIds: List[(String, String)] = ev.getParentIds(a)
    def cedarEntityType: String = ev.entityType
  }

  /** Create an instance with String ID (default).
    *
    * This is the factory for creating CedarEntityType instances when no ID refinement is needed.
    */
  def instance[A](
      entityTypeName: String,
      toEntity: A => CedarEntity,
      parentIds: A => List[(String, String)] = (_: A) => Nil
  ): CedarEntityType[A] { type Id = String } = new CedarEntityType[A] {
    type Id = String
    val entityType: String = entityTypeName
    def toCedarEntity(a: A): CedarEntity = toEntity(a)
    def getParentIds(a: A): List[(String, String)] = parentIds(a)
  }

  /** Create an instance with a custom ID type.
    *
    * Use this when you want typed IDs instead of raw Strings:
    * {{{
    * given CedarEntityType[Customer] = CedarEntityType.withId[Customer, CustomerId](
    *   entityTypeName = "SaaS::Customer",
    *   toEntity = cust => CedarEntity(...),
    *   parentIds = _ => Nil
    * )
    * }}}
    *
    * @tparam A
    *   The entity type
    * @tparam Id0
    *   The ID type
    */
  def withId[A, Id0](
      entityTypeName: String,
      toEntity: A => CedarEntity,
      parentIds: A => List[(String, String)] = (_: A) => Nil
  ): CedarEntityType[A] { type Id = Id0 } = new CedarEntityType[A] {
    type Id = Id0
    val entityType: String = entityTypeName
    def toCedarEntity(a: A): CedarEntity = toEntity(a)
    def getParentIds(a: A): List[(String, String)] = parentIds(a)
  }
}
