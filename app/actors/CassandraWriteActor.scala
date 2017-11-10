package actors

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import akka.actor.{ActorKilledException, ActorInitializationException}
import com.datastax.driver.core.{BoundStatement, Cluster}
import models.daos.core._
import models.daos.core.Tables._
import javax.inject._
import com.google.inject.name.Named
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.concurrent.{ Future, ExecutionContext }
import akka.pattern.pipe
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration._
import play.api.Logger
import play.api.libs.json._
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import java.util.UUID
import scala.util.Try
import scala.util.{Success, Failure}
import akka.pattern._
import akka.routing._
import play.api.Play.current
import models.mail._
import models.Locality._
import models._
import java.lang.Runtime

object CassandraWriteActor {
  case class DeleteColumn(database: String, table: String, columns: String, conditions: String)
  case class DeleteRow(database: String, table: String, conditions: String)
  case class SaveColumn(database: String, table: String, columns: String, values: String)
  case class UpdateColumn(database: String, table: String, columnvalues: String, conditions: String)
}
object CassandraWriteConfig {
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "cassandraactor")
  val initialsize = c.getInt("cassandraactor.startingRouteeNumber")
  val withintimerange = c.getDuration("cassandraactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
  val maxnrofretries = c.getInt("cassandraactor.supervisorStrategy.maxNrOfRetries")  
}
class CassandraWriteActor @Inject() ( 
            @Named("error-actor") errorActor: ActorRef)
            (implicit val ec: ExecutionContext) extends Actor {
  import CassandraWriteActor._
  import CassandraWriteConfig._
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

    val writer: ActorRef = context.actorOf(
      BalancingPool(initialsize).props(WriteActor.props(errorActor)), "casswriterouter")
        
    def receive = {
            case x => writer forward x
    }
}


object WriteActor {
  def props(errorActor: ActorRef): Props = Props(new WriteActor(errorActor))
}
class WriteActor(errorActor: ActorRef) extends Actor {
  import CassandraWriteActor._
  import CassandraWriteConfig._
  import WriteActor._
  import models.daos.core.{CassandraCluster}
  implicit val ec = context.dispatcher
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

    case DeleteRow(dbase, table, conditions) =>
        val session = cluster.connect(dbase)
        val statement = session.prepare("DELETE FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions"+";")
        val stmt  = new BoundStatement(statement)
        Future(session.executeAsync(stmt).isDone) pipeTo sender 
//val statement = session.prepare(DELETE col1, col2, col3 FROM Planeteers USING CONSISTENCY ONE WHERE KEY = 'Captain';)
    case DeleteColumn(dbase, table, columns, conditions) =>
        val session = cluster.connect(dbase)
        val statement = session.prepare(s"DELETE" + " " + s"$columns" + " " + "FROM" + " " + s"$table" + " " + "WHERE" + " " + s"$conditions"+";")
        val stmt  = new BoundStatement(statement)
        Future(session.executeAsync(stmt).isDone) pipeTo sender 

//val statement = session.prepare("INSERT INTO users(providerkey, providerid, hasher, password, salt) VALUES (kwekutgmail, credentials, hasherb, passworda, saltc);")
    case SaveColumn(dbase, table, columns, values) =>
        val session = cluster.connect(dbase)
        val statement = session.prepare("INSERT INTO" + " " + s"$table" + " " + s"($columns)" + " " + "VALUES" + " " + s"($values)" + ";")
        val stmt  = new BoundStatement(statement)
        Future(session.executeAsync(stmt).isDone) pipeTo sender 

//val statement = session.prepare(UPDATE ... SET col1 = val1, col2 = val2 WHERE KEY = key1;)
    case UpdateColumn(dbase, table, colval, conditions) =>
        val session = cluster.connect(dbase)
        val statement = session.prepare( "UPDATE" + " " + s"$table" + " " + "SET" + " " + s"$colval" + " " + "WHERE" + " " + s"$conditions" + ";")
        val stmt  = new BoundStatement(statement)
        Future(session.executeAsync(stmt).isDone) pipeTo sender 

    // Health check both the cass actors and the cass cluster
    case HealthCheck => 
        val table = usertable
        val dbase = userkeyspace
        val columns = "userid, email, payload"
        val values = s"userid, email, user"        
        val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        val session = cluster.connect(dbase)
        val statement = session.prepare("INSERT INTO" + " " + s"$table" + " " + s"($columns)" + " " + "VALUES" + " " + s"($values)" + ";")
        val stmt  = new BoundStatement(statement)
        Future(session.executeAsync(stmt).isDone) map (
          x=> ("CassWriteActor" + "=" + date + ":")
        ) pipeTo sender

    case x => errorActor ! (Talker(x.toString))
        
  }

}


