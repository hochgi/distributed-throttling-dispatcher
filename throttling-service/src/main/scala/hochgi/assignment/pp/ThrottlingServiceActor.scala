package hochgi.assignment.pp

import akka.actor.{Actor, ActorRef, Cancellable, Props, Terminated}
import hochgi.assignment.pp.actors.Cancellables
import hochgi.assignment.pp.throttle._

import scala.collection.mutable.{Map => MMap, Queue => MQueue, Set => MSet}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object ThrottlingServiceActor {

  val minutesInDay: Int = 24 * 60

  final case object SlideTimeSegment
  final case object CoolOffTimeout

  def props(ackTimeout: FiniteDuration,
            concurrentRequests: Int,
            concurrentRequestsSlidingWindow: FiniteDuration,
            dailyQuota: Int,
            dailyQuotaSlidingWindowSegmentMinutes: Int): Props = {

    require(minutesInDay % dailyQuotaSlidingWindowSegmentMinutes == 0,
      "daily quota sliding window segment must divide number of minutes in a day (1440)")

    Props(new ThrottlingServiceActor(ackTimeout,
      concurrentRequests,
      concurrentRequestsSlidingWindow,
      dailyQuota,
      dailyQuotaSlidingWindowSegmentMinutes)(
      ExecutionContext.global)) // in a real-world app, `global` might not be a good execution context
  }
}

import ThrottlingServiceActor._

class ThrottlingServiceActor private(ackTimeout: FiniteDuration,
                                     concurrentRequests: Int,
                                     concurrentRequestsSlidingWindow: FiniteDuration,
                                     dailyQuota: Int,
                                     dailyQuotaSlidingWindowSegmentMinutes: Int)(
                                     implicit executionContext: ExecutionContext) extends Actor with Cancellables {

  private var concurrentQuotaWithCoolOffCounter = 0
  private var dailyQuotaSlidingWindowIndex = 0
  private var dailyTotal = 0
  private val dailyQuotaWindows = Array.fill(minutesInDay / dailyQuotaSlidingWindowSegmentMinutes)(0)
  private val pendingExecution = MQueue.empty[(ActorRef,String)]
  private val executing = MMap.empty[String,ActorRef]
  private val pending = MSet.empty[String]
  private val watchedWorkers = MSet.empty[ActorRef]

  override def preStart(): Unit = {
    val d = dailyQuotaSlidingWindowSegmentMinutes.minutes
    context.system.scheduler.schedule(d,d,self,SlideTimeSegment)
    super.preStart()
  }

  override def receive = {
    case r:    Request                => handleNewRequest(sender(),r.id)
    case aa:   AckAck                 => cancelTimer(aa.forRequestID)
    case ptea: PermissionToExecuteAck => cancelTimer(ptea.forRequestID)
    case ec:   ExecutionCompleted     => handleExecutionCompleted(ec.forRequestID)
    case Terminated(aref)                     => handleTerminated(aref)
    case SlideTimeSegment                     => slideTimeSegment
    case CoolOffTimeout                       => handleCoolOffTimeout
  }

  private def handleNewRequest(aref: ActorRef, reqID: String): Unit = {
    if(!cancellables.contains(reqID)) {
      if (requestCollision(reqID)) aref ! RequestIdCollision(reqID)
      else {
        if(!watchedWorkers(aref)) {
          context.watch(aref)
          watchedWorkers += aref
        }
        val cesr = canExecuteSingleRequest
        val cancellable = context.system.scheduler.schedule(ackTimeout, ackTimeout, aref, RequestAck(reqID, cesr))
        addTimer(reqID, cancellable)
        if (cesr) activate(reqID, aref)
        else {
          pending += reqID
          pendingExecution.enqueue(aref -> reqID)
        }
        aref ! RequestAck(reqID, cesr)
      }
    }
    // else: Do nothing. Worker probably did not receive RequestAck,
    //       so it tries to resend request, but we already got the request.
    //       Since worker probably did not reply with AckAck,
    //       timer will cause us to resend RequestAck after `ackTimeout` elapses.
  }

  private def activate(reqID: String, aref: ActorRef): Unit = {
    executing += reqID -> aref
    dailyTotal += 1
    dailyQuotaWindows(dailyQuotaSlidingWindowIndex) += 1
    concurrentQuotaWithCoolOffCounter += 1
  }

  private def canExecuteSingleRequest: Boolean = {
    dailyTotal < dailyQuota &&
    concurrentQuotaWithCoolOffCounter < concurrentRequests
  }

  private def requestCollision(reqID: String): Boolean = {
    executing.contains(reqID) || pending(reqID)
  }

  private def handleExecutionCompleted(reqID: String): Unit = {
    cancelTimer(reqID) // Worker may have omitted PermissionToExecuteAck
    executing.get(reqID).fold(sender() ! ExecutionCompletedAck(reqID)){ aref =>
      executing -= reqID
      context.system.scheduler.scheduleOnce(concurrentRequestsSlidingWindow, self, CoolOffTimeout)
      aref ! ExecutionCompletedAck(reqID)
    }
  }

  private def handleCoolOffTimeout: Unit = {
    concurrentQuotaWithCoolOffCounter -= 1
    if(canPermit) permitOne
  }

  private def handleTerminated(aref: ActorRef): Unit = {
    watchedWorkers -= aref
    context.unwatch(aref)
    executing.find(_._2 == aref).fold(pendingExecution.foreach {
        case (`aref`,reqID) => {
          cancelTimer(reqID)
          pending -= reqID
        }
        case _ => // DO NOTHING
      }) {
      case (reqID,_) => {
        cancelTimer(reqID)
        executing -= reqID
        context.system.scheduler.scheduleOnce(concurrentRequestsSlidingWindow, self, CoolOffTimeout)
      }
    }
  }

  private def slideTimeSegment: Unit = {
    dailyQuotaSlidingWindowIndex = (dailyQuotaSlidingWindowIndex + 1) % dailyQuotaWindows.length
    dailyTotal -= dailyQuotaWindows(dailyQuotaSlidingWindowIndex)
    dailyQuotaWindows(dailyQuotaSlidingWindowIndex) = 0
    while(canPermit) permitOne
  }

  private def canPermit: Boolean = canExecuteSingleRequest && pendingExecution.nonEmpty

  private def permitOne: Unit = {
    val (aref,reqID) = pendingExecution.dequeue()
    aref ! PermissionToExecute(reqID)
    val cancellable = context.system.scheduler.schedule(ackTimeout, ackTimeout, aref, PermissionToExecute(reqID))
    addTimer(reqID, cancellable)
    activate(reqID, aref)
  }
}
