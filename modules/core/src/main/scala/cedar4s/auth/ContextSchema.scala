package cedar4s.auth

import cedar4s.entities.CedarValue

/** Base trait for Cedar request context.
  *
  * Context provides additional information for policy evaluation beyond principal/action/resource. In Cedar's
  * authorization model, context attributes are available in policies via the `context` variable.
  *
  * ==Schema-Generated Context==
  *
  * For type-safe context, the codegen produces typed context classes from Cedar action context schemas:
  *
  * {{{
  * // From Cedar schema:
  * // action view appliesTo {
  * //     principal: [User],
  * //     resource: Document,
  * //     context: {
  * //         requestTime: Long,
  * //         sourceIP: String,
  * //     }
  * // };
  *
  * // Generated:
  * final case class ViewContext(
  *     requestTime: Option[Long] = None,
  *     sourceIP: Option[String] = None
  * ) extends ContextSchema {
  *   def withRequestTime(t: Long): ViewContext = copy(requestTime = Some(t))
  *   def withSourceIP(ip: String): ViewContext = copy(sourceIP = Some(ip))
  *   // ...
  * }
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * Document.View("folder-1", "doc-1")
  *   .withContext(ViewContext()
  *     .withRequestTime(System.currentTimeMillis())
  *     .withSourceIP("192.168.1.1")
  *   )
  *   .require
  * }}}
  *
  * @see
  *   [[ContextSchema.empty]] for empty context
  * @see
  *   [[MergedContext]] for combining multiple contexts
  */
trait ContextSchema {

  /** Convert to Cedar context map */
  def toMap: Map[String, CedarValue]

  /** Merge with another context (other takes precedence for duplicate keys) */
  def ++(other: ContextSchema): MergedContext = MergedContext(this.toMap ++ other.toMap)

  /** Check if context is empty */
  def isEmpty: Boolean = toMap.isEmpty

  /** Check if context is non-empty */
  def nonEmpty: Boolean = !isEmpty
}

/** Empty context singleton.
  *
  * Use when no context attributes are needed for authorization.
  */
object EmptyContext extends ContextSchema {
  def toMap: Map[String, CedarValue] = Map.empty
  override def isEmpty: Boolean = true
  override def toString: String = "EmptyContext"
}

/** Context constructed from a raw map.
  *
  * This is the result of merging contexts or creating context from explicit key-value pairs.
  *
  * @param attributes
  *   The context attribute map
  */
final case class MergedContext(attributes: Map[String, CedarValue]) extends ContextSchema {
  def toMap: Map[String, CedarValue] = attributes

  /** Add a single attribute */
  def +(kv: (String, CedarValue)): MergedContext = MergedContext(attributes + kv)

  override def toString: String = s"MergedContext(${attributes.keys.mkString(", ")})"
}

object MergedContext {
  val empty: MergedContext = MergedContext(Map.empty)
}

/** Companion object for ContextSchema with factory methods.
  */
object ContextSchema {

  /** Empty context */
  val empty: ContextSchema = EmptyContext

  /** Create context from key-value pairs.
    *
    * {{{
    * val ctx = ContextSchema(
    *   "requestTime" -> CedarValue.long(System.currentTimeMillis()),
    *   "sourceIP" -> CedarValue.string("192.168.1.1")
    * )
    * }}}
    */
  def apply(attrs: (String, CedarValue)*): ContextSchema =
    if (attrs.isEmpty) EmptyContext
    else MergedContext(attrs.toMap)

  /** Create context from a map.
    */
  def fromMap(attrs: Map[String, CedarValue]): ContextSchema =
    if (attrs.isEmpty) EmptyContext
    else MergedContext(attrs)
}
