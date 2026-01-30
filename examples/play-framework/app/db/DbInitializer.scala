package db

import models._
import slick.jdbc.H2Profile.api._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DbInitializer @Inject() (db: Db)(implicit ec: ExecutionContext) {
  private val users = Tables.users
  private val orgs = Tables.orgs
  private val memberships = Tables.memberships
  private val documents = Tables.documents

  private val seedUsers = Seq(
    User("alice", "alice@example.com"),
    User("bob", "bob@example.com"),
    User("service", "service@example.com")
  )

  private val seedOrgs = Seq(
    Org("org-1", "Acme"),
    Org("org-2", "Globex")
  )

  private val seedMemberships = Seq(
    Membership("alice", "org-1"),
    Membership("bob", "org-2")
  )

  private val seedDocuments = Seq(
    Document("doc-1", "org-1", "alice", "Roadmap", "private"),
    Document("doc-2", "org-1", "alice", "Release Notes", "public"),
    Document("doc-3", "org-2", "bob", "Runbook", "private")
  )

  private val setupAction = for {
    _ <- Tables.schema.createIfNotExists
    userCount <- users.length.result
    _ <- if (userCount == 0) users ++= seedUsers else DBIO.successful(())
    orgCount <- orgs.length.result
    _ <- if (orgCount == 0) orgs ++= seedOrgs else DBIO.successful(())
    membershipCount <- memberships.length.result
    _ <- if (membershipCount == 0) memberships ++= seedMemberships else DBIO.successful(())
    docCount <- documents.length.result
    _ <- if (docCount == 0) documents ++= seedDocuments else DBIO.successful(())
  } yield ()

  db.db.run(setupAction)
}
