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
 * Original file: modules/core/src/smithy4s/ShapeId.scala
 *
 * Modifications:
 * - Changed package from smithy4s to cedar4s.schema
 * - Adapted separator from '#' (Smithy) to '::' (Cedar)
 * - Added Cedar-specific methods (toCedarType, toCedarAction)
 * - Removed Smithy-specific features (HasId, IdRef, Schema, withMember)
 * - Simplified for Cedar use case
 */

package cedar4s.schema

/** A unique identifier for a Cedar schema shape.
  *
  * Similar to smithy4s ShapeId, this provides a namespace + name pair that uniquely identifies entities, actions, and
  * types within a Cedar schema.
  *
  * @param namespace
  *   The Cedar namespace (e.g., "Robotsecurity")
  * @param name
  *   The shape name (e.g., "Customer", "Mission::read")
  */
final case class ShapeId(
    namespace: String,
    name: String
) {

  /** Full qualified name with "::" separator */
  def fullName: String = s"$namespace::$name"

  /** Format as Cedar entity type reference */
  def toCedarType: String = fullName

  /** Format as Cedar action reference */
  def toCedarAction: String = s"""$namespace::Action::"$name""""

  override def toString: String = fullName
}

object ShapeId {

  /** Parse a qualified name like "Robotsecurity::Customer" */
  def parse(qualified: String): Option[ShapeId] = {
    val parts = qualified.split("::", 2)
    if (parts.length == 2) Some(ShapeId(parts(0), parts(1)))
    else None
  }

  /** Create from namespace and simple name */
  def apply(namespace: String, name: String): ShapeId =
    new ShapeId(namespace, name)

  /** Create from a single namespace for built-in hints */
  def cedar(name: String): ShapeId = ShapeId("cedar", name)
}
