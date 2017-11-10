package actors

import akka.actor._
import akka.actor.{ ActorRef, ActorSystem, Props, Actor }
import akka.actor.{ActorKilledException, ActorInitializationException}
import javax.inject._
import com.google.inject.name.Named
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import services.kafkas.{Consumer, Producer}
import services.kafkas._
import java.util.UUID
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.LocalDateTime
import scala.util.Random
import play.api.Logger
import akka.event.LoggingReceive
import scala.concurrent.{Future,ExecutionContext}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{Success, Failure}
import actors.KafkaConsumerActor._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import akka.routing._
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import akka.pattern._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.util.{Try, Success, Failure}
import akka.actor.Actor
import akka.actor.ActorLogging
import scala.concurrent.{ Future, ExecutionContext }
import akka.event.Logging
import play.api.Play.current
import org.apache.kafka.clients.producer.ProducerRecord
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.language.postfixOps
import models.mail._
import models.Locality._
import models._
import java.lang.Runtime

object CommunicateActor{
 trait SerializeMessage {}
  case class Join(user: String, out: ActorRef) extends SerializeMessage
  case class UnJoin(user: String, out: ActorRef) extends SerializeMessage
  case class BroadCast(trgt: JsValue)
  case class Distribute(users: Set[String], trgt: JsValue)
  case class Target(user: String, trgt: JsValue)
  case class UpdateRefs(store: mutable.AnyRefMap[String, ActorRef])
}

class CommunicateActor @Inject() ( 
        @Named("error-actor") errorActor: ActorRef)
        (implicit val ec: ExecutionContext) extends Actor {
  import CommunicateActor._
  import ChildCommunicateActor._
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

    val reader: ActorRef = context.actorOf(BalancingPool(initialsize).props(ChildCommunicateActor.props(errorActor)),"commrouter")  
    
    def receive = {

      case Join(user, out) => add(user, out) 

      case UnJoin(user, out) => remove(user) 

      case x => reader forward x
    }

  def add(user: String, out: ActorRef): Future[String] = Future{
    store += (user -> out)
    user
  }

  def remove(user: String): Future[String] = Future{
    store -= (user)
    user
  } 

}

class ChildCommunicateActor(errorActor: ActorRef) extends Actor {
  import CommunicateActor._
  import ChildCommunicateActor._ 
  val producer = new KafkaProducer(Producer.props)
  implicit val ec = context.dispatcher
  val system = akka.actor.ActorSystem("system")  
  
  //If message causes a restart triggering exception, Warn user, Inform Administrator
  override def preRestart(reason: Throwable, message: Option[Any]) {
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
    message match {
      case Some(m) => errorActor ! (Announcer("ActorRestartException", reason.getMessage, m.toString, s"line $LINE of file $FILE", date))
      case None => errorActor ! (Announcer("ActorRestartException", reason.getMessage, "none", s"line $LINE of file $FILE", date)) 
    }
  }

  val breaker = new CircuitBreaker(system.scheduler,
      maxFailures = maxfailures,
      callTimeout = calltimeout milliseconds,
      resetTimeout = resettimeout milliseconds)
      breaker.onClose({
        breakerIsOpen = false
        // val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        // errorActor ! (Reporter("CircuitBreakerClosed", s"resetTimeout: $resettimeout  callTimeout: $calltimeout  maxFailures: $maxfailures", s"line $LINE of file $FILE", date))
      })
      breaker.onOpen({
        breakerIsOpen = true
        // val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        // errorActor ! (Reporter("CircuitBreakerOpen", s"resetTimeout: $resettimeout  callTimeout: $calltimeout  maxFailures: $maxfailures", s"line $LINE of file $FILE", date)) 
      })
  //Continiously broadcast the Apps latest userlist to all other apps
  // Send KafkaDistribution = (AppName, Set(store ids)) to all apps via topic GRID 
  system.scheduler.schedule(initialdelay milliseconds, interval milliseconds) {
    breaker.withCircuitBreaker( 
    Future{
      val msg = Json.toJson(KafkaDistribution(AppName.appname, store.keys.toSet)).toString
      val key = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      val producerRecord = new ProducerRecord[Array[Byte],Array[Byte]](gridtopic, gridpartition, key.getBytes("UTF8"), msg.getBytes("UTF8"))
      producer.send(producerRecord.asInstanceOf[ProducerRecord[Nothing, Nothing]]) 
    })
  }  

  def receive = {

    case HealthCheck => 
      val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      if (breakerIsOpen){} else {
        sender ! ("ChildCommunicateActor" + "=" + date + ":")
      }

    case Target(user, payload) => target(user, payload)

    case Distribute(users, payload) => distribute(users, payload) 

    case BroadCast(payload) => broadcast(payload)

    case x => errorActor ! (Talker(x.toString))
    
  }

    def findOne(user: String): Option[ActorRef] = {
      store.get(user)
    }

    def findMany(users: Set[String]): Set[ActorRef] = {
     var retVal = 
        for{ a <- users 
             (k, v) <- store if store.contains(a)
            }yield v
      retVal
    }

    def target(user: String, pload: JsValue) = Future{
      store.get(user)map(_ ! pload)
    } 

    def distribute(users: Set[String], pload: JsValue) = Future{
      for (actor <- findMany(users)) actor ! pload
    } 

    def broadcast(pload: JsValue) = Future{
      for ((user, actor) <- store) actor ! pload
    } 

}

object ChildCommunicateActor {
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
  
  var CircuitBreakerState = "Closed"
  var store: mutable.AnyRefMap[String, ActorRef] = mutable.AnyRefMap()

    val c = ConfigFactory.load()
    c.checkValid(ConfigFactory.defaultReference(), "communicateactor")
    
    val initialsize = c.getInt("communicateactor.startingRouteeNumber")
    val withintimerange = c.getDuration("communicateactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("communicateactor.supervisorStrategy.maxNrOfRetries")  
    
    val maxfailures = c.getInt("communicateactor.breaker.maxFailures")
    val calltimeout = c.getDuration("communicateactor.breaker.callTimeout", TimeUnit.MILLISECONDS)
    val resettimeout = c.getDuration("communicateactor.breaker.resetTimeout", TimeUnit.MILLISECONDS)
    val initialdelay = c.getDuration("communicateactor.scheduler.initialDelay", TimeUnit.MILLISECONDS) 
    val interval =  c.getDuration("communicateactor.scheduler.interval", TimeUnit.MILLISECONDS)

  val gridpartition: Integer = c.getInt("kafka.gridpartition")
  val gridtopic = c.getString("kafka.gridtopic")
  
  def props(errorActor: ActorRef): Props = Props(new ChildCommunicateActor(errorActor))
  

  var breakerIsOpen = false
}

