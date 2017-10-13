package hochgi.assignment.pp

import java.nio.charset.StandardCharsets
import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Producer
import akka.kafka.ProducerSettings
import akka.stream.scaladsl.{Flow, Keep, Sink}
import com.typesafe.config.Config
import hochgi.assignment.pp.job.{Job, codec}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Serdes, Serializer}
import sjsonnew.support.scalajson.unsafe.{CompactPrinter, Converter}
import scala.concurrent.Future

object JobsDispatcher {

  private val jobSerializer = new Serializer[Job] {
    override def configure(configs: java.util.Map[String, _], isKey: Boolean) = {
      // irrelevant
    }
    override def serialize(topic: String, data: Job) = {
      val j = Converter.toJsonUnsafe(data)(codec.JobJsonProtocol.JobFormat)
      CompactPrinter(j).getBytes(StandardCharsets.UTF_8)
    }
    override def close() = {
      // irrelevant
    }
  }

  def sink(config: Config)(implicit actorSystem: ActorSystem): Sink[Job,Future[Done]] = {
    val topic = config.getString("hochgi.assignment.pp.kafka.topic")
    val numOfPartitions = config.getInt("hochgi.assignment.pp.kafka.num-of-partitions")
    require(numOfPartitions > 0, s"invalid number of partitions defined[$numOfPartitions]. configure `hochgi.assignment.pp.kafka.num-of-partitions` property properly")

    val producerSettings = ProducerSettings(actorSystem, Serdes.String().serializer(), jobSerializer)

    Flow.fromFunction[Job, ProducerRecord[String, Job]] { job =>
      val partition = {
        if (numOfPartitions == 1) 0
        else job.hashCode % numOfPartitions
      }
      new ProducerRecord(
        topic,
        partition,
        System.currentTimeMillis(),
        job.id,
        job
      )
    }.toMat(Producer.plainSink(producerSettings))(Keep.right)
  }
}
