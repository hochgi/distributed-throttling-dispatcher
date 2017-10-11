package hochgi.assignment.pp.serialization

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.serialization.Serializer

class ThrottlingMessagesSerializer(actorSystem: ExtendedActorSystem) extends Serializer {

//  private val logger = Logging(actorSystem, this)

  override def identifier = 786

  override def toBinary(o: AnyRef) = o match {
    case _ => Array.emptyByteArray
  }

  override def includeManifest = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = ???
}
