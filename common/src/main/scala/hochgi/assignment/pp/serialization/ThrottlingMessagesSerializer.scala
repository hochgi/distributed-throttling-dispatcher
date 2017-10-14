package hochgi.assignment.pp.serialization

import java.io.NotSerializableException
import java.nio.charset.StandardCharsets

import akka.actor.ExtendedActorSystem
import akka.event.{LogSource, Logging}
import akka.serialization.Serializer
import hochgi.assignment.pp.throttle._
import hochgi.assignment.pp.throttle.codec.ThrottleJsonProtocol._
import hochgi.assignment.pp.util.LZ4
import sjsonnew.JsonFormat
import sjsonnew.support.scalajson.unsafe.{CompactPrinter, Converter, Parser}

class ThrottlingMessagesSerializer(actorSystem: ExtendedActorSystem) extends Serializer {

  private implicit val logSource = new LogSource[ThrottlingMessagesSerializer] {
    override def genString(t: ThrottlingMessagesSerializer) = s"ThrottlingMessagesSerializer~$toString"
  }
  private val logger = Logging(actorSystem, this)

  override def identifier = 786

  override def toBinary(o: AnyRef) = {
    if(o.isInstanceOf[ThrottlingMessage]) toJsonAndThenLZ4(o.asInstanceOf[ThrottlingMessage])
    else throw new NotSerializableException(s"cannot deserialize ${o.getClass().getName} using hochgi.assignment.pp.serialization.ThrottlingMessagesSerializer")
  }

  private def toJsonAndThenLZ4[T <: ThrottlingMessage : JsonFormat](t: T): Array[Byte] = {
    val json = Converter.toJsonUnsafe(t)
    val bytes = CompactPrinter(json).getBytes(StandardCharsets.UTF_8)
    LZ4.compress(ThrottlingMessage.serializationID(t) +: bytes)
  }

  override def includeManifest = false

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = {
    try {
      val data = LZ4.decompress(bytes)
      val jstr = new String(data.tail, StandardCharsets.UTF_8)
      val json = Parser.parseFromString(jstr).get
      val frmt = ThrottlingMessage.fromJsonAndSerializationID(data.head)
      val tmsg = Converter.fromJson(json)(frmt).get
      tmsg
    } catch {
      case err: Throwable => {
        logger.error(err,"deserialization failed")
        throw new NotSerializableException("deserialization failed: " + err.getMessage)
      }
    }
  }
}
