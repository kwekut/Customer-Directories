package services.stripe

import akka.actor._
import javax.inject._
import com.google.inject.name.Named
import scala.collection.JavaConverters._
import java.util.ArrayList
import java.util.List
import org.apache.http.NameValuePair
import org.apache.http.message.BasicNameValuePair
import scala.util.Try
import java.util.UUID
import scala.concurrent.{ Future, ExecutionContext }

import play.api.Logger
import com.stripe.Stripe
import com.stripe.net.RequestOptions._
import com.stripe.exception._
import com.stripe.model._
import com.stripe.net._
import com.typesafe.config.ConfigFactory
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import com.google.common.collect.ImmutableMap
import java.util.HashMap
import java.util.Map
import models._
// {
//   ...
//   "stripe_publishable_key": "pk_live_h9xguYGf2GcfytemKs5tHrtg",
//   "access_token": "sk_live_AxSI9q6ieYWjGIeRbURf6EG0",
//   "stripe_user_id": "acct_fW8jjNIxuQwR2z",
//   ...
// }

//https://stripe.com/docs/connect/payments-fees#charging-through-the-platform
//https://stripe.com/docs/connect/payments-fees#issuing-refunds
object StripeImpl{
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "stripe")
	val stripeApiKey: String = c.getString("stripe.api.key")
	val stripeTestApiKey: String = "sk_test_BQokikJOvBiI2HlWgH4olfQ2"

	case class DBException(message: String) extends Exception(message)
	case class CustomerChargeResponse(chargeid: String, amount: String, created: String, status: String)
}

