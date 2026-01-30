package cedar4s.schema

/** Cedar entity unique identifier.
  *
  * This is a simple value type representing a Cedar entity UID, consisting of an entity type and an entity ID.
  */
final case class CedarEntityUid(
    entityType: String,
    entityId: String
) {

  /** Format as Cedar entity UID string: Type::"id" */
  def toCedarString: String = s"""$entityType::"$entityId""""

  override def toString: String = toCedarString
}

object CedarEntityUid {

  /** Parse a Cedar entity UID string: Type::"id" */
  def parse(s: String): Option[CedarEntityUid] = {
    val pattern = """(.+)::"([^"]+)"""".r
    s match {
      case pattern(entityType, entityId) => Some(CedarEntityUid(entityType, entityId))
      case _                             => None
    }
  }
}
