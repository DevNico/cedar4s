package example.http4s

import cedar4s.auth.{CedarSession, FlatMap, Principal}
import cedar4s.capability.Sync
import cedar4s.client.{CedarEngine, CedarRuntime}
import cedar4s.entities.{CedarEntityType, EntityFetcher, EntityStore}
import cats.effect.{IO, IOApp}
import io.circe.generic.auto._
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.HttpRoutes
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import example.http4s.cedar.Http4sAuth
import example.http4s.cedar.EntityIds.{DocumentId, UserId}

import scala.concurrent.{ExecutionContext, Future}

object Http4sAuthServer extends IOApp.Simple {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory[IO]

  implicit val ioSync: Sync[IO] = Sync.instance(
    new Sync.Builder[IO] {
      def pure[A](a: A): IO[A] = IO.pure(a)
      def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
      def raiseError[A](e: Throwable): IO[A] = IO.raiseError(e)
      def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.handleErrorWith(f)
      def delay[A](thunk: => A): IO[A] = IO.delay(thunk)
      def blocking[A](thunk: => A): IO[A] = IO.blocking(thunk)
    }
  )

  implicit val ioFlatMap: FlatMap[IO] = FlatMap.instance(
    new FlatMap.Builder[IO] {
      def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
      def map[A, B](fa: IO[A])(f: A => B): IO[B] = fa.map(f)
      def pure[A](a: A): IO[A] = IO.pure(a)
      def liftFuture[A](future: Future[A]): IO[A] = IO.fromFuture(IO(future))
    }
  )

  private val users: Map[String, Http4sAuth.Entity.User] = Map(
    "alice" -> Http4sAuth.Entity.User(UserId("alice")),
    "bob" -> Http4sAuth.Entity.User(UserId("bob"))
  )

  private val documents: Map[String, Http4sAuth.Entity.Document] = Map(
    "doc-1" -> Http4sAuth.Entity.Document(id = DocumentId("doc-1"), title = "Roadmap", owner = "alice"),
    "doc-2" -> Http4sAuth.Entity.Document(id = DocumentId("doc-2"), title = "Notes", owner = "bob")
  )

  private class UserFetcher extends EntityFetcher[IO, Http4sAuth.Entity.User, UserId] {
    def fetch(id: UserId): IO[Option[Http4sAuth.Entity.User]] = IO.pure(users.get(id.value))
  }

  private class DocumentFetcher extends EntityFetcher[IO, Http4sAuth.Entity.Document, DocumentId] {
    def fetch(id: DocumentId): IO[Option[Http4sAuth.Entity.Document]] = IO.pure(documents.get(id.value))
  }

  private val store: EntityStore[IO] = EntityStore
    .builder[IO]()
    .register[Http4sAuth.Entity.User, UserId](new UserFetcher)
    .register[Http4sAuth.Entity.Document, DocumentId](new DocumentFetcher)
    .build()

  private val engine: CedarEngine[IO] =
    CedarEngine.fromResources[IO]("policies", Seq("main.cedar"))

  private def resolvePrincipal(principal: Principal): IO[Option[Http4sAuth.Entity.User]] = {
    principal match {
      case Http4sAuth.Principal.User(id) =>
        IO.pure(users.get(id.value))
      case _ =>
        IO.pure(None)
    }
  }

  private val cedarRuntime: CedarRuntime[IO, Http4sAuth.Entity.User] =
    CedarRuntime[IO, Http4sAuth.Entity.User](engine, store, CedarRuntime.resolverFrom(resolvePrincipal))

  final case class CheckAccessRequest(principalId: String, documentId: String)
  final case class CheckAccessResponse(allowed: Boolean)

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "auth" / "check" =>
    for {
      input <- req.as[CheckAccessRequest]
      allowed <- {
        given CedarSession[IO] = cedarRuntime.session(Http4sAuth.Principal.User(UserId(input.principalId)))
        Http4sAuth.Document.Read.on(DocumentId(input.documentId)).isAllowed
      }
      resp <- Ok(CheckAccessResponse(allowed))
    } yield resp
  }

  override def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(routes.orNotFound)
      .build
      .useForever
}
