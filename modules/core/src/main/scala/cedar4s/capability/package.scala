package cedar4s

/** Effect capability typeclasses for cedar4s.
  *
  * These minimal typeclasses allow cedar4s to work with any effect type (Future, IO, etc.) without depending on
  * specific effect libraries.
  *
  * ==Usage==
  *
  * {{{
  * import cedar4s.capability._
  * import cedar4s.capability.instances._ // for Future instances
  *
  * // Or with cats-effect:
  * // import cedar4s.interop.cats._ // bridges cats Sync/Concurrent to cedar4s
  * }}}
  */
package object capability {
  // Re-export instances for convenience (Scala 2-compatible forwarding)
  implicit def futureMonadError(implicit ec: scala.concurrent.ExecutionContext): MonadError[scala.concurrent.Future] =
    instances.futureMonadError

  implicit def futureSync(implicit ec: scala.concurrent.ExecutionContext): Sync[scala.concurrent.Future] =
    instances.futureSync

  implicit def futureConcurrent(implicit ec: scala.concurrent.ExecutionContext): Concurrent[scala.concurrent.Future] =
    instances.futureConcurrent
}
