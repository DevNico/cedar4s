/*
 * Adapted from smithy4s: https://github.com/disneystreaming/smithy4s
 * Copyright 2021-2026 Disney Streaming
 *
 * Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ---
 *
 * This file contains code adapted from the smithy4s project.
 * Original source: https://github.com/disneystreaming/smithy4s
 * Original file: modules/core/src/ShapeTag.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s.schema
 * - Removed Schema dependency (Cedar doesn't need Smithy's Schema)
 * - Changed equality from instance equality (eq) to ShapeId-based equality
 * - Removed HasId trait (not needed for Cedar)
 * - Added ShapeMap utility for type-safe heterogeneous maps
 * - Simplified Companion trait for Cedar entity/action generation
 */

package cedar4s.schema

/** A type-safe tag that uniquely identifies a Cedar schema shape.
  *
  * Similar to smithy4s ShapeTag, this provides referential equality for type-safe lookups in polymorphic collections.
  *
  * Each generated entity/action object extends ShapeTag.Companion, making it usable as a key in type-safe maps.
  *
  * @tparam A
  *   The Scala type this tag represents
  */
trait ShapeTag[A] {

  /** Unique identifier for this shape */
  def id: ShapeId

  /** Hints attached to this shape */
  def hints: Hints

  // Use ShapeId for equality, not object identity
  override def equals(other: Any): Boolean = other match {
    case that: ShapeTag[?] => this.id == that.id
    case _                 => false
  }

  override def hashCode: Int = id.hashCode

  override def toString: String = s"ShapeTag(${id.fullName})"
}

object ShapeTag {

  /** Companion object mixin for generated entity/action types.
    *
    * Generated code extends this trait:
    * {{{
    * object Customer extends ShapeTag.Companion[CustomerAttributes] {
    *   val id = ShapeId("Robotsecurity", "Customer")
    *   val hints = Hints(CedarHints.ownership(OwnershipType.Root))
    * }
    * }}}
    *
    * @tparam A
    *   The Scala type this companion represents
    */
  trait Companion[A] extends ShapeTag[A]

  /** Create a simple ShapeTag (useful for testing).
    */
  def apply[A](shapeId: ShapeId, shapeHints: Hints = Hints.empty): ShapeTag[A] =
    new ShapeTag[A] {
      val id: ShapeId = shapeId
      val hints: Hints = shapeHints
    }
}

/** A type-safe heterogeneous map keyed by ShapeTag.
  *
  * Useful for entity registries and caches where you need to store values of different types indexed by their schema
  * shape.
  *
  * {{{
  * val registry = ShapeMap.empty
  *   .put(Customer, loadCustomers())
  *   .put(Location, loadLocations())
  *
  * val customers: Option[List[CustomerData]] = registry.get(Customer)
  * }}}
  */
final class ShapeMap private (
    private val map: Map[ShapeId, Any]
) {

  /** Get a value by its shape tag */
  def get[A](tag: ShapeTag[A]): Option[A] =
    map.get(tag.id).map(_.asInstanceOf[A])

  /** Get a value or throw if not found */
  def apply[A](tag: ShapeTag[A]): A =
    get(tag).getOrElse(throw new NoSuchElementException(s"Key not found: ${tag.id}"))

  /** Put a value with its shape tag */
  def put[A](tag: ShapeTag[A], value: A): ShapeMap =
    new ShapeMap(map + (tag.id -> value))

  /** Remove a value by its shape tag */
  def remove[A](tag: ShapeTag[A]): ShapeMap =
    new ShapeMap(map - tag.id)

  /** Check if a key exists */
  def contains[A](tag: ShapeTag[A]): Boolean =
    map.contains(tag.id)

  /** Get all shape IDs */
  def keys: Set[ShapeId] = map.keySet

  /** Check if empty */
  def isEmpty: Boolean = map.isEmpty

  /** Number of entries */
  def size: Int = map.size

  /** Combine with another ShapeMap (right takes precedence) */
  def ++(other: ShapeMap): ShapeMap =
    new ShapeMap(map ++ other.map)
}

object ShapeMap {

  /** Empty shape map */
  val empty: ShapeMap = new ShapeMap(Map.empty)

  /** Create from a single entry */
  def apply[A](tag: ShapeTag[A], value: A): ShapeMap =
    empty.put(tag, value)
}
