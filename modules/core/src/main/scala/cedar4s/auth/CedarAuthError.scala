package cedar4s.auth

/** Authorization error types for Cedar.
  *
  * These are generic error types that can be used by any application using cedar4s for authorization.
  */
sealed trait CedarAuthError extends Throwable {
  def message: String
  override def getMessage: String = message
}

object CedarAuthError {

  /** User lacks required permission for the action */
  final case class Unauthorized(
      message: String,
      denyReason: Option[String] = None
  ) extends CedarAuthError

  /** No valid authentication context available */
  final case class Unauthenticated(
      message: String
  ) extends CedarAuthError

  /** Internal error during authorization check */
  final case class AuthorizationFailed(
      message: String,
      cause: Option[Throwable] = None
  ) extends CedarAuthError
}
