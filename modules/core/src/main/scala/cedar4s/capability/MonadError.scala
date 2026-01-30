package cedar4s.capability

/** Monad with error handling capabilities.
  *
  * This is the primary effect capability required for authorization operations, which need to handle authorization
  * failures.
  */
trait MonadError[F[_]] extends Monad[F] {
  def raiseError[A](e: Throwable): F[A]

  def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A]

  /** Lift an Either into the effect */
  def fromEither[A](either: Either[Throwable, A]): F[A] =
    either match {
      case Right(a) => pure(a)
      case Left(e)  => raiseError(e)
    }

  /** Attempt an effect, catching errors into Either */
  def attempt[A](fa: F[A]): F[Either[Throwable, A]] =
    handleErrorWith(map(fa)(Right(_): Either[Throwable, A]))(e => pure(Left(e)))
}

object MonadError {
  def apply[F[_]](implicit ev: MonadError[F]): MonadError[F] = ev
}
