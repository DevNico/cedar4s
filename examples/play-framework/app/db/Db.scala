package db

import play.api.inject.ApplicationLifecycle
import slick.basic.DatabaseConfig
import slick.jdbc.H2Profile

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Db @Inject() (lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {
  private val config = DatabaseConfig.forConfig[H2Profile]("db")
  val profile: H2Profile = config.profile
  val db: H2Profile#Backend#Database = config.db

  lifecycle.addStopHook(() => Future(db.close()))
}