class StripeImpl @Inject() 
        (implicit val ec: ExecutionContext)  extends StripeService {
	import StripeImpl._

//(paymentid#typ#Povider:brand#last4#expyr)
// Create a Customer:
	def createCustomer(user: User, payment: Payment) = Future {
		val id = UUID.randomUUID().toString
		val userid = user.userid
		val email = user.email.getOrElse("")
		val token = payment.access_token.getOrElse("")
		val requestOptions = (new RequestOptionsBuilder()).setApiKey(stripeApiKey).build()
		val chargeParams: HashMap[String, Object] = new HashMap()
		val cusParams: HashMap[String, Object] = new HashMap()
			cusParams.asScala += ("source" -> token);
			cusParams.asScala += ("email" -> email);
		val customer = Customer.create(cusParams, requestOptions)
		val account_id = customer.getId()
		val currency = customer.getCurrency()
		val customerCards = customer.getCards().getData();
		val brand = customerCards.get(0).getBrand()
		val last4 = customerCards.get(0).getLast4()
		val expyear = customerCards.get(0).getExpYear()
		
		// val customerCards = customer.getSources().getData().get(0)
		// val brand = customerCards.get(0).getBrand()
		// val last4 = customerCards.get(0).getLast4()
		// val expyear = customerCards.get(0).getExpYear()

		// val customerSourceList = customer.getSources().all(chargeParams).getData();
		// val cusobj = customerSourceList.get(0)
		// val brand = customerSourceList.get(0).getBrand()
		// val last4 =  customerSourceList.get(0).getLast4()
		// val expyear = customerSourceList.get(0).getExpYear()
		val summary = s"$id#Card#Stripe:$brand#$last4#$expyear"
		payment.copy(
		  paymentid=id,
		  userid=userid,
          account_id=Some(account_id), 
          currency=currency, 
          summary=summary, 
          provider="stripe"
        )
	}

//Charge the customer into our own account
	def chargeCustomer(customerId: String, currency: String, amt: java.lang.Integer, fee: String) = Future {
		val requestOptions = (new RequestOptionsBuilder()).setApiKey(stripeApiKey).build()
		val chargeParams: HashMap[String, Object] = new HashMap()
			chargeParams.asScala += ("amount" -> amt)
			chargeParams.asScala += ("currency" -> "usd")
			chargeParams.asScala += ("customer" -> customerId)
		val chargeRaw = Charge.create(chargeParams, requestOptions)
		//val charge: Charge = chargeRaw.capture(requestOptions)
		val charge = ( chargeRaw.capture(requestOptions) ) //map { charge =>
		charge
		//CustomerChargeResponse(charge.getAmount.toString, charge.getCreated.toString, charge.getStatus.toString)
	} 

	//Vendor charges customer directly - We are not involved
	//This is safer and more desirable as a startup - Dont get involved
	def chargeCustomerDirect(customertoken: String, vendorstripeid: String, currency: String, amt: java.lang.Integer, fee: String) = Future {
		//CONNECTED_STRIPE_ACCOUNT_ID = vendorstripeid
		Stripe.apiKey = stripeApiKey
		val requestOptions = RequestOptions.builder().setStripeAccount(vendorstripeid).build();
		val chargeParams: HashMap[String, Object] = new HashMap()
			//chargeParams += ("description" -> feedid)
			chargeParams.asScala += ("source" -> customertoken)
			chargeParams.asScala += ("amount" -> amt)
			chargeParams.asScala += ("currency" -> "usd")
			
		val chargeRaw = Charge.create(chargeParams, requestOptions)
		val charge = ( chargeRaw.capture(requestOptions) ) 
		charge
		//CustomerChargeResponse(charge.getId.toString, charge.getAmount.toString, charge.getCreated.toString, charge.getStatus.toString)
	} 

	//Vendor refunds customer directly - We are not involved
	//This is safer and more desirable as a startup - Dont get involved
	def refundCustomerDirect(chargeid: String, vendorstripeid: String, currency: String, amt: java.lang.Integer, fee: String) = Future {
		//CONNECTED_STRIPE_ACCOUNT_ID = vendorstripeid
		Stripe.apiKey = stripeApiKey
		val requestOptions = RequestOptions.builder().setStripeAccount(vendorstripeid).build();
		val refundParams: HashMap[String, Object] = new HashMap()
			refundParams.asScala += ("amount" -> amt)
		val chargeRaw = Charge.retrieve(chargeid, requestOptions)
		val charge = ( chargeRaw.refund(refundParams) )
		charge
		//CustomerChargeResponse(charge.getId.toString, charge.getAmount.toString, charge.getCreated.toString, charge.getStatus.toString)
	} 

	// //We charge customer on behalf of shop and transfer - We are middle men
	// // This is not desirable to start with
	// def chargeCustomerIntermediary(customertoken: String, vendorstripeid: String, amt: java.lang.Integer) = Future {
	// 	Stripe.apiKey = stripeApiKey
	// 	val chargeParams: HashMap[String, Object] = new HashMap()
	// 		chargeParams += ("amount" -> amt)
	// 		chargeParams += ("currency" -> "usd")
	// 		chargeParams += ("source" -> customertoken)
	// 		chargeParams += ("destination", vendorstripeid)
	// 	val chargeRaw = Charge.create(chargeParams)
	// 	val charge = ( chargeRaw.capture() ) 
	// 	charge
	// 	//CustomerChargeResponse(charge.getId.toString, charge.getAmount.toString, charge.getCreated.toString, charge.getStatus.toString)
	// } 
	// //We charge customer on behalf of shop and transfer - We are middle men
	// // This is not desirable to start with
	// def refundCustomerIntermediary(chargeid: String, vendorstripeid: String, amt: java.lang.Integer) = Future {
	// 	Stripe.apiKey = stripeApiKey
	// 	val refundParams: HashMap[String, Object] = new HashMap()
	// 		refundParams += ("amount" -> amt)
	// 		refundParams += ("reverse_transfer", "true")
	// 		refundParams += ("refund_application_fee", "true")
	// 	val chargeRaw = Charge.retrieve(chargeid)
	// 	val charge = chargeRaw.refund(refundParams)
	// 	charge
	// 	//CustomerChargeResponse(charge.getId.toString, charge.getAmount.toString, charge.getCreated.toString, charge.getStatus.toString)
	// } 

}

