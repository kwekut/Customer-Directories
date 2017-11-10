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
import models._


trait DwollaService  {

	def generateIAV(da: DwollaAdmin, customer_url: String): Future[Either[String, DwollaError]]
	
	def getFundingSourcesToken(da: DwollaAdmin, customer_url: String): Future[Either[String, DwollaError]]
	
	def createVerifiedCustomer(da: DwollaAdmin, dwobj: PaymentUser, ipAddress: String
			): Future[Either[String, DwollaError]]
	
	def createTransfer(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
			): Future[Either[String, DwollaError]]
	
	def checkTransferStatus(da: DwollaAdmin, transfer_url: String): Future[Either[String, DwollaError]]
	
	def createTransferWithFee(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
			): Future[Either[String, DwollaError]]
	
	def refundTransferWithFee(da: DwollaAdmin, dc: Payment, ds: Payment, value: String, idempotencykey: String
			): Future[Either[String, DwollaError]]

}


//Visit https://dashboard-uat.dwolla.com/applications to 
//generate an access token for your account. Authorization: Bearer 0Sn0W6kzNicvoWhDbQcVSKLRUpGjIdlPSEYyrHqrDDoRnQwE7Q
//First, we’ll create a Verified Customer for Jane Merchant.
//When the customer is created, you’ll receive the customer URL in the location header.
//Attach an unverified funding source