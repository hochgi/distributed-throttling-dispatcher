package hochgi.assignment.pp

import java.nio.charset.StandardCharsets

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import com.typesafe.config.ConfigFactory
import hochgi.assignment.pp.CloudQueryExecutorActor.ThrottledRequest
import hochgi.assignment.pp.job.Request
import hochgi.assignment.pp.util._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/11/17
  * Time: 4:25 AM
  */
object CloudQueryExecutor {

  private val config = ConfigFactory.load()
  private val maxConcurrentRequests = config.getInt("hochgi.assignment.pp.throttling.worker.max-concurrent-requests")
  private val maxRequestIdLength = config.getInt("hochgi.assignment.pp.throttling.worker.max-request-id-length")
  private val ackTimeout = config.getDuration("hochgi.assignment.pp.throttling.ack-timeout")

  /**
    * @param executeRequest the request being throttled cluster-wise.
    *                       It MUST NOT run at the expense of the caller thread,
    *                       since it is called from within an actor.
    *                       In case of doubt, wrap your call with [[Future.apply()]]
    */
  def flow[T](system: ActorSystem, throttlingService: ActorRef)(executeRequest: Request => Future[T])(implicit ec: ExecutionContext): Graph[FlowShape[Request,T],NotUsed] =
    new CloudQueryExecutor(maxConcurrentRequests,maxRequestIdLength,ackTimeout,system,throttlingService,executeRequest)
}

class CloudQueryExecutor[T](maxConcurrentRequests: Int,
                            maxRequestIdLength: Int,
                            ackTimeout: FiniteDuration,
                            system: ActorSystem,
                            throttlingService: ActorRef,
                            executeRequest: Request => Future[T])(
                            implicit ec: ExecutionContext) extends GraphStage[FlowShape[Request, T]] {

  val in = Inlet[Request]("CloudQueryExecutor.in")
  val out = Outlet[T]("CloudQueryExecutor.out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler { self =>

      var cloudQueryExecutorActor: ActorRef = _
      var rollingID: String = base64Int(self.##)
      var concurrentJobsExecuted: Int = 0
      var pullWhenDone: Boolean = false

      override def preStart(): Unit = {

        // this async callback is needed to safely handle `executeRequest`
        // completion, which runs at the expense of the (implicitly) provided
        // execution context (within the `andThen` closure).
        val asyncCallback1: Try[T] => Unit = {
          val acb = getAsyncCallback[Try[T]] {
            case Failure(e) => failStage(new RuntimeException("executeRequest threw an exception", e))
            case Success(t) => {
              concurrentJobsExecuted -= 1
              push(out, t)
              if(pullWhenDone) {
                pull(in)
                pullWhenDone = false
              }
            }
          }
          acb.invoke
        }

        // this async callback is needed to safely handle our actor
        // execution of `executeRequest` which starts on the actor dispatcher.
        val asyncCallback2: Promise[Done] => Unit = {
          val acb = getAsyncCallback[Promise[Done]] { p =>
            executeRequest(grab(in)).andThen {
              case t@Failure(e) => {
                cloudQueryExecutorActor ! PoisonPill
                asyncCallback1(t)
              }
              case success => {
                p.success(Done)
                asyncCallback1(success)
              }
            }
          }
          acb.invoke
        }

        cloudQueryExecutorActor = system.actorOf(CloudQueryExecutorActor.props(maxRequestIdLength,
          ackTimeout,
          throttlingService,
          () => failStage(new RuntimeException("it appears that throttling service has been terminated")),
          () => {
            val p = Promise[Done]
            asyncCallback2(p)
            p.future
          }))
        super.preStart()
      }

      override def onPush(): Unit = {
        cloudQueryExecutorActor ! ThrottledRequest(rollingID)
        rollingID = base64Int(Hash(rollingID.getBytes(StandardCharsets.UTF_8)))
      }

      override def onPull(): Unit = {
        if(concurrentJobsExecuted < maxConcurrentRequests)
          pull(in)
        else pullWhenDone = true
      }

      setHandlers(in, out, this)
    }
}