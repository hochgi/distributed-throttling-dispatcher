package hochgi.assignment.pp

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.typesafe.config.ConfigFactory
import hochgi.assignment.pp.job.Request

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/11/17
  * Time: 4:25 AM
  */
object CloudQueryExecutor {

  val config = ConfigFactory.load()
  private val maxRequestIdLength = config.getInt("hochgi.assignment.pp.throttling.worker.max-request-id-length")

  def flow[T](executeRequest: Request => Future[T]): Flow[Request,T,NotUsed] = {
    ???
  }
}

import CloudQueryExecutor._

class CloudQueryExecutor[T](maxConcurrentRequests: Int,
                            system: ActorSystem,
                            throttlingService: ActorRef,
                            executeRequest: Request => Future[T]) extends GraphStage[FlowShape[Request, T]] {

  val in = Inlet[Request]("CloudQueryExecutor.in")
  val out = Outlet[T]("CloudQueryExecutor.out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      var cloudQueryExecutorActor: ActorRef = _

      override def preStart(): Unit = {
        cloudQueryExecutorActor = system.actorOf(CloudQueryExecutorActor.props(maxRequestIdLength,???,???,???,???))
        //TODO: getAsyncCallback() to inject .invoke to agent actor, which will execute body given as constructor arg
        //      body of invoke should contain `push(out, request(r))`
        super.preStart()
      }

      override def onPush(): Unit = {
        //TODO: send to actor Request = `grab(in)`
      }

      override def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }
}