package hochgi.assignment.pp

import java.nio.charset.StandardCharsets

import akka.Done
import akka.pattern.pipe
import akka.actor.{Actor, ActorRef, PoisonPill, Props, Terminated}
import com.typesafe.config.Config
import hochgi.assignment.pp.actors.Cancellables
import hochgi.assignment.pp.throttle._
import hochgi.assignment.pp.util.Hash

import scala.collection.mutable.{Map => MMap}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object CloudQueryExecutorActor {

  def props(maxRequestIdLength: Int,
            ackTimeout: FiniteDuration,
            throttlingService: ActorRef,
            shutdown: () => Unit,
            invokeOne: String => Future[Done]): Props = {
    Props(new CloudQueryExecutorActor(maxRequestIdLength,
                                      ackTimeout,
                                      throttlingService,
                                      shutdown,
                                      invokeOne)(
                                      ExecutionContext.global)) // in a real-world app, `global` might not be a good execution context
  }

  final case class ThrottledRequest(reqID: String)
  final case class ExecutionDone(reqID: String)

  sealed trait RequestState
  case object Initiating extends RequestState
  case object Pending extends RequestState
  case object Executing extends RequestState
  case object Finished extends RequestState
}

import CloudQueryExecutorActor._

class CloudQueryExecutorActor private(maxRequestIdLength: Int,
                                      ackTimeout: FiniteDuration,
                                      throttlingService: ActorRef,
                                      shutdown: () => Unit,
                                      invokeOne: String => Future[Done])(
                                      implicit executionContext: ExecutionContext) extends Actor with Cancellables {

  // stateMap is currently used only for ack resends,
  // but could be exposed (e.g. JMX) to allow inspection of worker state
  private val stateMap = MMap.empty[String,RequestState]
  private val reqIdMap = MMap.empty[String,String]


  override def preStart(): Unit = {
    context.watch(throttlingService)
    super.preStart()
  }

  override def receive = {
    case Terminated(`throttlingService`) => handleTermination
    case ThrottledRequest(reqID) => handleNewThrottledRequest(reqID)
    case ExecutionDone(reqID) => handleExecutionDone(reqID)
    case ric: RequestIdCollision => handleCollision(ric.id)
    case ra: RequestAck if ra.canExecute => handlePermissionToExecute(ra.forRequestID,AckAck(ra.forRequestID))
    case ra: RequestAck => handleRequestAck(ra.forRequestID)
    case pte: PermissionToExecute => handlePermissionToExecute(pte.forRequestID,PermissionToExecuteAck(pte.forRequestID))
    case eca: ExecutionCompletedAck => handleExecutionCompletedAck(eca.forRequestID)
  }

  /**
    * In a real world app, we would probably handle it differently...
    */
  private def handleTermination: Unit = {
    shutdown()
    self ! PoisonPill
  }

  private def handleNewThrottledRequest(reqID: String): Unit = {
    val r = Request(reqID)
    throttlingService ! r
    val cancellable = context.system.scheduler.schedule(ackTimeout,ackTimeout,throttlingService,r)
    addTimer(reqID, cancellable)
    reqIdMap += reqID -> reqID
    stateMap += reqID -> Initiating
  }

  private def handleCollision(reqID: String): Unit = {
    val hash = Hash(reqID.getBytes(StandardCharsets.UTF_8))
    val salt = Hash.base643LSB(hash)
    val newReqID = {
      // in case of many collisions, we don't want our messages
      // to keep inflating.
      if (reqID.length > maxRequestIdLength) salt
      else salt + reqID
    }
    reqIdMap.get(reqID).foreach{ origReqID =>
      reqIdMap -= reqID
      reqIdMap += newReqID -> origReqID
    }
    cancelTimer(reqID)
    stateMap -= reqID
    handleNewThrottledRequest(newReqID)
  }

  private def handleRequestAck(reqID: String): Unit = {
    if(stateMap(reqID) eq Initiating) {
      cancelTimer(reqID)
      stateMap += reqID -> Pending
    }
    throttlingService ! AckAck
  }

  private def handlePermitExecution(reqID: String): Unit = {
    cancelTimer(reqID)
    stateMap += reqID -> Executing
    invokeOne(reqIdMap(reqID)).map { _ =>
      ExecutionDone(reqID)
    }.pipeTo(self)
  }

  private def handleExecutionDone(reqID: String): Unit = {
    throttlingService ! ExecutionCompleted(reqID)
    val cancellable = context.system.scheduler.schedule(ackTimeout,ackTimeout,throttlingService,ExecutionCompleted(reqID))
    addTimer(reqID, cancellable)
    stateMap += reqID -> Finished
  }

  private def handleExecutionCompletedAck(reqID: String): Unit = {
    cancelTimer(reqID)
    stateMap -= reqID
  }

  private def handlePermissionToExecute(reqID: String, ack: ThrottlingMessage): Unit = {
    throttlingService ! ack
    stateMap.get(reqID) match {
      case Some(Initiating | Pending) => handlePermitExecution(reqID)
      case _ => // Do Nothing (other than ack-ing which we already did)
    }
  }
}
