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
 * Original files:
 *   - modules/core/src-3/Newtype.scala
 *   - modules/core/src-3/AbstractNewtype.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s
 * - Removed smithy4s-specific dependencies (Schema, Hints, ShapeTag)
 * - Merged AbstractNewtype into Newtype for simplicity
 * - Simplified for Cedar entity ID use case
 */

package cedar4s

/** Base class for creating newtype wrappers using Scala 3 opaque types.
  *
  * A newtype is a zero-cost abstraction that wraps an underlying type with a distinct type at compile time, while
  * erasing to the underlying type at runtime. This provides type safety without runtime overhead.
  *
  * This is particularly useful for Cedar entity IDs, where we want distinct types for different entity IDs (e.g.,
  * `UserId` vs `GroupId`) to prevent accidentally mixing them up, while still having them be strings at runtime.
  *
  * Example usage:
  * {{{
  * // Define a newtype for user IDs
  * object UserId extends Newtype[String]
  * type UserId = UserId.Type
  *
  * // Create instances
  * val id: UserId = UserId("user-123")
  *
  * // Access underlying value
  * val str: String = id.value
  *
  * // Pattern matching
  * id match {
  *   case UserId(s) => println(s"User: $s")
  * }
  *
  * // Use the built-in bijection for conversions
  * val bijection: Bijection[String, UserId] = UserId.bijection
  * }}}
  *
  * @tparam A
  *   The underlying type being wrapped
  */
abstract class Newtype[A] { self =>

  /** The opaque type - erased to A at runtime */
  opaque type Type = A

  /** Unwrap the newtype to access the underlying value */
  extension (orig: Type) def value: A = orig

  /** Wrap a value in the newtype */
  def apply(a: A): Type = a

  /** Pattern matching extractor */
  def unapply(orig: Type): Some[A] = Some(orig.value)

  /** Bijection between the underlying type and the newtype. Useful for generic transformations and conversions.
    */
  implicit val bijection: Bijection[A, Type] = new Newtype.Make[A, Type] {
    def to(a: A): Type = self.apply(a)
    def from(t: Type): A = self.value(t)
  }
}

object Newtype {

  /** Internal marker trait for newtype bijections */
  private[cedar4s] trait Make[A, B] extends Bijection[A, B]
}
