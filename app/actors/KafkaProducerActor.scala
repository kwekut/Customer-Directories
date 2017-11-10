package actors

import akka.actor._
import javax.inject._
import com.google.inject.name.Named
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import scala.concurrent.{ Future, ExecutionContext }
import actors.ProcessActor._
import akka.event.LoggingReceive
import play.api.Logger
import play.api.Configuration
import java.util.UUID
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.LocalDateTime
import org.apache.kafka.clients.producer.ProducerRecord
import akka.actor.SupervisorStrategy._
import actors.CommunicateActor._
import actors.ProcessActor._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import services.kafkas._
import java.lang.Runtime
import scala.util.{Try, Success, Failure}
import com.typesafe.config.ConfigFactory
import akka.routing._
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Future,Await}
import scala.concurrent.duration._
import akka.pattern._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import play.api.Play.current
import scala.language.postfixOps
import models.mail._
import models.Locality._
import models._
import java.lang.Runtime

object KafkaProducerActor {
  case class Grid( js: String )
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
}

//To-do, Make producer actor persistent
class KafkaProducerActor @Inject() (
      @Named("error-actor") errorActor: ActorRef,
      @Named("preference-actor") prefActor: ActorRef,
      @Named("notification-actor") notificationActor: ActorRef, 
      @Named("spark-actor") sparkActor: ActorRef,
      @Named("communicate-actor") commActor: ActorRef)
      (implicit val ec: ExecutionContext) extends Actor {

  import KafkaProducerActor._
  import KafkaProdConfig._
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

    val pusher: ActorRef = context.actorOf(
      BalancingPool(initialsize).props(KafkaSender.props(notificationActor, prefActor, sparkActor, errorActor)), "kafkaproducerouter")  

      def receive = {
            case x => pusher forward x
      }

}


object KafkaSender {
  def props(notificationActor: ActorRef, prefActor: ActorRef, sparkActor: ActorRef, errorActor: ActorRef, testactor: Option[ActorRef] = None): Props = Props(new KafkaSender(notificationActor, prefActor, sparkActor, errorActor, testactor))
}

class KafkaSender(notificationActor: ActorRef, prefActor: ActorRef, sparkActor: ActorRef, errorActor: ActorRef, testactor: Option[ActorRef] = None) extends Actor {
  import KafkaProducerActor._  
  import KafkaSender._
  import KafkaProdConfig._
  val producer = new KafkaProducer(Producer.props)
  val consumer = new KafkaConsumer(Consumer.props)
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

  //Subscribe to grid to get the latest appname-userids for each app instance
  override def preStart() {
    val gridtopik = gridtopic.split(",").toList.asJava
    Await.ready(Future{consumer.subscribe(gridtopik)}, interval milliseconds)
  }

