package hochgi.assignment.pp.example

import java.nio.charset.StandardCharsets

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import hochgi.assignment.pp.JobsDispatcher
import hochgi.assignment.pp.job.{Job, Method, Request}
import hochgi.assignment.pp.job.WorkerType.{Ignorer, Printer}
import hochgi.assignment.pp.util._

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ExampleJobDispatcher extends App with LazyLogging  {

  val config = ConfigFactory.load()
  implicit val system = ActorSystem("example-job-dispatcher", config.getConfig("hochgi.assignment.pp.throttling.akka").withFallback(config))
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  val startTime = System.currentTimeMillis()

  val source = Source.unfoldAsync(Int.MaxValue) { signedInt =>

    val shouldStop = signedInt % 1729 == 0 // should take a little over 2K iterations
    val nanos = ((1L << 32) - 1) & signedInt // average in nanos, is a little over 2 seconds

    // the whole source should complete in about 40 minutes give or take...

    val p = Promise[Option[(Int,(String,Boolean))]]
    system.scheduler.scheduleOnce(nanos.nanos) {
      if(shouldStop) p.success(None)
      else {
        val str = base64Int(signedInt)
        val next = Hash(str.getBytes(StandardCharsets.UTF_8))
        val workerType = signedInt % 2 == 0
        p.success(Some(next -> (str -> workerType)))
      }
    }
    p.future
  }

  val doneFuture = source.map {
    case (randomString,shouldIgnore) =>
      val wType = {
        if (shouldIgnore) Ignorer
        else Printer
      }
      // you'll want an actual request here...
      val r: Option[Request] = Some(Request(s"http://example.com/$randomString",Method.Get,None,Vector.empty,Vector.empty))
      // can be used to describe any other work the worker should perform other than throttled cloud calls.
      // if both (request & extra) exist, you can use non optional API
      val extra: Option[String] = None
      Job(randomString,wType,r,extra)
  }.runWith(JobsDispatcher.sink(config, () => {
    case Ignorer => 0
    case Printer => 1
  })(system))

  doneFuture.onComplete{t =>
    val now = System.currentTimeMillis()
    t match {
      case Failure(fail) => logger.error("Encountered a problem",fail)
      case Success(Done) => logger.info(s"Execution completed successfully. Time spent: ${now - startTime}ms.")
    }
    materializer.shutdown()
    system.terminate()
  }
}
