package cedar4s.capability

/** Minimal functor abstraction for mapping over effect types.
  *
  * This is cedar4s's own typeclass to avoid depending on cats in the core module. Interop with cats is provided via
  * implicit conversions.
  */
trait Functor[F[_]] {
  def map[A, B](fa: F[A])(f: A => B): F[B]
}

object Functor {
  def apply[F[_]](implicit ev: Functor[F]): Functor[F] = ev

  /** Extension methods for Functor */
  implicit class FunctorOps[F[_], A](private val fa: F[A]) extends AnyVal {
    def map[B](f: A => B)(implicit F: Functor[F]): F[B] = F.map(fa)(f)
  }
}
