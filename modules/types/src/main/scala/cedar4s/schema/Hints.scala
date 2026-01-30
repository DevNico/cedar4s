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
 * Original file: modules/core/src/smithy4s/Hints.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s.schema
 * - Removed member/target hint level distinction (Cedar only needs single-level hints)
 * - Simplified API to basic get/put operations (no lazy loading, no dynamic hints)
 * - Removed Schema and Document dependencies
 * - Changed equality from instance-based (eq) to value-based
 * - Added Cedar-specific hints (ownership, parents, collectionAction)
 * - Removed Smithy-specific features (trait hints, dynamic bindings, filters)
 */

package cedar4s.schema

/** Type-safe hint key for metadata attached to Cedar schema shapes.
  *
  * Similar to smithy4s Hints, this provides a way to attach arbitrary metadata to generated types without modifying
  * their structure.
  *
  * Each hint has a unique ShapeId and an associated value type.
  *
  * @tparam A
  *   The type of value this hint carries
  */
trait Hint[A] {

  /** Unique identifier for this hint type */
  def id: ShapeId

  /** Wrap a value in this hint */
  def apply(value: A): Hints.Binding[A] = Hints.Binding(this, value)
}

object Hint {

  /** Create a hint with the given id */
  def apply[A](shapeId: ShapeId): Hint[A] = new Hint[A] {
    val id: ShapeId = shapeId
  }

  /** Create a hint in the "cedar" namespace */
  def cedar[A](name: String): Hint[A] = apply(ShapeId.cedar(name))
}

/** A type-safe, heterogeneous map of hints.
  *
  * Hints carry metadata about Cedar schema shapes, such as:
  *   - Ownership type (Root, Direct, Indirect)
  *   - Parent relationships
  *   - Documentation
  *   - Custom annotations
  *
  * This is similar to smithy4s Hints but simplified for Cedar's needs.
  */
final class Hints private (
    private val map: Map[ShapeId, Any]
) {

  /** Get a hint value by its key */
  def get[A](hint: Hint[A]): Option[A] =
    map.get(hint.id).map(_.asInstanceOf[A])

  /** Check if a hint is present */
  def contains[A](hint: Hint[A]): Boolean =
    map.contains(hint.id)

  /** Get a hint value or a default */
  def getOrElse[A](hint: Hint[A], default: => A): A =
    get(hint).getOrElse(default)

  /** Add a hint binding */
  def +[A](binding: Hints.Binding[A]): Hints =
    new Hints(map + (binding.hint.id -> binding.value))

  /** Combine with another Hints (right takes precedence) */
  def ++(other: Hints): Hints =
    new Hints(map ++ other.map)

  /** Get all hint IDs */
  def keys: Set[ShapeId] = map.keySet

  /** Check if empty */
  def isEmpty: Boolean = map.isEmpty

  /** Check if non-empty */
  def nonEmpty: Boolean = map.nonEmpty

  /** Number of hints */
  def size: Int = map.size

  override def toString: String =
    s"Hints(${map.keys.map(_.fullName).mkString(", ")})"

  override def equals(other: Any): Boolean = other match {
    case that: Hints => this.map == that.map
    case _           => false
  }

  override def hashCode: Int = map.hashCode
}

object Hints {

  /** A binding of a hint to its value */
  final case class Binding[A](hint: Hint[A], value: A)

  /** Empty hints */
  val empty: Hints = new Hints(Map.empty)

  /** Create hints from bindings */
  def apply(bindings: Binding[?]*): Hints =
    bindings.foldLeft(empty)((h, b) => h + b.asInstanceOf[Binding[Any]])

  /** Create hints from a single binding */
  def apply[A](binding: Binding[A]): Hints =
    empty + binding
}

// ============================================================================
// Built-in Cedar Hints
// ============================================================================

/** Ownership classification for Cedar entities.
  */
sealed trait OwnershipType

object OwnershipType {

  /** Root entity - no parents (e.g., Customer/Tenant) */
  case object Root extends OwnershipType

  /** Direct child of root (e.g., Location in Customer) */
  case object Direct extends OwnershipType

  /** Indirect descendant via intermediate entities (e.g., Mission in Location in Customer) */
  case object Indirect extends OwnershipType
}

/** Standard hints used by Cedar codegen.
  */
object CedarHints {

  /** Ownership type hint */
  val ownership: Hint[OwnershipType] = Hint.cedar("ownership")

  /** Parent entity names in order from root */
  val parents: Hint[List[String]] = Hint.cedar("parents")

  /** Documentation string */
  val doc: Hint[String] = Hint.cedar("doc")

  /** Entity attributes schema */
  val attributes: Hint[Map[String, String]] = Hint.cedar("attributes")

  /** Whether this is a collection action (create/list) */
  val collectionAction: Hint[Boolean] = Hint.cedar("collectionAction")
}
