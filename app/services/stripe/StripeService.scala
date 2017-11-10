package services.stripe

import scala.util.Try
import java.util.UUID
import scala.concurrent.{ Future, ExecutionContext }
import com.stripe.model.Charge
import StripeImpl._
import models._
 /**
 * Give access to the Stripe object.
 */	
trait StripeService {
	def createCustomer(user: User, payment: Payment): Future[Payment]
	
	def chargeCustomer(customertoken: String, currency: String, amt: java.lang.Integer, fee: String): Future[Charge]
	
	def chargeCustomerDirect(customertoken: String, vendorstripeid: String, currency: String, amt: java.lang.Integer, fee: String): Future[Charge]
							//chargeid, vendorstripeacid, currency, amt, fee
	def refundCustomerDirect(chargeid: String, vendorstripeid: String, currency: String, amt: java.lang.Integer, fee: String): Future[Charge]

	//def chargeCustomerIntermediary(customertoken: String, vendorstripeid: String, amt: java.lang.Integer): Future[Charge]
	
	//def refundCustomerIntermediary(chargeid: String, vendorstripeid: String, amt: java.lang.Integer): Future[Charge]
}