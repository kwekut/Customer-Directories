package services.iplocator

import javax.inject.Inject
import scala.concurrent.{ Future, ExecutionContext }
import java.util.UUID
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.LocalDateTime
import scala.util.{Try, Success, Failure}
import play.api.Logger
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import au.id.jazzy.play.geojson._
import javax.inject._
import com.google.inject.name.Named
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsNull,Json,JsString,JsValue}
import scala.io.Source
import java.net.URL
import models._

object IPLocateImpl {
  private val cong = ConfigFactory.load()
  cong.checkValid(ConfigFactory.defaultReference(), "crypto")
  private val key: String = cong.getString("crypto.key")
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z") 
}


class IPLocateImpl @Inject() (val ws: WSClient)(implicit val ec: ExecutionContext)  extends IPLocateService {
	import IPLocateImpl._

	//get approx location from ip address
	def getLocation(ipAddress: String) = {
		val url = "http://api.ipinfodb.com/v3/ip-city"
		val complexResponse = ws.url(url)
		  .withRequestTimeout(10000.millis)
		  .withQueryString("key" -> key, "ip" -> ipAddress)
		  .get()
		complexResponse map { response => 
		    LatLng(
		      (response.json \ "latitude").as[Double],
		      (response.json \ "longitude").as[Double]
		   ) 
		}

	}

}