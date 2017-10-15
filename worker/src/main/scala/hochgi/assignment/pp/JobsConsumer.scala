package hochgi.assignment.pp

import java.nio.charset.StandardCharsets
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import hochgi.assignment.pp.job.{Job, codec}
import org.apache.kafka.common.serialization.{Deserializer, Serdes}
import sjsonnew.shaded.scalajson.ast.unsafe.{JNull, JValue}
import sjsonnew.support.scalajson.unsafe.{Converter, Parser}
import scala.util.{Failure, Success}

object JobsConsumer extends LazyLogging {

  private val jobDeserializer = new Deserializer[Job] {
    override def configure(configs: java.util.Map[String, _], isKey: Boolean) = {
      // irrelevant
    }
    override def deserialize(topic: String, data: Array[Byte]): Job = {
      val json = Option(data).fold[JValue](JNull){ arr =>
        val dataStr = new String(arr, StandardCharsets.UTF_8)
        Parser.parseFromString(dataStr) match {
          case Success(j) => j
          case Failure(e) =>
            logger.error(s"failed to deserialize from topic[$topic] data[$dataStr]",e)
            JNull
        }
      }
      Converter.fromJsonUnsafe(json)(codec.JobJsonProtocol.JobFormat)
    }
    override def close() = {
      // irrelevant
    }
  }

  def source(config: Config)(implicit actorSystem: ActorSystem): Source[CommittableMessage[String, Job], Control]= {
    val topic = config.getString("hochgi.assignment.pp.kafka.topic")
    val consumerSettings = ConsumerSettings(actorSystem, Serdes.String().deserializer(), jobDeserializer)
      .withBootstrapServers(config.getString("hochgi.assignment.pp.kafka.bootstrap.servers"))
    Consumer.committableSource(consumerSettings, Subscriptions.topics(topic))
  }
}
