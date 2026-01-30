package cedar4s.auth

/** Resolves a principal reference into a principal entity.
  *
  * The principal entity type P should extend the generated PrincipalEntity trait from your schema's DSL and have a
  * CedarEntityType instance. The framework will automatically convert P to CedarPrincipal using the typeclass.
  *
  * Example:
  * {{{
  * import example.docshare.cedar.DocShare
  *
  * val resolver = new PrincipalResolver[Future, DocShare.Entity.User] {
  *   def resolve(principal: Principal): Future[Option[DocShare.Entity.User]] =
  *     principal match {
  *       case DocShare.Principal.User(userId) =>
  *         // Fetch user from database
  *         userRepo.find(userId.value).map { userData =>
  *           Some(DocShare.Entity.User(
  *             id = userId,
  *             email = userData.email,
  *             name = userData.name
  *           ))
  *         }
  *       case _ => Future.successful(None)
  *     }
  * }
  * }}}
  *
  * @tparam F
  *   Effect type (e.g., Future, IO)
  * @tparam P
  *   Principal entity type (must have CedarEntityType instance)
  */
trait PrincipalResolver[F[_], P] {

  /** Resolve a principal reference into a principal entity.
    *
    * Return Some(principal entity) if the principal exists and can be loaded, or None if the principal doesn't exist or
    * cannot be resolved.
    *
    * @param principal
    *   The principal reference from the authorization check
    * @return
    *   Effect containing optional principal entity
    */
  def resolve(principal: Principal): F[Option[P]]
}

object PrincipalResolver {
  def apply[F[_], P](implicit resolver: PrincipalResolver[F, P]): PrincipalResolver[F, P] = resolver
}
