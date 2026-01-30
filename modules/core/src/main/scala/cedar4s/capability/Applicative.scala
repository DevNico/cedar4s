package cedar4s.capability

/** Minimal applicative abstraction for parallel/independent operations.
  *
  * This is used for batch fetching where operations can run in parallel.
  */
trait Applicative[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]

  def map2[A, B, C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C]

  override def map[A, B](fa: F[A])(f: A => B): F[B] =
    map2(fa, pure(()))((a, _) => f(a))

  /** Traverse a sequence, collecting results */
  def traverse[A, B](as: Seq[A])(f: A => F[B]): F[Seq[B]] =
    as.foldRight(pure(Seq.empty[B])) { (a, acc) =>
      map2(f(a), acc)(_ +: _)
    }

  /** Sequence a collection of effects */
  def sequence[A](fas: Seq[F[A]]): F[Seq[A]] =
    traverse(fas)(identity)
}

object Applicative {
  def apply[F[_]](implicit ev: Applicative[F]): Applicative[F] = ev
}
