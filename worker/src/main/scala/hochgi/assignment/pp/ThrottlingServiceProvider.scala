package hochgi.assignment.pp

import akka.actor.{Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/14/17
  * Time: 11:27 PM
  */
object ThrottlingServiceProvider {

  private val config = ConfigFactory.load()
  private val throttlingSystem = config.getString("hochgi.assignment.pp.throttling.actor-system")
  private val throttlingHostname = config.getString("hochgi.assignment.pp.throttling.service.hostname")
  private val throttlingPort = config.getString("hochgi.assignment.pp.throttling.service.port")
  private val throttlingActorName = config.getString("hochgi.assignment.pp.throttling.service.name")

  def getService(system: ActorSystem) = {
    // TODO: more resilient ref resolution
    system.actorSelection(s"akka.tcp://$throttlingSystem@$throttlingHostname:$throttlingPort/user/$throttlingActorName").resolveOne(5.minutes)
  }
}

// TODO: this actor will be used to schedule `Identify` messages periodically, until service is resolved.
// during deployment, workers may be deployed before throttling service, and we should wait indefinitely until
// throttling service is up and running
class ThrottlingServiceProvider extends Actor {
  override def receive = {
    ???
  }
}
