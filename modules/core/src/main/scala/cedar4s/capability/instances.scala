package cedar4s.capability

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try, Success, Failure}

/** Instances for scala.concurrent.Future.
  *
  * These are provided in core so users don't need an additional dependency for common Future-based applications.
  */
object instances {

  /** MonadError instance for Future (requires implicit ExecutionContext) */
  implicit def futureMonadError(implicit ec: ExecutionContext): MonadError[Future] =
    new MonadError[Future] {
      def pure[A](a: A): Future[A] = Future.successful(a)

      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] =
        fa.flatMap(f)

      override def map[A, B](fa: Future[A])(f: A => B): Future[B] =
        fa.map(f)

      override def map2[A, B, C](fa: Future[A], fb: Future[B])(f: (A, B) => C): Future[C] =
        fa.zipWith(fb)(f)

      override def traverse[A, B](as: Seq[A])(f: A => Future[B]): Future[Seq[B]] =
        Future.traverse(as)(f)

      override def sequence[A](fas: Seq[Future[A]]): Future[Seq[A]] =
        Future.sequence(fas)

      def raiseError[A](e: Throwable): Future[A] =
        Future.failed(e)

      def handleErrorWith[A](fa: Future[A])(f: Throwable => Future[A]): Future[A] =
        fa.recoverWith { case e => f(e) }
    }

  /** Sync instance for Future (requires implicit ExecutionContext) */
  implicit def futureSync(implicit ec: ExecutionContext): Sync[Future] =
    new Sync[Future] {
      def pure[A](a: A): Future[A] = Future.successful(a)

      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] =
        fa.flatMap(f)

      override def map[A, B](fa: Future[A])(f: A => B): Future[B] =
        fa.map(f)

      def raiseError[A](e: Throwable): Future[A] =
        Future.failed(e)

      def handleErrorWith[A](fa: Future[A])(f: Throwable => Future[A]): Future[A] =
        fa.recoverWith { case e => f(e) }

      def delay[A](thunk: => A): Future[A] =
        Future(thunk)

      def blocking[A](thunk: => A): Future[A] =
        Future(thunk) // Future doesn't distinguish blocking
    }

  /** Concurrent instance for Future (requires implicit ExecutionContext) */
  implicit def futureConcurrent(implicit ec: ExecutionContext): Concurrent[Future] =
    new Concurrent[Future] {
      def pure[A](a: A): Future[A] = Future.successful(a)

      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] =
        fa.flatMap(f)

      override def map[A, B](fa: Future[A])(f: A => B): Future[B] =
        fa.map(f)

      def raiseError[A](e: Throwable): Future[A] =
        Future.failed(e)

      def handleErrorWith[A](fa: Future[A])(f: Throwable => Future[A]): Future[A] =
        fa.recoverWith { case e => f(e) }

      def delay[A](thunk: => A): Future[A] =
        Future(thunk)

      def blocking[A](thunk: => A): Future[A] =
        Future(thunk)

      def start[A](fa: Future[A]): Future[Fiber[A]] = {
        // Future starts executing immediately, so we just wrap it
        Future.successful(new Fiber[A] {
          def join: Future[A] = fa
          def cancel: Future[Unit] = Future.successful(()) // Can't cancel Future
        })
      }

      def race[A, B](fa: Future[A], fb: Future[B]): Future[Either[A, B]] = {
        val p = Promise[Either[A, B]]()
        fa.onComplete {
          case Success(a) => p.trySuccess(Left(a))
          case Failure(e) => p.tryFailure(e)
        }
        fb.onComplete {
          case Success(b) => p.trySuccess(Right(b))
          case Failure(e) => p.tryFailure(e)
        }
        p.future
      }

      override def parSequence[A](fas: List[Future[A]]): Future[List[A]] =
        Future.sequence(fas)

      override def parTraverse[A, B](as: List[A])(f: A => Future[B]): Future[List[B]] =
        Future.traverse(as)(f)
    }
}
