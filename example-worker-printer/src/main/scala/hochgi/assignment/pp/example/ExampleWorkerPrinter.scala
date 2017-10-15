package hochgi.assignment.pp.example

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.stream.scaladsl.{GraphDSL, Keep, RunnableGraph, Sink, UnzipWith, Zip}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import hochgi.assignment.pp.job.Job
import hochgi.assignment.pp.{CloudQueryExecutor, JobsConsumer, ThrottlingServiceProvider}

import scala.concurrent.duration.DurationInt
import scala.concurrent.Promise
import scala.util.{Failure, Success}

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/15/17
  * Time: 10:34 AM
  */
object ExampleWorkerPrinter extends App with LazyLogging {

  val config = ConfigFactory.load()
  implicit val system = ActorSystem("example-worker-printer", config.getConfig("hochgi.assignment.pp.throttling.akka").withFallback(config))
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val doneFuture = ThrottlingServiceProvider.getService(system).flatMap { throttlingService =>

    val g = GraphDSL.create(JobsConsumer.source(config), Sink.ignore)(Keep.right) {
      implicit b => {
        (source, sink) => {

          import GraphDSL.Implicits._

          val unzip = b.add(UnzipWith((msg: CommittableMessage[String, Job]) => (msg.committableOffset, msg.record.value().cloudCall.get)))
          val throttledExecution = b.add(CloudQueryExecutor.flow(system, throttlingService) { req =>
            //you'll want to do some actual cloud call here
            // but ignorer just ignores.

            // let's simulate an actual request time
            val millis = req.hashCode % 1024
            println(s"printing a request that should take ${millis}ms")
            val p = Promise[Done]
            system.scheduler.scheduleOnce(millis.millis)(p.success(Done))
            p.future.andThen {
              case _ => println(s"request completed after ${millis}ms")
            }
          })
          val zip = b.add(Zip[CommittableOffset, Done])

          source ~> unzip.in
                    unzip.out0            ~>            zip.in0
                    unzip.out1 ~> throttledExecution ~> zip.in1
                                                        zip.out.mapAsync(1)(_._1.commitScaladsl()) ~> sink

          ClosedShape
        }
      }
    }

    RunnableGraph.fromGraph(g).run()
  }

  // in a real world scenario, this will never end
  doneFuture.onComplete{t =>
    t match {
      case Failure(fail) => logger.error("Encountered a problem",fail)
      case Success(Done) => logger.info("Execution completed successfully.")
    }
    materializer.shutdown()
    system.terminate()
  }
}
