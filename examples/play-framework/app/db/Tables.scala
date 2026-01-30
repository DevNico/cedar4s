package db

import models._
import slick.jdbc.H2Profile.api._

class UsersTable(tag: Tag) extends Table[User](tag, "users") {
  def id = column[String]("id", O.PrimaryKey)
  def email = column[String]("email")
  def * = (id, email).mapTo[User]
}

class OrgsTable(tag: Tag) extends Table[Org](tag, "orgs") {
  def id = column[String]("id", O.PrimaryKey)
  def name = column[String]("name")
  def * = (id, name).mapTo[Org]
}

class MembershipsTable(tag: Tag) extends Table[Membership](tag, "memberships") {
  def userId = column[String]("user_id")
  def orgId = column[String]("org_id")

  def pk = primaryKey("pk_memberships", (userId, orgId))
  def userFk = foreignKey("fk_memberships_user", userId, TableQuery[UsersTable])(_.id)
  def orgFk = foreignKey("fk_memberships_org", orgId, TableQuery[OrgsTable])(_.id)

  def * = (userId, orgId).mapTo[Membership]
}

class DocumentsTable(tag: Tag) extends Table[Document](tag, "documents") {
  def id = column[String]("id", O.PrimaryKey)
  def orgId = column[String]("org_id")
  def ownerId = column[String]("owner_id")
  def title = column[String]("title")
  def visibility = column[String]("visibility")

  def orgFk = foreignKey("fk_documents_org", orgId, TableQuery[OrgsTable])(_.id)
  def ownerFk = foreignKey("fk_documents_owner", ownerId, TableQuery[UsersTable])(_.id)

  def * = (id, orgId, ownerId, title, visibility).mapTo[Document]
}

object Tables {
  val users = TableQuery[UsersTable]
  val orgs = TableQuery[OrgsTable]
  val memberships = TableQuery[MembershipsTable]
  val documents = TableQuery[DocumentsTable]

  val schema = users.schema ++ orgs.schema ++ memberships.schema ++ documents.schema
}
