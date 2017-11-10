package services.dwolla

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
import javax.inject._
import com.google.inject.name.Named
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import play.api.libs.json.{JsNull,Json,JsString,JsValue}
import scala.io.Source
import java.net.URL
import models._

object DwollaImpl {
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "dwolla")
  	//val refreshtoken = c.getString("dwolla.refreshtoken")
  	//val accesstoken = c.getString("dwolla.accesstoken")
	val client_id = c.getString("dwolla.client_id")
	val client_secret = c.getString("dwolla.client_secret")
    val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z") 
}
// Use bank statement and save 29 cent for each transaction
//https://developers.dwolla.com/guides/transfer-money-between-users/06-create-transfer.html
//type = personal/business, date = YYYY-MM-DD, ssn = l4, state = CA
class DwollaImpl @Inject() (val ws: WSClient)(implicit val ec: ExecutionContext)  extends DwollaService {
	import DwollaImpl._


//Generate an IAV token
//POST https://api.dwolla.com/customers/{id}/iav-token
	def generateIAV(da: DwollaAdmin, customer_url: String): Future[Either[String, DwollaError]] = {
	val url = s"https://api.dwolla.com/customers/$customer_url/iav-token"	
		val complexResponse =
			ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            //.post
            .get()
		complexResponse map { response => 
		    if(response.status == 201){
			val fundurl = (response.json \ "_links" \ "self" \ "href").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    val token = (response.json \ "token").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Left(fundurl)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

	//"https://api-uat.dwolla.com/customers/28138609-30FF-4607-B28C-4A3872F8FD4A/funding-sources-token"
	def getFundingSourcesToken(da: DwollaAdmin, customer_url: String): Future[Either[String, DwollaError]] = {
		val url = "https://api-uat.dwolla.com/customers/$customer_url/funding-sources-token"
		//val url = "https://api.dwolla.com/customers/$customer_url/funding-sources-token"	
		val complexResponse =
			ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .get
		complexResponse map { response => 
		    if(response.status == 200){
		    	val token = response.header("token").get
		    Left(token)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

//C = POST https://api.dwolla.com/customers
//https://api-uat.dwolla.com/customers/c7f300c0-f1ef-4151-9bbe-005005aa3747
//Create Vendor and Consumer Accounts
	def createVerifiedCustomer(da: DwollaAdmin, dwobj: PaymentUser, ipAddress: String
		): Future[Either[String, DwollaError]] = {
		val url = "https://api-uat.dwolla.com/customers"
		//val url = "https://api.dwolla.com/customers"
		val createverifiedcustomer: JsValue = Json.obj(
		  "firstName" -> dwobj.firstName, "lastName" -> dwobj.lastName,
		  "email" -> dwobj.email, "ipAddress" -> ipAddress,
		  "type" -> dwobj.typ, "address" -> dwobj.address, "city" -> dwobj.city,
		  "state" -> dwobj.state, "postalCode" -> dwobj.zip, "dateOfBirth" -> dwobj.dob,
		  "ssn" -> dwobj.ssn, "phone" -> dwobj.phone)
		val complexResponse =
			ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .post(createverifiedcustomer)
		complexResponse map { response => 
		    if(response.status == 201){
		    	val cusurl = response.header("Location").get
		    Left(cusurl)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}


	//val source_url = s"https://api-uat.dwolla.com/funding-sources/80275e83-1f9d-4bf7-8816-2ddcd5ffc197"
	//val destination_url = s"https://api-uat.dwolla.com/customers/$destination_id"
	//status - Either processed, pending, cancelled, failed, or reclaimed
	//Source - Funding source => https://api.dwolla.com/funding-sources/{id}
	//Destination - Funding source/Customer/Account => https://api.dwolla.com/funding-sources/{id}
	def createTransfer(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
		): Future[Either[String, DwollaError]] = {
		val url = "https://api-uat.dwolla.com/transfers"
		val createtranfer: JsValue = Json.obj(
		  "_links" -> Json.obj("source" -> Json.obj("href" -> dc.funding_url), "destination" -> Json.obj("href" -> ds.funding_url)),
		  "amount" -> Json.obj("currency" -> ds.currency, "value" -> value)
		)
		val complexResponse = ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Idempotency-Key" -> s"$idempotencykey", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .post(createtranfer)
		complexResponse map { response => 
		    if(response.status == 201){
		    	val transurl = response.header("Location").get
		    Left(transurl)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

	// Take the transfer url from tranfer function
	//https://api-uat.dwolla.com/transfers/d76265cd-0951-e511-80da-0aa34a9b2388
	def checkTransferStatus(da: DwollaAdmin, transfer_url: String): Future[Either[String, DwollaError]] = {
		val complexResponse = ws.url(transfer_url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .get
		complexResponse map { response => 
		    if(response.status == 201){
				val transferid = (response.json \ "_links" \ "self" \ "href").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
				val source = (response.json \ "_links" \ "source" \ "href").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
				val destination = (response.json \ "_links" \ "destination" \ "href").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val id = (response.json \ "id").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val transferstatus = (response.json \ "status").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val value = (response.json \ "amount" \ "value").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val created = (response.json \ "created").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Left(transferstatus)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

	def createTransferWithFee(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
		): Future[Either[String, DwollaError]] = {
		val url = "https://transfers"
		val rate = dc.fee_rate
		val fee = (value.toInt) * (rate.toInt/100)
		val transact: JsValue = if (ds.fee_rate.toInt > 0) {
			Json.obj(
			  "_links" -> Json.obj("source" -> Json.obj("href" -> dc.funding_url), "destination" -> Json.obj("href" -> ds.funding_url)),
			  "amount" -> Json.obj("currency" -> ds.currency, "value" -> value),
			  "fees" -> Json.arr(
			  	Json.obj("_links" -> Json.obj("charge-to" -> Json.obj("href" -> ds.funding_url))),
			  	Json.obj("amount" -> Json.obj("currency" -> ds.currency, "value" -> fee))
			  )
			)
			//"metadata" -> Json.obj(x -> y),
		} else {
			Json.obj(
			  "_links" -> Json.obj("source" -> Json.obj("href" -> dc.funding_url), "destination" -> Json.obj("href" -> ds.funding_url)),
			  "amount" -> Json.obj("currency" -> ds.currency, "value" -> value)
			)			
		}
		val complexResponse = ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Idempotency-Key" -> s"$idempotencykey", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .post(transact)
		complexResponse map { response => 
		    if(response.status == 201){
		    	val transurl = response.header("Location").get
		    Left(transurl)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

	def refundTransferWithFee(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
		): Future[Either[String, DwollaError]] = {
	val url = "https://transfers"
		val rate = dc.fee_rate
		val fee = (value.toInt) * (rate.toInt/100)
		val createfee: JsValue = Json.obj(
		  "_links" -> Json.obj("source" -> Json.obj("href" -> dc.funding_url), "destination" -> Json.obj("href" -> ds.funding_url)),
		  "amount" -> Json.obj("currency" -> ds.currency, "value" -> value),
		  "fees" -> Json.arr(
		  	Json.obj("_links" -> Json.obj("charge-to" -> Json.obj("href" -> ds.funding_url))),
		  	Json.obj("amount" -> Json.obj("currency" -> ds.currency, "value" -> fee))
		  )
		)
		//"metadata" -> Json.obj(x -> y),
		val complexResponse = ws.url(url)
		    .addHttpHeaders("Accept" -> "application/vnd.dwolla.v1.hal+json", "Content-Type" -> "application/vnd.dwolla.v1.hal+json", "Idempotency-Key" -> s"$idempotencykey", "Authorization" -> s"$da.access_token")
		    .withRequestTimeout(10000.millis)
            .post(createfee)
		complexResponse map { response => 
		    if(response.status == 201){
		    	val transurl = response.header("Location").get
		    Left(transurl)
		    } else {
		    	val status = response.status.toString
		    	val code = (response.json \ "code").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val message = (response.json \ "message").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    	val path = (response.json \ "path").validate[String] match { case s: JsSuccess[String] => s.get; case e: JsError => JsError.toJson(e).toString()}
		    Right(DwollaError(status, path, code, message))
		   	}
		}
	}

}