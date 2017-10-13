package hochgi.assignment.pp.util

import java.util.Base64

import com.typesafe.config.ConfigFactory
import net.jpountz.xxhash.XXHashFactory

object Hash {
  private lazy val xxhashFactory = XXHashFactory.fastestJavaInstance()
  private val config = ConfigFactory.load()
  private val hashSeed = config.getInt("hochgi.assignment.pp.serialization.hash-seed")

  def apply(bytes: Array[Byte]): Int = xxhashFactory.hash32().hash(bytes,0,bytes.length,hashSeed)

  /**
    * 3 bytes == 4 base64 chars,
    * since every 6 bits are encoded as a single char
    * This is useful for easy "salting" when you want
    * to resolve hash collisions.
    *
    * The motivation for salting, instead of simply double hashing,
    * is to avoid a fixed point or a small enough periodic cycle of the hash function
    *
    * @param i an int to salt a string, can be acquired using [[apply(bytes)]]
    * @return
    */
  def base643LSB(i: Int): String = {
    var h = i
    val b0 = h.toByte
    h >>= 8
    val b1 = h.toByte
    h >>= 8
    val b2 = h.toByte
    Base64.getEncoder.encodeToString(Array(b0, b1, b2))
  }
}
