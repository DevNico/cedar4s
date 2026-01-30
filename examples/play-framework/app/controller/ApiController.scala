package controller

import auth.JwtService
import cats.data.{EitherT, Kleisli}
import cats.instances.future._
import cedar.AuthRuntime
import cedar4s.auth.{CedarSession, FlatMap}
import cedar4s.capability.instances.futureMonadError
import db.DocumentRepo
import de.innfactory.smithy4play.ContextRoute
import de.innfactory.smithy4play.routing.Controller
import example.playframework.api.{Document => ApiDocument, _}
import example.playframework.api.PlayFrameworkApiGen.serviceInstance
import example.playframework.authz.DocumentAction
import example.playframework.cedar.PlayAuth
import example.playframework.cedar.EntityIds.{DocumentId, UserId, OrgId}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiController @Inject() (
    authRuntime: AuthRuntime,
    documentRepo: DocumentRepo,
    jwtService: JwtService
)(implicit
    cc: play.api.mvc.ControllerComponents,
    ec: ExecutionContext
) extends PlayFrameworkApi[ContextRoute]
    with Controller {

  implicit val flatMapFuture: FlatMap[Future] = FlatMap.futureInstance

  private def requireUserId(requestHeader: play.api.mvc.RequestHeader): EitherT[Future, Throwable, String] = {
    val bearer = requestHeader.headers.get("Authorization").getOrElse("")
    val token = bearer.stripPrefix("Bearer ").trim

    if (token.isEmpty) {
      EitherT.leftT[Future, String](UnauthorizedError("missing-token"))
    } else {
      jwtService.verify(token) match {
        case Right(claim) =>
          claim.subject match {
            case Some(userId) => EitherT.rightT[Future, Throwable](userId)
            case None         => EitherT.leftT[Future, String](UnauthorizedError("missing-subject"))
          }
        case Left(reason) => EitherT.leftT[Future, String](UnauthorizedError(reason))
      }
    }
  }

  private def toApiActions(actionNames: Set[String]): List[DocumentAction] =
    actionNames.toList.flatMap { name =>
      // Action names from Cedar are like "Document::Read", map to smithy4s enum values
      val simpleName = name.split("::").lastOption.getOrElse(name)
      DocumentAction.values.find(_.value.equalsIgnoreCase(simpleName))
    }

  private def toApiDocument(doc: models.Document, actionNames: Set[String]): ApiDocument =
    ApiDocument(
      allowedActions = toApiActions(actionNames),
      id = doc.id,
      orgId = doc.orgId,
      ownerId = doc.ownerId,
      title = doc.title,
      visibility = doc.visibility
    )

  override def issueToken(userId: String): ContextRoute[IssueTokenOutput] = Kleisli { _ =>
    EitherT.rightT[Future, Throwable](IssueTokenOutput(jwtService.issue(userId)))
  }

  override def getDocument(documentId: String): ContextRoute[ApiDocument] = Kleisli { rc =>
    requireUserId(rc.requestHeader).flatMap { userId =>
      implicit val session: CedarSession[Future] = authRuntime.runtime.session(PlayAuth.Principal.User(UserId(userId)))

      EitherT.right[Throwable](documentRepo.find(documentId)).flatMap {
        case Some(doc) =>
          EitherT.right[Throwable](PlayAuth.Document.Read.on(DocumentId(doc.id)).isAllowed).flatMap {
            case true =>
              EitherT.right[Throwable](
                PlayAuth.Document.allowedActionNames(DocumentId(doc.id)).map { actionNames =>
                  toApiDocument(doc, actionNames)
                }
              )
            case false =>
              EitherT.leftT[Future, ApiDocument](ForbiddenError("not-allowed"))
          }
        case None =>
          EitherT.leftT[Future, ApiDocument](NotFoundError("document-not-found"))
      }
    }
  }

  override def createDocument(orgId: String, title: String, visibility: String): ContextRoute[ApiDocument] = Kleisli {
    rc =>
      requireUserId(rc.requestHeader).flatMap { userId =>
        implicit val session: CedarSession[Future] =
          authRuntime.runtime.session(PlayAuth.Principal.User(UserId(userId)))

        EitherT.right[Throwable](PlayAuth.Org.DocumentCreate.on(OrgId(orgId)).isAllowed).flatMap {
          case true =>
            val doc = models.Document(
              id = java.util.UUID.randomUUID().toString,
              orgId = orgId,
              ownerId = userId,
              title = title,
              visibility = visibility
            )
            EitherT.right[Throwable](documentRepo.insert(doc)).flatMap { _ =>
              EitherT.right[Throwable](
                PlayAuth.Document.allowedActionNames(DocumentId(doc.id)).map { actionNames =>
                  toApiDocument(doc, actionNames)
                }
              )
            }
          case false =>
            EitherT.leftT[Future, ApiDocument](ForbiddenError("not-allowed"))
        }
      }
  }

  override def listDocuments(orgId: String): ContextRoute[ListDocumentsOutput] = Kleisli { rc =>
    requireUserId(rc.requestHeader).flatMap { userId =>
      implicit val session: CedarSession[Future] = authRuntime.runtime.session(PlayAuth.Principal.User(UserId(userId)))

      EitherT.right[Throwable](documentRepo.findByOrg(orgId)).flatMap { docs =>
        // Filter to only documents the user can read, then get capabilities
        EitherT.right[Throwable](
          Future
            .traverse(docs) { doc =>
              PlayAuth.Document.Read.on(DocumentId(doc.id)).isAllowed.map(allowed => (doc, allowed))
            }
            .flatMap { results =>
              val allowed = results.filter(_._2).map(_._1)
              Future
                .traverse(allowed) { doc =>
                  PlayAuth.Document.allowedActionNames(DocumentId(doc.id)).map { actionNames =>
                    toApiDocument(doc, actionNames)
                  }
                }
                .map(list => ListDocumentsOutput(list.toList))
            }
        )
      }
    }
  }
}
