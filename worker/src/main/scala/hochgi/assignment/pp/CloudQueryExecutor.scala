package hochgi.assignment.pp

import java.nio.charset.StandardCharsets

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.stream._
import akka.stream.stage._
import com.typesafe.config.ConfigFactory
import hochgi.assignment.pp.CloudQueryExecutorActor.ThrottledRequest
import hochgi.assignment.pp.job.Request
import hochgi.assignment.pp.util._

import scala.collection.mutable.{Map => MMap, PriorityQueue => MPQueue}
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

      val prefetchedRequests: MMap[String,(Request,Int)] = MMap.empty[String,(Request,Int)]
      val completedRequests: MPQueue[(Int,T)] = MPQueue.empty(implicitly[Ordering[Int]].reverse.on[(Int,T)](_._1))
      var counter: Int = 0
      var emitNext: Int = 0
      var cloudQueryExecutorActor: ActorRef = _
      var rollingID: String = base64Int(self.##)
      var concurrentJobsExecuted: Int = 0
      var pullWhenDone: Boolean = false
      var emitOnCompletion: Boolean = false

      override def preStart(): Unit = {

        // eagerly pull up to `maxConcurrentRequests` requests
        pull(in)

        // this async callback is needed to safely handle `executeRequest`
        // completion, which runs at the expense of the (implicitly) provided
        // execution context (within the `andThen` closure).
        val asyncCallback1: ((Try[T],Int)) => Unit = {
          val acb = getAsyncCallback[(Try[T],Int)] {
            case (Failure(e),_) => failStage(new RuntimeException("executeRequest threw an exception", e))
            case (Success(t),o) => {
              completedRequests.enqueue(o -> t)
              if(emitOnCompletion) pushAndUpdateStateOrSetFlag()
            }
          }
          acb.invoke
        }

        // this async callback is needed to safely handle our actor
        // execution of `executeRequest` which starts on the actor dispatcher.
        val asyncCallback2: ((String,Promise[Done])) => Unit = {
          val acb = getAsyncCallback[(String,Promise[Done])] { case (id,p) =>
            val (r,ord) = prefetchedRequests(id)
            prefetchedRequests -= id
            executeRequest(r).andThen {
              case t@Failure(e) => {
                cloudQueryExecutorActor ! PoisonPill
                asyncCallback1(t -> ord)
              }
              case success => {
                p.success(Done)
                asyncCallback1(success -> ord)
              }
            }
          }
          acb.invoke
        }

        cloudQueryExecutorActor = system.actorOf(CloudQueryExecutorActor.props(maxRequestIdLength,
          ackTimeout,
          throttlingService,
          () => failStage(new RuntimeException("it appears that throttling service has been terminated")),
          (reqID: String) => {
            val p = Promise[Done]
            asyncCallback2(reqID -> p)
            p.future
          }))
        super.preStart()
      }

      override def onPush(): Unit = {
        cloudQueryExecutorActor ! ThrottledRequest(rollingID)
        prefetchedRequests += rollingID -> (grab(in) -> counter)
        counter += 1
        rollingID = base64Int(Hash(rollingID.getBytes(StandardCharsets.UTF_8)))
        concurrentJobsExecuted += 1
        if(concurrentJobsExecuted < maxConcurrentRequests) {
          pull(in)
        } else pullWhenDone = true
      }

      override def onPull(): Unit = pushAndUpdateStateOrSetFlag()

      def pushAndUpdateStateOrSetFlag() = {
        if(completedRequests.headOption.exists(_._1 == emitNext)) {
          push(out, completedRequests.dequeue()._2)
          emitOnCompletion = false
          emitNext += 1
          if(emitNext == (Int.MaxValue - concurrentJobsExecuted)) {
            emitNext = 0
            val old = completedRequests.toList
            completedRequests.clear()
            completedRequests.enqueue(old.map {
              case (i,t) =>
                (i - (Int.MaxValue - concurrentJobsExecuted)) -> t
            }:_*)
          }
          concurrentJobsExecuted -= 1
          if(pullWhenDone) {
            pull(in)
            pullWhenDone = false
          }
        }
        else emitOnCompletion = isAvailable(out)
      }

      setHandlers(in, out, this)
    }
}