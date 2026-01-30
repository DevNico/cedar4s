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
 * Original file: modules/core/src/smithy4s/Bijection.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s
 * - Adapted for Cedar entity ID use case
 */

package cedar4s

import scala.Predef.{identity => identity0, _}

/** A bijection is an association of two opposite functions A => B and B => A.
  *
  * A bijection MUST abide by the round-tripping property, namely, for all input A:
  *
  * {{{
  * bijection.from(bijection(input)) == input
  * }}}
  *
  * This is used by [[Newtype]] to provide conversion between the underlying type and the newtype wrapper.
  */
trait Bijection[A, B] extends Function[A, B] { outer =>

  /** Convert from A to B */
  def to(a: A): B

  /** Convert from B to A */
  def from(b: B): A

  /** Alias for `to` - allows using the bijection as a function */
  final def apply(a: A): B = to(a)

  /** Swap the direction of the bijection */
  def swap: Bijection[B, A] = Bijection(from, to)

  /** Compose with bijections on both source and target types */
  final def imapFull[A0, B0](
      sourceBijection: Bijection[A, A0],
      targetBijection: Bijection[B, B0]
  ): Bijection[A0, B0] = new Bijection[A0, B0] {
    def to(a0: A0): B0 = targetBijection(outer.to(sourceBijection.from(a0)))
    def from(b0: B0): A0 = sourceBijection(outer.from(targetBijection.from(b0)))
  }

  /** Compose with a bijection on the source type */
  final def imapSource[A0](bijection: Bijection[A, A0]): Bijection[A0, B] =
    imapFull(bijection, Bijection.identity)

  /** Compose with a bijection on the target type */
  final def imapTarget[B0](bijection: Bijection[B, B0]): Bijection[A, B0] =
    imapFull(Bijection.identity, bijection)
}

object Bijection {

  /** Identity bijection - converts A to itself */
  def identity[A]: Bijection[A, A] = apply(identity0[A], identity0[A])

  /** Create a bijection from two conversion functions */
  def apply[A, B](to: A => B, from: B => A): Bijection[A, B] =
    new Impl[A, B](to, from)

  private case class Impl[A, B](toFunction: A => B, fromFunction: B => A) extends Bijection[A, B] {
    def to(a: A): B = toFunction(a)
    def from(b: B): A = fromFunction(b)
  }
}
