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
 * Original file: modules/core/src-2/smithy4s/Newtype.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s
 * - Removed smithy4s-specific dependencies (Schema, Hints, ShapeTag)
 * - Simplified for Cedar entity ID use case (codegen only, runtime uses Scala 3)
 */

package cedar4s

/** Base class for creating newtype wrappers.
  *
  * This is the Scala 2 implementation used for cross-compilation of the codegen module to support the sbt plugin. In
  * practice, runtime code uses Scala 3's opaque type version which provides zero-cost abstractions.
  *
  * The Scala 2 version uses a type alias which provides source compatibility but not the same type safety guarantees as
  * opaque types.
  *
  * @tparam A
  *   The underlying type being wrapped
  */
abstract class Newtype[A] { self =>

  /** In Scala 2, Type is just an alias for A. This provides source compatibility with Scala 3's opaque type version.
    */
  type Type = A

  /** Wrap a value in the newtype */
  def apply(a: A): Type = a

  /** Pattern matching extractor */
  def unapply(t: Type): Some[A] = Some(t)

  /** Extension method simulation for value access */
  implicit class TypeOps(val t: Type) {
    def value: A = t
  }

  /** Bijection between the underlying type and the newtype. In Scala 2, this is just the identity since Type = A.
    */
  implicit val bijection: Bijection[A, Type] = new Newtype.Make[A, Type] {
    def to(a: A): Type = self.apply(a)
    def from(t: Type): A = t
  }
}

object Newtype {

  /** Internal marker trait for newtype bijections */
  private[cedar4s] trait Make[A, B] extends Bijection[A, B]
}
