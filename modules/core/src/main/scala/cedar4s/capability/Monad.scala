package cedar4s.capability

/** Minimal monad abstraction for sequential operations.
  *
  * This is used when operations must be chained (e.g., fetch entity then fetch parents).
  */
trait Monad[F[_]] extends Applicative[F] {
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]

  override def map[A, B](fa: F[A])(f: A => B): F[B] =
    flatMap(fa)(a => pure(f(a)))

  override def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C] =
    flatMap(fa)(a => map(fb)(b => f(a, b)))
}

object Monad {
  def apply[F[_]](implicit ev: Monad[F]): Monad[F] = ev

  /** Extension methods for Monad */
  implicit class MonadOps[F[_], A](private val fa: F[A]) extends AnyVal {
    def flatMap[B](f: A => F[B])(implicit F: Monad[F]): F[B] = F.flatMap(fa)(f)
  }
}
