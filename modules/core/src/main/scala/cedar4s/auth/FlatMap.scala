package cedar4s.auth

/** Minimal FlatMap typeclass for effect sequencing.
  *
  * This avoids depending on cats-core while still allowing effect composition. Applications can provide instances for
  * their effect types, or use the provided instance for scala.concurrent.Future.
  *
  * ==Why Not Use Cats?==
  *
  * cedar4s-core is designed to be dependency-light. By defining our own minimal FlatMap, we avoid requiring cats-core
  * on the classpath. Applications that already use cats can easily create a bridge:
  *
  * {{{
  * import cats.Monad
  *
  * given flatMapFromCats[F[_]](implicit M: Monad[F]): FlatMap[F] with {
  *   def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = M.flatMap(fa)(f)
  *   def map[A, B](fa: F[A])(f: A => B): F[B] = M.map(fa)(f)
  *   def pure[A](a: A): F[A] = M.pure(a)
  * }
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * import scala.concurrent.ExecutionContext.Implicits.global
  *
  * given FlatMap[Future] = FlatMap.futureInstance
  *
  * val deferred = Document.View.on("doc-1")
  * deferred.require  // Uses FlatMap to sequence resolve → authorize
  * }}}
  *
  * @tparam F
  *   The effect type
  */
trait FlatMap[F[_]] {

  /** Sequentially compose two effects */
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]

  /** Transform the value inside the effect */
  def map[A, B](fa: F[A])(f: A => B): F[B]

  /** Lift a pure value into the effect */
  def pure[A](a: A): F[A]

  /** Lift a Future into the effect type.
    *
    * This is needed because EntityStore operations return Future, and we need to bridge into the user's effect type F.
    */
  def liftFuture[A](future: scala.concurrent.Future[A]): F[A]

  /** Sequence two effects, discarding the first result */
  def productR[A, B](fa: F[A])(fb: F[B]): F[B] =
    flatMap(fa)(_ => fb)

  /** Sequence two effects, discarding the second result */
  def productL[A, B](fa: F[A])(fb: F[B]): F[A] =
    flatMap(fa)(a => map(fb)(_ => a))
}

object FlatMap {

  /** Summon a FlatMap instance from implicit scope */
  def apply[F[_]](implicit ev: FlatMap[F]): FlatMap[F] = ev

  /** Instance for scala.concurrent.Future.
    *
    * Requires an ExecutionContext in implicit scope.
    */
  def futureInstance(implicit ec: scala.concurrent.ExecutionContext): FlatMap[scala.concurrent.Future] =
    new FlatMap[scala.concurrent.Future] {
      def flatMap[A, B](fa: scala.concurrent.Future[A])(
          f: A => scala.concurrent.Future[B]
      ): scala.concurrent.Future[B] =
        fa.flatMap(f)
      def map[A, B](fa: scala.concurrent.Future[A])(f: A => B): scala.concurrent.Future[B] =
        fa.map(f)
      def pure[A](a: A): scala.concurrent.Future[A] =
        scala.concurrent.Future.successful(a)
      def liftFuture[A](future: scala.concurrent.Future[A]): scala.concurrent.Future[A] =
        future // Identity for Future
    }

  /** Builder for creating FlatMap instances without Scala 3 polymorphic function types.
    */
  trait Builder[F[_]] {
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
    def map[A, B](fa: F[A])(f: A => B): F[B]
    def pure[A](a: A): F[A]
    def liftFuture[A](future: scala.concurrent.Future[A]): F[A]
  }

  /** Create a FlatMap instance from a builder.
    */
  def instance[F[_]](builder: Builder[F]): FlatMap[F] = new FlatMap[F] {
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = builder.flatMap(fa)(f)
    def map[A, B](fa: F[A])(f: A => B): F[B] = builder.map(fa)(f)
    def pure[A](a: A): F[A] = builder.pure(a)
    def liftFuture[A](future: scala.concurrent.Future[A]): F[A] = builder.liftFuture(future)
  }
}
