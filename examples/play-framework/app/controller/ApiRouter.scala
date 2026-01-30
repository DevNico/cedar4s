package controller

import com.typesafe.config.Config
import de.innfactory.smithy4play.AutoRouter
import play.api.Application
import play.api.mvc.ControllerComponents

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ApiRouter @Inject() (implicit
    cc: ControllerComponents,
    app: Application,
    ec: ExecutionContext,
    config: Config
) extends AutoRouter
