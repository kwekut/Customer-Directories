package actors

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import akka.actor.{ActorKilledException, ActorInitializationException}
import com.datastax.driver.core.{ResultSet, BoundStatement, Cluster, Row, ResultSetFuture}
import scala.concurrent.{ Future, ExecutionContext }
import com.datastax.driver.core.querybuilder.QueryBuilder
import models.daos.core._
import models.daos.core.Tables._
import javax.inject._
import com.google.inject.name.Named
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.concurrent.{ Future, ExecutionContext }
import akka.pattern.pipe
import play.api.Logger
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration._
import play.api.libs.json._
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.util.{Try, Success, Failure}
import akka.pattern._
import akka.routing._
import play.api.Play.current
import models.mail._
import models.Locality._
import models._
import java.lang.Runtime


object CassandraReadActor {
  case class FindColumn(database: String, table: String, columns: String, conditions: String)
  case class FindColumns(database: String, table: String, columns: String, conditions: String)
  case class FindRow(database: String, table: String, conditions: String)
  case class FindRows(database: String, table: String, conditions: String)
  case class PayloadColumn(payload: Option[String])
}

object CassandraReadConfig {
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "cassandraactor")
  //int cores = Runtime.getRuntime().availableProcessors();
  val initialsize = c.getInt("cassandraactor.startingRouteeNumber")
  val withintimerange = c.getDuration("cassandraactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) 
  val maxnrofretries = c.getInt("cassandraactor.supervisorStrategy.maxNrOfRetries")  
}
class CassandraReadActor @Inject() ( 
            @Named("error-actor") errorActor: ActorRef)
            (implicit val ec: ExecutionContext) extends Actor {
  import CassandraReadActor._
  import CassandraReadConfig._

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

    val reader: ActorRef = context.actorOf(
      BalancingPool(initialsize).props(ReadActor.props(errorActor)), "cassreadrouter")
        
    def receive = {
            case x => reader forward x
    }
}

object ReadActor {
    def props(errorActor: ActorRef): Props = Props(new ReadActor(errorActor))   
}
class ReadActor(errorActor: ActorRef) extends Actor {
  import CassandraReadActor._
  import CassandraReadConfig._
  import ReadActor._
  import models.daos.core.{CassandraCluster}
  import models.daos.core.cassandra.resultset._
  implicit val ec = context.dispatcher
  import akka.pattern.pipe
  val cluster = CassandraCluster.cluster

  //If message causes a restart triggering exception, Warn user, Inform Administrator
  override def preRestart(reason: Throwable, message: Option[Any]) {
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
    message match {
      case Some(m) => errorActor ! (Announcer("ActorRestartException", reason.getMessage, m.toString, s"line $LINE of file $FILE", date))
      case None => errorActor ! (Announcer("ActorRestartException", reason.getMessage, "none", s"line $LINE of file $FILE", date)) 
    }
  }

  def receive: Receive = {

    //SELECT firstName, lastName, fullName, email, avatarUrl FROM users WHERE providerKey = key AND providerID = provider;
    case FindColumn(dbase, table, columns, conditions)  =>
        val session = cluster.connect(dbase)
        val stmt  =  s"SELECT" + " " + s"$columns" + " " + "FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions" +";" 
        session.executeAsync(stmt) map(_.all().asScala.map(buildPayloadColumn).toList) pipeTo sender

    case FindColumns(dbase, table, columns, conditions)  =>
        val session = cluster.connect(dbase)
        val stmt  =  s"SELECT" + " " + s"$columns" + " " + "FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions" +";"
        session.executeAsync(stmt) map(_.all().asScala.map(buildPayloadColumn).toList) pipeTo sender

    case FindRow(dbase, table, conditions)  =>
        val session = cluster.connect(dbase)
        val stmt  = new BoundStatement(session.prepare( "SELECT * FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions" +";"))
        session.executeAsync(stmt) map(frs=> buildPayloadColumn(frs.one())) pipeTo sender 


    case FindRows(dbase, table, conditions)  =>
        val session = cluster.connect(dbase)
        val stmt  = new BoundStatement(session.prepare( "SELECT * FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions" +";"))
        session.executeAsync(stmt) map(_.all().asScala.map(buildPayloadColumn).toList) pipeTo sender
        //session.executeAsync(stmt) map(_.all().map(asRow).toList) pipeTo sender

    // Health check both the spark actors and the spark cluster
    case HealthCheck => 
        val table = usertable
        val dbase = userkeyspace
        val columns = "payload"
        val conditions = "email=email"
        val session = cluster.connect(dbase)
        val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        val stmt  =  s"SELECT" + " " + s"$columns" + " " + "FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions" +";"
        val trans = session.executeAsync(stmt) map(_.all().asScala.map(buildPayloadColumn).toList)
        trans map (x=> ("CassReadActor" + "=" + date + ":")) pipeTo sender

    case x => errorActor ! (Talker(x.toString))
        
  }

  def buildPayloadColumn(row: Row): PayloadColumn = {
    PayloadColumn(
   if (row.getColumnDefinitions().contains("payload")) {if (row.getString("payload") == null) {None} else {Some(row.getString("payload"))} } else {None}
    )
  }

}


