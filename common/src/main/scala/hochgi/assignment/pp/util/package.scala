package hochgi.assignment.pp

import java.util.Base64

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * Proj: jobs-throttling-dispatcher
  * User: gilad
  * Date: 10/14/17
  * Time: 9:26 PM
  */
package object util {

  implicit def toFiniteDuration(d: java.time.Duration): FiniteDuration = Duration.fromNanos(d.toNanos)

  def base64Int(i: Int): String = {
    var h = i
    val b0 = h.toByte
    h >>= 8
    val b1 = h.toByte
    h >>= 8
    val b2 = h.toByte
    h >>= 8
    val b3 = h.toByte
    Base64.getEncoder.encodeToString(Array(b0, b1, b2, b3))
  }
}
