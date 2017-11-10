package services.kafkas

import java.util.Properties
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.consumer._
//import java.util.HashMap
import scala.collection.mutable.Buffer

import scala.collection.JavaConverters._
import java.util.ArrayList
import java.util.Arrays
import play.api.Logger
import com.typesafe.config.ConfigFactory
import models._

object Consumer {
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "consumer")
   val groupid: String = AppName.appname

   val bootServe: String = c.getString("consumer.bootstrap.servers") 
   val commitInterval: String = c.getString("consumer.auto.commit.interval.ms") 
   val sessionTo: String = c.getString("consumer.session.timeout.ms")
   val keyDeserial: String = c.getString("consumer.key.deserializer")
   val valDeserial: String = c.getString("consumer.value.deserializer")
 
  val props = new Properties()
    props.put("bootstrap.servers", bootServe)    
    //props.put("group.id", groupid)
    props.put("auto.commit.interval.ms", commitInterval)
    props.put("session.timeout.ms", sessionTo)
    props.put("key.deserializer", keyDeserial)
    props.put("value.deserializer", valDeserial)
    //initiated in Actor - Inversion of control 
  //val consumer = new KafkaConsumer(props)
}







  





















// ///////////////////////////
// 	    	val customerParams: HashMap[String, Integer] = new HashMap()
// 				customerParams += ("description" -> user.fullName.getOrElse("Na"))
// 				customerParams += ("email" -> user.email.getOrElse("Na"))
// 				customerParams += ("source" -> token)
// 			val customer = Customer.create(customerParams, requestOptions)
// /////////////////////
// 		val params = new ArrayList[NameValuePair]
// 	    	params.add(new BasicNameValuePair("Body", msg));
// 	    	params.add(new BasicNameValuePair("To", to));
// 	    	params.add(new BasicNameValuePair("From", from));
// 	    	params.add(new BasicNameValuePair("MediaUrl", driver));
// 	    val message: Message = messageFactory.create(params)
// 	    	message.getSid



// class Consumer {

//   public static kafka.javaapi.consumer.ConsumerConnector createJavaConsumerConnector(ConsumerConfig config);
// }

// public interface kafka.javaapi.consumer.ConsumerConnector {

//   public <K,V> Map<String, List<KafkaStream<K,V>>>
//     createMessageStreams(Map<String, Integer> topicCountMap, Decoder<K> keyDecoder, Decoder<V> valueDecoder);

//   public Map<String, List<KafkaStream<byte[], byte[]>>> 
//   	createMessageStreams(Map<String, Integer> topicCountMap);

//   public <K,V> List<KafkaStream<K,V>>
//     createMessageStreamsByFilter(TopicFilter topicFilter, int numStreams, Decoder<K> keyDecoder, Decoder<V> valueDecoder);

//   public List<KafkaStream<byte[], byte[]>> 
//   	createMessageStreamsByFilter(TopicFilter topicFilter, int numStreams);

//   public List<KafkaStream<byte[], byte[]>> 
//   	createMessageStreamsByFilter(TopicFilter topicFilter);

//   public void commitOffsets();
//   public void shutdown();
// }