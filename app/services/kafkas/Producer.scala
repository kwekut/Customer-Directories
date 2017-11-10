package services.kafkas

import play.api.Logger
import java.util.{Properties, UUID}
import akka.actor.{ ActorRef, ActorSystem, Props, Actor }
import org.apache.kafka.common.TopicPartition
import scala.collection.mutable.Buffer

import scala.collection.JavaConverters._
import java.util.ArrayList
import java.util.Arrays
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer._
import com.typesafe.config.ConfigFactory
import models._

object Producer {
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "producer")
  val clientid: String = AppName.appname

  val bootserve: String = c.getString("producer.bootstrap.servers")
  val acks: Integer = c.getInt("producer.acks")
  val retries: Integer = c.getInt("producer.retries")
  val batchSize: Integer = c.getInt("producer.batch.size")
  val linger: Integer = c.getInt("producer.linger.ms")
  val buffermem: Integer = c.getInt("producer.buffer.memory")
  val keySerial: String = c.getString("producer.key.serializer")
  val valSerial: String = c.getString("producer.value.serializer")  

  val props = new Properties()
  props.put("bootstrap.servers", bootserve)
  props.put("acks", acks.toString)
  props.put("retries", retries)
  props.put("batch.size", batchSize)
  props.put("linger.ms", linger)
  props.put("buffer.memory", buffermem)
  props.put("key.serializer", keySerial.toString)
  props.put("value.serializer", valSerial.toString)

}

