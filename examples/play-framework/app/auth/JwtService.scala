package auth

import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import play.api.Configuration

import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

@Singleton
class JwtService @Inject() (config: Configuration) {
  private val secret = config.get[String]("jwt.secret")
  private val issuer = config.get[String]("jwt.issuer")
  private val audience = config.get[String]("jwt.audience")
  private val ttlSeconds = config.get[Long]("jwt.ttlSeconds")

  def issue(userId: String): String = {
    val now = java.time.Instant.now().getEpochSecond
    val claim = JwtClaim(
      subject = Some(userId),
      issuer = Some(issuer),
      audience = Some(Set(audience)),
      issuedAt = Some(now),
      expiration = Some(now + ttlSeconds)
    )
    Jwt.encode(claim, secret, JwtAlgorithm.HS256)
  }

  def verify(token: String): Either[String, JwtClaim] = {
    Jwt.decode(token, secret, Seq(JwtAlgorithm.HS256)) match {
      case Success(claim) =>
        val issuerOk = claim.issuer.contains(issuer)
        // jwt-scala 10.x may not populate claim.audience on decode;
        // fall back to checking the raw content JSON
        val audienceOk = claim.audience.exists(_.contains(audience)) ||
          claim.content.contains(s""""aud":"$audience"""")
        if (issuerOk && audienceOk) Right(claim) else Left("invalid-issuer-or-audience")
      case Failure(_) => Left("invalid-token")
    }
  }
}
