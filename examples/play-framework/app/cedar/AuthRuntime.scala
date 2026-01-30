package cedar

import cedar4s.auth.{FlatMap, Principal}
import cedar4s.capability.Applicative
import cedar4s.capability.instances.{futureMonadError, futureSync}
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.{EntityFetcher, EntityStore}
import db.{DbInitializer, DocumentRepo, OrgRepo, UserRepo}
import example.playframework.cedar.PlayAuth
import example.playframework.cedar.EntityIds.{UserId, OrgId, DocumentId}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthRuntime @Inject() (
    dbInitializer: DbInitializer,
    userRepo: UserRepo,
    orgRepo: OrgRepo,
    documentRepo: DocumentRepo
)(implicit ec: ExecutionContext) {

  private val _ = dbInitializer

  implicit val flatMapFuture: FlatMap[Future] = FlatMap.futureInstance

  private class UserFetcher extends EntityFetcher[Future, PlayAuth.Entity.User, UserId] {
    def fetch(id: UserId): Future[Option[PlayAuth.Entity.User]] =
      userRepo.find(id.value).flatMap {
        case Some(user) =>
          userRepo.listOrgs(user.id).map { orgIds =>
            Some(PlayAuth.Entity.User(id = UserId(user.id), orgs = orgIds.toSet))
          }
        case None => Future.successful(None)
      }

    override def fetchBatch(
        ids: Set[UserId]
    )(implicit F: Applicative[Future]): Future[Map[UserId, PlayAuth.Entity.User]] =
      for {
        usersById <- userRepo.findBatch(ids.map(_.value))
        orgsByUser <- userRepo.listOrgsBatch(usersById.keySet)
      } yield usersById.map { case (strId, user) =>
        UserId(strId) -> PlayAuth.Entity.User(id = UserId(user.id), orgs = orgsByUser.getOrElse(strId, Set.empty))
      }
  }

  private class OrgFetcher extends EntityFetcher[Future, PlayAuth.Entity.Org, OrgId] {
    def fetch(id: OrgId): Future[Option[PlayAuth.Entity.Org]] =
      orgRepo.find(id.value).map(_.map(org => PlayAuth.Entity.Org(OrgId(org.id))))

    override def fetchBatch(ids: Set[OrgId])(implicit F: Applicative[Future]): Future[Map[OrgId, PlayAuth.Entity.Org]] =
      orgRepo
        .findBatch(ids.map(_.value))
        .map(_.map { case (strId, org) => OrgId(strId) -> PlayAuth.Entity.Org(OrgId(org.id)) })
  }

  private class DocumentFetcher extends EntityFetcher[Future, PlayAuth.Entity.Document, DocumentId] {
    def fetch(id: DocumentId): Future[Option[PlayAuth.Entity.Document]] =
      documentRepo
        .find(id.value)
        .map(_.map { doc =>
          PlayAuth.Entity.Document(
            id = DocumentId(doc.id),
            orgId = OrgId(doc.orgId),
            owner = doc.ownerId,
            title = doc.title,
            visibility = doc.visibility
          )
        })

    override def fetchBatch(
        ids: Set[DocumentId]
    )(implicit F: Applicative[Future]): Future[Map[DocumentId, PlayAuth.Entity.Document]] =
      documentRepo
        .findBatch(ids.map(_.value))
        .map(_.map { case (strId, doc) =>
          DocumentId(strId) -> PlayAuth.Entity.Document(
            id = DocumentId(doc.id),
            orgId = OrgId(doc.orgId),
            owner = doc.ownerId,
            title = doc.title,
            visibility = doc.visibility
          )
        })
  }

  val store: EntityStore[Future] = EntityStore
    .builder[Future]()
    .register[PlayAuth.Entity.User, UserId](new UserFetcher)
    .register[PlayAuth.Entity.Org, OrgId](new OrgFetcher)
    .register[PlayAuth.Entity.Document, DocumentId](new DocumentFetcher)
    .build()

  val engine: CedarEngine[Future] =
    CedarEngine.fromResources[Future]("policies", Seq("main.cedar"))

  private def buildPrincipal(principal: Principal): Future[Option[PlayAuth.Entity.User]] =
    principal match {
      case PlayAuth.Principal.User(id) =>
        userRepo.find(id.value).flatMap {
          case Some(user) =>
            userRepo.listOrgs(user.id).map { orgIds =>
              Some(PlayAuth.Entity.User(id = UserId(user.id), orgs = orgIds.toSet))
            }
          case None =>
            Future.successful(None)
        }
      case _ =>
        Future.successful(None)
    }

  val runtime: CedarRuntime[Future, PlayAuth.Entity.User] =
    CedarRuntime[Future, PlayAuth.Entity.User](engine, store, CedarRuntime.resolverFrom(buildPrincipal))
}