  def receive = {
      //Send the msg to the app instances containing the targeted users
      //Sends to targeted users with live/open sockets at that moment
    case JsonProduce(operator, topic, kmsg) =>
      val jkmsg = Json.toJson(kmsg).toString 
      val key = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      appdistribution.foreach{ case(appname, setofids)=> 
        if( (setofids).exists((n) => (kmsg.ids).contains(n)) ){
          testactor.map(_ ! kmsg)
          val producerRecord0 = new ProducerRecord[Array[Byte],Array[Byte]](appname, msgpartition, key.getBytes("UTF8"), jkmsg.getBytes("UTF8"))
          producer.send(producerRecord0.asInstanceOf[ProducerRecord[Nothing, Nothing]]) 
        }
      }
      // //send to firebase notification for client websockets closed - ie socket is closed/client app closed
      // // check if userid is contained in any of the app instances and if not, 
      // // create notification and send
      //send to each user - (vendor & customer) or (vendor & followers)
      kmsg.ids map {userid=>
        val alive = appdistribution.values.toSet.flatten
        if( alive.contains(userid) ){ 
          //Client App is running
          val title = kmsg.msg.deliverystatus
          val clickaction = kmsg.clickaction
          val timetolive = kmsg.timetolive
          val priority = kmsg.priority
          val body: Option[String] = if (kmsg.msg.deliverystatus == "Recieved") {
              if(kmsg.msg.typ=="ORDER" || kmsg.msg.typ=="PROMO"){
              Some(s"Recieved order for $kmsg.shopname $kmsg.title at $kmsg.price from $kmsg.username")
              }else if(kmsg.msg.typ=="MAIL"){
              Some(s"Mail between $kmsg.shopname $kmsg.username with message: $kmsg.detail")
              }else if(kmsg.msg.typ=="RESV"){
              Some(s"Reservation exchanges between $kmsg.shopname $kmsg.username with message: $kmsg.detail")
              } else {None}  
            } else if (kmsg.msg.deliverystatus == "Delivered"){ 
              Some(s"Delivered $kmsg.title from $kmsg.shopname to $kmsg.username")
            } else if (kmsg.msg.deliverystatus == "Cancel"){  
              Some(s"Cancellation requested for $kmsg.title from $kmsg.shopname")
            } else if (kmsg.msg.deliverystatus == "Refunded"){
              Some(s"Refunded $kmsg.price for item $kmsg.title from $kmsg.shopname to $kmsg.username")
            } else {None}
              body map{ bdy=>
                notificationActor ! SendNotification(userid, bdy, title, priority, timetolive, clickaction)          
              }
        } else {
          //Client App is closed, or running in background
          val title = kmsg.msg.deliverystatus
          val clickaction = kmsg.clickaction
          val timetolive = kmsg.timetolive
          val priority = kmsg.priority
          val body: Option[String] = if (kmsg.msg.deliverystatus == "Recieved") {
              if(kmsg.msg.typ=="ORDER" || kmsg.msg.typ=="PROMO"){
              Some(s"Recieved order $kmsg.title of $kmsg.shopname from $kmsg.username")
              }else if(kmsg.msg.typ=="MAIL"){
              Some(s"Mail between $kmsg.shopname $kmsg.username with message: $kmsg.detail")
              }else if(kmsg.msg.typ=="RESV"){
              Some(s"Reservation exchanges between $kmsg.shopname $kmsg.username with message: $kmsg.detail")
              } else {None}  
            } else if (kmsg.msg.deliverystatus == "Delivered"){ 
              Some(s"Delivered $kmsg.title from $kmsg.shopname to $kmsg.username")
            } else if (kmsg.msg.deliverystatus == "Cancel"){  
              Some(s"Cancellation requested for $kmsg.title from $kmsg.shopname")
            } else if (kmsg.msg.deliverystatus == "Refunded"){
              Some(s"Refunded $kmsg.price for item $kmsg.title from $kmsg.shopname to $kmsg.username")
            } else {None}
              body map{ bdy=>
                notificationActor ! SendNotification(userid, bdy, title, priority, timetolive, clickaction)          
              } //if(body.isDefined){}else{Error Report}
        }
      }

      // Duplicate to be persisted(cassandra) for homeapp-inbox via persistapp
      // nb the above producer.send is for live websockets
      sparkActor ! PersistKmsgs(kmsg)
      //Add to Shop-user's and Customer-user's history fields
      val mg = kmsg.msg
      sparkActor ! ProcToSparkUsrMsg("ADDSHOPHISTORY", self, self, operator, mg)
      sparkActor ! ProcToSparkUsrMsg("ADDFEEDHISTORY", self, self, operator, mg)
      //Send data as preference object to update preference
      prefActor ! AddKmsgPreference(operator, kmsg)


      // Add var to appdistribution of type Map[AppName, Set(user ids)]
      // Will be used to find which app instances(app topics/appname's) gets message 
      // Update - Remove relevant old records and add new records
    case Grid(x) => 
        Json.parse(x).validate[KafkaDistribution] match {
          case mg: JsSuccess[KafkaDistribution] => 
            Logger.info("KafkaSender got something via kafka:Grid")
            Try(mg map { d => appdistribution - d.appname; appdistribution += (d.appname -> d.userids)}) match {
                case Success(lines) => testactor.map(_ ! appdistribution.head)
                case Failure(ex) => Logger.info("KafkaSender: Incomming Grid, parse Failure - KafkaProdChildActor Unparsable message:" + Try(x.toString).toOption.toString +"=>"+ ex.getMessage) 
            }
          case e: JsError => { Logger.info("KafkaSender: Incomming Grid, parse Failure - KafkaProdChildActor Unparsable message:" + Try(x.toString).toOption.toString +"=>"+ JsError.toJson(e).toString)}
        }

    case HealthCheck => 
      val m = "HealthCheck"
      val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      val producerRecord1 = new ProducerRecord[Array[Byte],Array[Byte]](healthtopic, healthpartition, date.getBytes("UTF8"), m.getBytes("UTF8"))
      producer.send(producerRecord1.asInstanceOf[ProducerRecord[Nothing, Nothing]])         
        if (breakerIsOpen){} else {
          sender ! ("ChildKafkaProducerActor" + "=" + date + ":")
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
  // Get all [AppName, Set(user ids)] and send to child to be set
  system.scheduler.schedule(initialdelay milliseconds, interval milliseconds) {
    breaker.withCircuitBreaker(
      Await.ready(
        Future{
              val stream = consumer.poll(0)
              for {rec <- stream.asScala} yield self ! Grid(new String(rec.value(), "UTF-8"))
        }, interval milliseconds
      )
    )
  }  

}

object KafkaProdConfig {
  var appdistribution: mutable.AnyRefMap[String, Set[String]] = mutable.AnyRefMap()
  
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "kafkaproduceractor")
  val msgpartition : Integer = c.getInt("kafka.msgpartition")
  val initialsize = c.getInt("kafkaproduceractor.startingRouteeNumber")
    val withintimerange = c.getDuration("kafkaproduceractor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("kafkaproduceractor.supervisorStrategy.maxNrOfRetries")  

    val maxfailures = c.getInt("kafkaproduceractor.breaker.maxFailures")
    val calltimeout = c.getDuration("kafkaproduceractor.breaker.callTimeout", TimeUnit.MILLISECONDS)
    val resettimeout = c.getDuration("kafkaproduceractor.breaker.resetTimeout", TimeUnit.MILLISECONDS)
    val initialdelay = c.getDuration("kafkaproduceractor.scheduler.initialDelay", TimeUnit.MILLISECONDS) 
    val interval =  c.getDuration("kafkaproduceractor.scheduler.interval", TimeUnit.MILLISECONDS)

  val gridpartition: Integer = c.getInt("kafka.gridpartition")
  val gridtopic = c.getString("kafka.gridtopic")
  val healthpartition: Integer = c.getInt("kafka.healthpartition")
  val healthtopic = c.getString("kafka.healthtopic")

  var breakerIsOpen = false
}


