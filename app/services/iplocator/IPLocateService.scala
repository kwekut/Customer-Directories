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

import org.apache.commons.codec.binary.Base64._
import java.util.Base64
import play.api.Logger
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import au.id.jazzy.play.geojson._
import models._


trait IPLocateService  {
	def getLocation(ipAddress: String): Future[LatLng]
}
