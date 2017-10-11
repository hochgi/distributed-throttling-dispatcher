package hochgi.assignment.pp

import akka.NotUsed
import akka.stream.scaladsl.Flow
import hochgi.assignment.pp.job.Request

import scala.concurrent.Future

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/11/17
  * Time: 4:25 AM
  */
object CloudQueryExecutor {

  def flow[T](request: Request => T): Flow[Request,T,NotUsed] = {
    ???
  }

  def asyncFlow[T](request: Request => Future[T]): Flow[Request,T,NotUsed] = {
    ???
  }
}
