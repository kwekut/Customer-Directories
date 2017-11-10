package controllers

import javax.inject.Inject
import com.google.inject.name.Named
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorRef
import actors.SparkActor._

import scala.concurrent.{ Future, ExecutionContext }
import com.mohiva.play.silhouette.api.{ Environment, LogoutEvent, Silhouette }
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import play.api.mvc.{ Result, RequestHeader }
import play.api._
import play.api.mvc._
import play.api.i18n.MessagesApi
import play.api.i18n.Messages
import play.api.libs.json.Json
import play.api.libs.json._
import play.api.libs.functional.syntax._
import utils.{ DefaultEnv }
import models.mail._
import models._
import java.lang.Runtime
import scala.concurrent.{ Future, ExecutionContext }

class ApplicationController @Inject() (cc:ControllerComponents,
                val env: Silhouette[DefaultEnv],
                @Named("kafkaconsumer-actor") consActor: ActorRef,
                @Named("kafkaproducer-actor") prodActor: ActorRef,
                @Named("process-actor") prossActor: ActorRef,
                @Named("spark-actor") sparkActor: ActorRef,
                @Named("communicate-actor") commActor: ActorRef)
                (implicit val ec: ExecutionContext) extends AbstractController(cc) {

  implicit lazy val timeout: Timeout = 10.seconds

  def index = Action {  Ok(views.html.index())  }

// check all actors, weakest actors response time determines the response time
  def health = Action.async { implicit request =>
    val spark: Future[String] = ask(sparkActor, HealthCheck).mapTo[String] 
    val comm: Future[String] = ask(commActor, HealthCheck).mapTo[String]
    val pross: Future[String] = ask(prossActor, HealthCheck).mapTo[String]
    val prod: Future[String] = ask(prodActor, HealthCheck).mapTo[String]
    val cons: Future[String] = ask(consActor, HealthCheck).mapTo[String]
	val reply: Future[Option[String]] = (
		for {
				sp <- spark
				cm <- comm
				ps <- pross
				pd <- prod
				cs <- cons
			} yield Some(sp + cm + ps + pd + cs)
	)
    reply flatMap {
      case Some(x) => Future.successful(Ok(x))
      case None => Future.successful(InternalServerError("Oops"))
    }
  }

}

