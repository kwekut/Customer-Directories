package actors

import javax.inject._
import play.api.libs.ws._
import scala.concurrent.{ Future, ExecutionContext }
import javax.inject._
import play.api.inject.ApplicationLifecycle
import akka.actor.{ ActorRef, ActorSystem, Props, Actor }
import akka.actor.{ActorKilledException, ActorInitializationException}
import javax.inject._
import com.google.inject.name.Named
import java.util.UUID
import org.joda.time.{LocalTime,DateTime,LocalDateTime}
import org.joda.time.format.{DateTimeFormat,DateTimeFormatter}
import play.api.Logger

import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.util.{Try, Success, Failure}
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import akka.routing._
import com.typesafe.config.ConfigFactory
import akka.pattern._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.concurrent.{ Future, ExecutionContext }
import java.lang.Runtime
import play.api.Play.current
import scala.language.postfixOps
//import play.api.http.HttpEntity
//import akka.stream.ActorMaterializer
//import akka.stream.scaladsl._
import akka.util.ByteString
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
//import ai.x.play.json.Jsonx
import models.PartialFunctions._
import models.daos._
import models.mail._
import models.Locality._
import models._

object NotificationConfig {
    val c = ConfigFactory.load()
    c.checkValid(ConfigFactory.defaultReference(), "notificationactor")
	val initialsize = c.getInt("notificationactor.startingRouteeNumber")
    val withintimerange = c.getDuration("notificationactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("notificationactor.supervisorStrategy.maxNrOfRetries") 
    val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z") 

    val icon = c.getString("firebase.icon")
    //val clickaction = c.getString("firebase.clickaction")
    val volume = c.getString("firebase.volume")
    val serverkey = c.getString("firebase.serverkey")
    val url = c.getString("firebase.url") 
    //val timetolive = c.getInt("firebase.timetolive")
    //val priority = c.getString("firebase.priority")
}

class NotificationActor @Inject() (
					@Named("error-actor") errorActor: ActorRef,
					ws: WSClient, userDAO: UserDAO)
					(implicit val ec: ExecutionContext) extends Actor  {

	import NotificationConfig._
	var prompt = 0

    override val supervisorStrategy = {
      val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      OneForOneStrategy(maxNrOfRetries = maxnrofretries, withinTimeRange = withintimerange milliseconds) {
        case aIE: ActorInitializationException => errorActor ! (Announcer("ActorInitializationException", aIE.getMessage, "none", s"line $LINE of file $FILE", date)); Stop 
        case aKE: ActorKilledException => errorActor ! (Announcer("ActorKilledException", aKE.getMessage, "none", s"line $LINE of file $FILE", date)); Stop
        case uE: Exception if prompt < 4 => prompt + 1
          errorActor ! (Announcer("ActorException", uE.getMessage, "none", s"line $LINE of file $FILE", date)) ;Restart
      }
    }
    val process: ActorRef = context.actorOf(
      BalancingPool(initialsize).props(AlertActor.props(ws, userDAO, errorActor)), "alertactorrouter")  
    
    def receive = {
        case x => process forward x
    }

}

object AlertActor {
  def props(ws: WSClient, userDAO: UserDAO, errorActor: ActorRef): Props = Props(new AlertActor(ws, userDAO, errorActor))
}

class AlertActor (ws: WSClient, userDAO: UserDAO, errorActor: ActorRef) extends Actor  {
  import NotificationConfig._
  implicit val ec = context.dispatcher
  //If message causes a restart triggering exception, Warn user, Inform Administrator
  override def preRestart(reason: Throwable, message: Option[Any]) {
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
    message match {
      case Some(m) => errorActor ! (Announcer("ActorRestartException", reason.getMessage, m.toString, s"line $LINE of file $FILE", date))
      case None => errorActor ! (Announcer("ActorRestartException", reason.getMessage, "none", s"line $LINE of file $FILE", date)) 
    }
  }

