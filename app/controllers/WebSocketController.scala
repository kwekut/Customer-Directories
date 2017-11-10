package controllers


import scala.concurrent.{ Future, ExecutionContext }
import com.mohiva.play.silhouette.api.{ Environment, LogoutEvent, Silhouette }
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
//import com.mohiva.play.silhouette.api.services.AuthInfoService
import models.services.UserService
import services.iplocator.{ IPLocateService, IPLocateImpl }
import models.User
import play.api.Logger
import play.api.i18n.MessagesApi
import javax.inject.Inject
import com.google.inject.name.Named
import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import akka.stream.Materializer
import play.api.libs.streams._
import java.util.UUID
import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import actors.WebSocketActor
import actors.CommunicateActor._
import actors.SparkActor._
import actors.ElasticActor._
import utils.{ DefaultEnv }
import models.daos._
import models.mail._
import models._
import java.lang.Runtime

class WebSocketController @Inject()  (cc: ControllerComponents,
					  val env: Silhouette[DefaultEnv],
					  val userService: UserService,
					  val authenticatorService: NewAuthenticatorService[JWTAuthenticator],
            val ipService: IPLocateService,
                @Named("error-actor") errorActor: ActorRef,
							  @Named("process-actor") prossActor: ActorRef,
							  @Named("spark-actor") sparkActor: ActorRef,
                @Named("elastic-actor") elasticActor: ActorRef,
                @Named("communicate-actor") commActor: ActorRef)
  							(implicit system: ActorSystem, val mat: Materializer, implicit val ec: ExecutionContext)	extends AbstractController(cc) {

  def socket(token: String) = WebSocket.acceptOrResult[JsValue, JsValue] { request =>
    val ip = request.remoteAddress.toString()
    //implicit val req = Request(request, AnyContentAsText(token))
    authenticatorService.retrieve(token) flatMap {
      case None => Future(Left(Forbidden))
      case Some(auth) => 
          userService.retrieve(auth.loginInfo).map {
            case Some(user) =>
                  Right(ActorFlow.actorRef(WebSocketActor.props(user, ip, ipService, commActor, prossActor, sparkActor, elasticActor, errorActor) _))
            case None => Left(Forbidden)
          }
           
    }
  }

}  
