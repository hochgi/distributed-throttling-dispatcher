package hochgi.assignment.pp

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object ThrottlingService extends App {
  val config = ConfigFactory.load()
  val actorSystemName = config.getString("hochgi.assignment.pp.throttling.actor-system")
  val actorSystem = ActorSystem(actorSystemName, config.getConfig("hochgi.assignment.pp.throttling.akka").withFallback(config))
}
