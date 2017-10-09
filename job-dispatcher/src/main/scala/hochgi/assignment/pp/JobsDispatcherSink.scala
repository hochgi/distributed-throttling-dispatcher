package hochgi.assignment.pp

import akka.stream.SinkShape
import akka.stream.stage.GraphStage
import com.typesafe.config.Config

case class JobsDispatcherSink(config: Config) extends GraphStage[SinkShape[Job]] {



}
