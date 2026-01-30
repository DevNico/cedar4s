package cedar4s.capability

/** Type class for effect types that support concurrent operations.
  *
  * Concurrent extends Sync to add capabilities for:
  *   - Starting computations concurrently (start, background)
  *   - Racing multiple computations
  *   - Asynchronous callbacks
  *
  * This is used for concurrent entity batching and parallel operations.
  *
  * ==Relationship to cats-effect==
  *
  * This is a simplified version of cats.effect.Concurrent. Applications using cats-effect can create a bridge:
  *
  * {{{
  * import cats.effect.{Concurrent as CatsConcurrent, Fiber}
  *
  * given concurrentFromCats[F[_]](implicit C: CatsConcurrent[F]): cedar4s.capability.Concurrent[F] with {
  *   // ... implement methods using C
  *   def start[A](fa: F[A]): F[Fiber[F, A]] = C.start(fa).map(f =>
  *     new Fiber[F, A] {
  *       def cancel: F[Unit] = f.cancel
  *       def join: F[A] = f.joinWithNever
  *     }
  *   )
  * }
  * }}}
  *
  * @tparam F
  *   The effect type
  */
trait Concurrent[F[_]] extends Sync[F] {

  /** A handle to a running computation that can be canceled or joined.
    */
  trait Fiber[A] {

    /** Wait for the fiber to complete and get its result */
    def join: F[A]

    /** Cancel the fiber */
    def cancel: F[Unit]
  }

  /** Start a computation in the background and return a Fiber handle.
    *
    * The computation runs concurrently and the fiber can be joined or canceled.
    *
    * @param fa
    *   The computation to start
    * @return
    *   A fiber handle
    */
  def start[A](fa: F[A]): F[Fiber[A]]

  /** Run a computation in the background and discard the result.
    *
    * Errors are not propagated unless explicitly handled in fa.
    *
    * @param fa
    *   The computation to run
    * @return
    *   Unit effect
    */
  def background[A](fa: F[A]): F[Unit] =
    flatMap(start(fa))(_ => pure(()))

  /** Race two computations, returning the result of whichever completes first.
    *
    * The loser is canceled.
    *
    * @param fa
    *   First computation
    * @param fb
    *   Second computation
    * @return
    *   Either the result of fa (Left) or fb (Right)
    */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]]

  /** Execute multiple computations concurrently and collect results.
    *
    * If any computation fails, all others are canceled.
    *
    * @param fas
    *   The computations to run
    * @return
    *   All results in order
    */
  def parSequence[A](fas: List[F[A]]): F[List[A]] = {
    fas match {
      case Nil        => pure(Nil)
      case fa :: rest =>
        flatMap(start(fa)) { fiber =>
          flatMap(parSequence(rest)) { restResults =>
            map(fiber.join)(a => a :: restResults)
          }
        }
    }
  }

  /** Apply a function to each element concurrently.
    *
    * @param as
    *   The input list
    * @param f
    *   The function to apply
    * @return
    *   The results in order
    */
  def parTraverse[A, B](as: List[A])(f: A => F[B]): F[List[B]] =
    parSequence(as.map(f))
}

object Concurrent {

  /** Summon a Concurrent instance from implicit scope */
  def apply[F[_]](implicit ev: Concurrent[F]): Concurrent[F] = ev
}
