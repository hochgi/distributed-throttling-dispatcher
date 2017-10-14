package hochgi.assignment.pp

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import hochgi.assignment.pp.util.toFiniteDuration

object ThrottlingService extends App {
  val config = ConfigFactory.load()
  val actorSystemName = config.getString("hochgi.assignment.pp.throttling.actor-system")
  val system = ActorSystem(actorSystemName, config.getConfig("hochgi.assignment.pp.throttling.akka").withFallback(config))
  val ackTimeout = config.getDuration("hochgi.assignment.pp.throttling.ack-timeout")
  val maxConcurrentRequests = config.getInt("hochgi.assignment.pp.throttling.service.max-concurrent-requests")
  val concurrentRequestsSlidingWindow = config.getDuration("hochgi.assignment.pp.throttling.service.concurrent-requests-sliding-window")
  val dailyQuota = config.getInt("hochgi.assignment.pp.throttling.service.daily-quota")
  val dailyQuotaSlidingWindowSegmentMinutes = config.getInt("hochgi.assignment.pp.throttling.service.daily-quota-sliding-window-segment-minutes")
  val actorName = config.getString("hochgi.assignment.pp.throttling.service.name")
  system.actorOf(ThrottlingServiceActor.props(
    ackTimeout,
    maxConcurrentRequests,
    concurrentRequestsSlidingWindow,
    dailyQuota,
    dailyQuotaSlidingWindowSegmentMinutes),actorName)
}
