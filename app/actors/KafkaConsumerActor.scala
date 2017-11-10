package actors

import akka.actor._
import javax.inject._
import com.google.inject.name.Named
import play.api.libs.json._
import java.util.UUID
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.LocalDateTime
import play.api.Logger
import java.util.Properties
import akka.actor.{Props, Actor}
import akka.actor.{ActorKilledException, ActorInitializationException}
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Try
import scala.util.{Success, Failure}
import akka.actor.SupervisorStrategy._
import org.apache.kafka.clients.consumer._
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import akka.routing._
import com.typesafe.config.ConfigFactory
import akka.pattern._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.concurrent.{Future,Await}
import actors.CommunicateActor._
import actors.ProcessActor._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import services.kafkas.{Consumer, Producer}
import services.kafkas._
import models.mail._
import models.Locality._
import models._
import java.lang.Runtime
import play.api.Play.current
import scala.language.postfixOps

object KafkaConsumerActor {
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
}

class KafkaConsumerActor @Inject() ( 
          @Named("error-actor") errorActor: ActorRef,
          @Named("communicate-actor") commActor: ActorRef)
          (implicit val ec: ExecutionContext) extends Actor {
    import KafkaConsumerActor._
    import KafkaReciever._
    import KconFig._
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

   val recActor: ActorRef = context.actorOf(BalancingPool(initialsize).props(KafkaReciever.props(commActor, errorActor)),"kafkaconsumerrouter")

    def receive = {
        case x => 
    }
  
}


object KafkaReciever {
  def props(commActor: ActorRef, errorActor: ActorRef): Props = Props(new KafkaReciever(commActor, errorActor))
  case class Load( msg: String )
}
class KafkaReciever(commActor: ActorRef, errorActor: ActorRef, testactor: Option[ActorRef] = None) extends Actor {
  import KafkaReciever._
  import KconFig._
  import CommunicateActor._
  import KafkaConsumerActor._
  implicit val ec = context.dispatcher
  val system = akka.actor.ActorSystem("system")
  val consumer = new KafkaConsumer(Consumer.props) 

  //If message causes a restart triggering exception, Warn user, Inform Administrator
  override def preRestart(reason: Throwable, message: Option[Any]) {
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
    message match {
      case Some(m) => errorActor ! (Announcer("ActorRestartException", reason.getMessage, m.toString, s"line $LINE of file $FILE", date))
      case None => errorActor ! (Announcer("ActorRestartException", reason.getMessage, "none", s"line $LINE of file $FILE", date)) 
    }
  }

  //Subscibe to app instance's dedicated topic. Each app has its own topic
  override def preStart() {
    val topiks = AppName.appname.split(",").toList.asJava
    consumer.subscribe(topiks)
  }

  def receive = {

    case Load(message) => 
      Json.parse(message).validate[KafkaMessage] match {
        case msg: JsSuccess[KafkaMessage] => 
          Logger.info("KafkaReciever got something via kafka:")
          Try(msg.get.ids map { id => 
            commActor ! Target(id, Json.toJson(msg.get.msg)) 
          }) match {
              case Success(lines) => //send deeplink to firebase via persistapp for deeplink phone notification
              case Failure(ex) => Logger.info("KafkaReciever: Incomming MSG, parse Failure - KafkaConsChildActor Unparsable message:" + Try(message.toString).toOption.toString +"=>"+ ex.getMessage) 
          }
        case e: JsError => { Logger.info("KafkaReciever: Incomming MSG, parse Failure - KafkaConsChildActor Unparsable message:" + Try(message.toString).toOption.toString +"=>"+ JsError.toJson(e).toString)}
      }

    case HealthCheck => 
      val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        if (breakerIsOpen){} else {
          sender ! ("ChildKafkaConsumerActor" + "=" + date + ":")
        }

    case x => errorActor ! (Talker(x.toString))

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
  // Consume topics for this app instance(val apptopic)
  system.scheduler.schedule(initialdelay milliseconds, interval milliseconds) {
    breaker.withCircuitBreaker(
      Await.ready(
        Future{
              val stream = consumer.poll(0)
              for {rec <- stream.asScala} yield self ! Load(new String(rec.value(), "UTF-8"))
        }, interval milliseconds
      )
    )
  }

}

  
object KconFig {
    val system = akka.actor.ActorSystem("system")
    val c = ConfigFactory.load()
    c.checkValid(ConfigFactory.defaultReference(), "kafkaconsumeractor")
    
    val initialsize = c.getInt("kafkaconsumeractor.startingRouteeNumber")
    val withintimerange = c.getDuration("kafkaconsumeractor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("kafkaconsumeractor.supervisorStrategy.maxNrOfRetries")  
    val maxfailures = c.getInt("kafkaconsumeractor.breaker.maxFailures")
    val calltimeout = c.getDuration("kafkaconsumeractor.breaker.callTimeout", TimeUnit.MILLISECONDS)
    val resettimeout = c.getDuration("kafkaconsumeractor.breaker.resetTimeout", TimeUnit.MILLISECONDS)
    val initialdelay = c.getDuration("kafkaconsumeractor.scheduler.initialDelay", TimeUnit.MILLISECONDS) 
    val interval =  c.getDuration("kafkaconsumeractor.scheduler.interval", TimeUnit.MILLISECONDS)

  var breakerIsOpen = false
}