    def receive = {
        
// Log the message ID value for all messages, in case you need to troubleshoot
// If message_id is set, check for registration_id
//If registration_id is set, replace the original ID with the new value (canonical ID) in your server 
 //On failure
//If it is Unavailable, you could retry to send it in another request.
//If it is NotRegistered, you should remove the registration ID from your server database because the application was uninstalled from the device

//https://firebase.google.com/docs/cloud-messaging/http-server-ref#send-downstream      
//https://firebase.google.com/docs/cloud-messaging/server 
      case SendNotification(userid, body, title, priority, timetolive, clickaction) => 
    	val id = UUID.randomUUID().toString
    	val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
		userDAO.find(userid) map {
		case Some(user) =>
		    user.alerttoken.toList match {
		    case x: List[String] => 
		    x map (to => {  
			val notification = Notification(icon, title, body, clickaction)
			val alert = PushNotification(to, priority, timetolive, notification)
			val prime = Json.toJson(alert)
		    val complexResponse: Future[WSResponse] = ws.url(url)
		    	.withHeaders("Content-Type" -> "application/json", "Authorization" -> "key=${serverkey}")
		    	.withRequestTimeout(10000.millis)
		    	.post(prime)
		    	complexResponse map { response => 
		    		if(response.status == 200){
						response.json.validate[FireHead] match {
	  						case s: JsSuccess[FireHead] => s map {firebasetop=>
	  							// firebasetop.multicast_id,firebasetop.success
	  							// firebasetop.failure,firebasetop.canonical_ids
	  						}
						  	case e: JsError =>  
						  		errorActor ! (Reporter("FirebaseConnectionError", JsError.toJson(e).toString(), s"line $LINE of file $FILE", date))
						}
						parseFirebaseJson(response.json) map( jrs =>
						jrs match {
	  						case s: JsSuccess[FireResult] => s map {firebaseresult:FireResult =>
	  							if(firebaseresult.message_id.isDefined) {
	  								firebaseresult.registration_id map {x=>
	  									userDAO.save(user.copy(alerttoken = user.alerttoken ++ Set(x) )) 
	  								}
	  							} else {
	  								firebaseresult.error map {error=>
	  									errorActor ! (Reporter("FirebaseMessageError", error, s"line $LINE of file $FILE", date))  
	  								}
	  							}
	  						}
						  	case e: JsError => 
						  		errorActor ! (Reporter("FirebaseConnectionError", JsError.toJson(e).toString(), s"line $LINE of file $FILE", date))  
						  	
						})
		    		} else if(response.status == 400) {
		                errorActor ! (Reporter("FirebaseConnectionError", "400 - Request could not be parsed as JSON", s"line $LINE of file $FILE", date)) 
		    		} else if(response.status == 401) {
		    			self ! CheckServerKey
		                errorActor ! (Reporter("FirebaseConnectionError", "401 - There was an error authenticating the fcm account.", s"line $LINE of file $FILE", date)) 
		    		} else if(response.status >= 500) {
		                errorActor ! (Reporter("FirebaseConnectionError", "500 - Internal error in the FCM connection server, or the server may be temporarily unavailable.", s"line $LINE of file $FILE", date)) 
		    		}
		    	}
		    })
		    case List() =>
		    	errorActor ! (Reporter("UserTokenError", s"User $user.username has no firebase token", s"line $LINE of file $FILE", date))
			}
  	    case None => 
            errorActor ! (Reporter("UserDAOError", "No user account found", s"line $LINE of file $FILE", date))          
		} 

      case CheckServerKey => 
    	val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        val complexResponse: Future[WSResponse] = ws.url(url)
            .withHeaders("Content-Type" -> "application/json", "Authorization" -> s"key=${serverkey}")
            .withRequestTimeout(10000.millis)
            .post(s"{registration_ids: [$serverkey]}")
		complexResponse map { response => 
		    if(response.status == 401){
				errorActor ! (Reporter("FirebaseConnectionError", "401 - Your Server key is not valid", s"line $LINE of file $FILE", date))
		    }
		}

	  case x => errorActor ! (Talker(x.toString))

    }

