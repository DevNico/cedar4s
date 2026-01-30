package db

import models._
import slick.jdbc.H2Profile.api._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserRepo @Inject() (db: Db)(implicit ec: ExecutionContext) {
  private val users = Tables.users
  private val memberships = Tables.memberships

  def find(id: String): Future[Option[User]] =
    db.db.run(users.filter(_.id === id).result.headOption)

  def listOrgs(userId: String): Future[Seq[String]] =
    db.db.run(memberships.filter(_.userId === userId).map(_.orgId).result)

  def findBatch(ids: Set[String]): Future[Map[String, User]] = {
    if (ids.isEmpty) Future.successful(Map.empty)
    else db.db.run(users.filter(_.id inSet ids).result).map(_.map(u => u.id -> u).toMap)
  }

  def listOrgsBatch(userIds: Set[String]): Future[Map[String, Set[String]]] = {
    if (userIds.isEmpty) Future.successful(Map.empty)
    else {
      db.db
        .run(memberships.filter(_.userId inSet userIds).result)
        .map { rows =>
          rows.groupBy(_.userId).view.mapValues(_.map(_.orgId).toSet).toMap
        }
    }
  }
}

@Singleton
class OrgRepo @Inject() (db: Db)(implicit ec: ExecutionContext) {
  private val orgs = Tables.orgs

  def find(id: String): Future[Option[Org]] =
    db.db.run(orgs.filter(_.id === id).result.headOption)

  def findBatch(ids: Set[String]): Future[Map[String, Org]] = {
    if (ids.isEmpty) Future.successful(Map.empty)
    else db.db.run(orgs.filter(_.id inSet ids).result).map(_.map(o => o.id -> o).toMap)
  }
}

@Singleton
class DocumentRepo @Inject() (db: Db)(implicit ec: ExecutionContext) {
  private val documents = Tables.documents

  def find(id: String): Future[Option[Document]] =
    db.db.run(documents.filter(_.id === id).result.headOption)

  def insert(doc: Document): Future[Int] =
    db.db.run(documents += doc)

  def findByOrg(orgId: String): Future[Seq[Document]] =
    db.db.run(documents.filter(_.orgId === orgId).result)

  def findBatch(ids: Set[String]): Future[Map[String, Document]] = {
    if (ids.isEmpty) Future.successful(Map.empty)
    else db.db.run(documents.filter(_.id inSet ids).result).map(_.map(d => d.id -> d).toMap)
  }
}
