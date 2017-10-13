package hochgi.assignment.pp.actors

import akka.actor.{Actor, Cancellable}
import scala.collection.mutable.{Map => MMap}

/**
  * mixin with an actor
  */
trait Cancellables { this: Actor =>

  protected[this] val cancellables: MMap[String,Cancellable] = MMap.empty

  protected[this] def cancelTimer(canelationID: String): Unit = {
    cancellables.get(canelationID).foreach { cancellable =>
      cancellable.cancel()
      cancellables -= canelationID
    }
  }

  protected[this] def addTimer(canelationID: String, cancellable: Cancellable): Unit = {
    cancelTimer(canelationID)
    cancellables += canelationID -> cancellable
  }
}