	    private def parseFirebaseJson(json: JsValue) = {
	  		val results = (json \ "results").as[List[JsObject]]
	  		results map { result => result.validate[FireResult] }
		}
}

// {
//     "multicast_id":7728707543791363238,
//     "success":4,
//     "failure":0,
//     "canonical_ids":0,
//     "results":[
//         {"message_id":"0:1470905757075944%4d9f3641f9fd7ecd", "registration_id":"", "error":""},
//         {"message_id":"0:1470905757075946%4d9f3641f9fd7ecd"},
//         {"message_id":"0:1470905757075383%4d9f3641f9fd7ecd"},
//         {"message_id":"0:1470905757075948%4d9f3641f9fd7ecd"} ]
// }

// case class FireHead(multicast_id: Option[String], success: Option[String], failure: Option[String], canonical_ids: Option[String])
// complexRequest map { response =>
//     FireHead(	(response \ "multicast_id").asOpt[String]
//     			(response \ "success").asOpt[String]
//     			(response \ "failure").asOpt[String]
//     			(response \ "canonical_ids").asOpt[String] )
// }


// case class FireResult(message_id: Option[String], registration_id: Option[String], error: Option[String])
// futureResponse map { response =>
//     val res: FireResult = parsefirebaseJson(response.json)
//   }
// private def parsefirebaseJson(json: JsValue): String = {
//   	val results = (json \ "results").as[List[JsObject]]
//   	results map {result =>
// 	  	FireResult( (result \ "message_id").asOpt[String],
// 	  				(result \ "registration_id").asOpt[String],
// 	  				(result \ "error").asOpt[String] )
//   	}
  	
//   }



// //Sender ID = A unique numerical value created when you create your Firebase project,
// //Server key = A server key that authorizes your app server for access to Google services
// //Registration token = An ID generated by the FCM SDK for each client app instance
// //to = To send messages to specific devices, set the to key to the registration token for the specific app instance.

// //Authorization: key=YOUR_SERVER_KEY = Make sure this is the server key, whose value is available in your Firebase project console under Project Settings > Cloud Messaging
// ///////////////////////////Post///////////////////////
// https://fcm.googleapis.com/fcm/send
// Content-Type:application/json
// Authorization:key=AIzaSyZ-1u...0GBYzPu7Udno5aA
// {
//   "to" : "bk3RNwTe3H0:CI2k_HHwgIpoDKCIZvvDMExUdFQ3P1...",
//   "priority" : "normal",
//   "notification" : {
//     "body" : "This week's edition is now available.",
//     "title" : "NewsMagazine.com",
//     "icon" : "new"
//   },
//   "data" : {
//     "volume" : "3.21.15",
//     "contents" : "http://www.news-magazine.com/world-week/21659772"
//   },
//   "time_to_live" : 3,
//   //"collapse_key": "score_update"
// }
// ///////////////////////////////////////////////////
// //https://firebase.google.com/docs/cloud-messaging/http-server-ref#send-downstream
// Downstream HTTP messages (JSON)
// //Target fields
// to: Optional, string
// registration_ids: String array
// condition: Optional, string
// collapse_key: Optional, string
// priority: Optional, string
// //Payload fields
// data: Optional, JSON object
// //////////////////////////////
// //IOS Notification fields
// title: Optional, string
// body: Optional, string
// sound: Optional, string
// badge: Optional, string
// click_action: Optional, string
// body_loc_key: Optional, string
// body_loc_args: Optional, JSON array as string
// title_loc_key: Optional, JSON array as string
// title_loc_args: Optional, JSON array as string
// //Android Notification fields
// title: Optional, string
// body: Optional, string
// icon: Optional, string
// sound: Optional, string
// tag: Optional, string
// color: Optional, string
// click_action: Optional, string
// body_loc_key: Optional, string
// body_loc_args: Optional, JSON array as string
// title_loc_key: Optional, JSON array as string
// title_loc_args: Optional, JSON array as string


// //Response
// multicast_id:Required number identifying the multicast message.
// success:Required, number of messages that were processed without an error.
// failure:Required, number of messages that could not be processed.
// canonical_ids:Required, number of results that contain a canonical registration token. See the registration overview for more discussion of this topic.
// results:Optional, array of objects representing the status of the messages processed. The objects are listed in the same order as the request (i.e., for each registration ID in the request, its result is listed in the same index in the response).
// message_id:String specifying a unique ID for each successfully processed message.
// registration_id:Optional string specifying the canonical registration token for the client app that the message was processed and sent to. Sender should use this value as the registration token for future requests. Otherwise, the messages might be rejected.
// error:String specifying the error that occurred when processing the message for the recipient. The possible values can be found in table 9.

// case class NotResponse(multicast_id: Int, success: Int, failure: Int, canonical_ids: Int, 
// 	results: Seq[JsObject])
// case class Results(message_id: String, registration_id: Option[String], error: String)
// badge: Optional, string