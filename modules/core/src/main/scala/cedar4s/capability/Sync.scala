package cedar4s.capability

/** Type class for effect types that can handle synchronous blocking operations.
  *
  * Sync extends MonadError to add capabilities for:
  *   - Delaying side effects
  *   - Executing blocking operations safely
  *
  * This is used primarily for wrapping blocking cedar-java library calls.
  *
  * ==Relationship to cats-effect==
  *
  * This is a minimal version of cats.effect.Sync. Applications using cats-effect can create a bridge:
  *
  * {{{
  * import cats.effect.Sync as CatsSync
  *
  * given syncFromCats[F[_]](implicit S: CatsSync[F]): cedar4s.capability.Sync[F] with {
  *   def pure[A](a: A): F[A] = S.pure(a)
  *   def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = S.flatMap(fa)(f)
  *   def raiseError[A](e: Throwable): F[A] = S.raiseError(e)
  *   def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = S.handleErrorWith(fa)(f)
  *   def delay[A](thunk: => A): F[A] = S.delay(thunk)
  *   def blocking[A](thunk: => A): F[A] = S.blocking(thunk)
  * }
  * }}}
  *
  * @tparam F
  *   The effect type
  */
trait Sync[F[_]] extends MonadError[F] {

  /** Defer a side-effecting computation.
    *
    * The computation is captured but not executed until the F is run.
    *
    * @param thunk
    *   The computation to defer
    * @return
    *   The computation wrapped in F
    */
  def delay[A](thunk: => A): F[A]

  /** Execute a blocking operation.
    *
    * This hints to the runtime that the operation will block a thread, allowing it to be shifted to a
    * blocking-optimized thread pool if needed.
    *
    * For effect types that don't distinguish blocking (like Future), this behaves identically to delay.
    *
    * @param thunk
    *   The blocking computation
    * @return
    *   The result wrapped in F
    */
  def blocking[A](thunk: => A): F[A]

  /** Suspend an already-constructed effect.
    *
    * @param fa
    *   The effect to suspend
    * @return
    *   The suspended effect
    */
  def defer[A](fa: => F[A]): F[A] =
    flatMap(delay(()))(_ => fa)
}

object Sync {

  /** Summon a Sync instance from implicit scope */
  def apply[F[_]](implicit ev: Sync[F]): Sync[F] = ev

  /** Builder for creating Sync instances without Scala 3 polymorphic function types.
    */
  trait Builder[F[_]] {
    def pure[A](a: A): F[A]
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
    def raiseError[A](e: Throwable): F[A]
    def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A]
    def delay[A](thunk: => A): F[A]
    def blocking[A](thunk: => A): F[A]
  }

  /** Create a Sync instance from a builder.
    */
  def instance[F[_]](builder: Builder[F]): Sync[F] = new Sync[F] {
    def pure[A](a: A): F[A] = builder.pure(a)
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = builder.flatMap(fa)(f)
    def raiseError[A](e: Throwable): F[A] = builder.raiseError(e)
    def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = builder.handleErrorWith(fa)(f)
    def delay[A](thunk: => A): F[A] = builder.delay(thunk)
    def blocking[A](thunk: => A): F[A] = builder.blocking(thunk)
  }
}
