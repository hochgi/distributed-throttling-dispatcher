package hochgi.assignment.pp.util

import java.nio.charset.StandardCharsets

import com.typesafe.config.ConfigFactory
import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory

/* TODO:
 * https://github.com/thomsonreuters/CM-Well/blob/b9c9d4001dca60741c4ba4c3b9e7159a5c465a0a/server/cmwell-util/src/main/scala/cmwell/util/string/package.scala#L96-L167
 * but with LZ4 (or ZSTD if JNI binding isn't a problem) instead of ZLIB
 */
object LZ4 {

  private lazy val lz4Factory = LZ4Factory.fastestJavaInstance()
  private lazy val xxhashFactory = XXHashFactory.fastestJavaInstance()
  private val maxMessageLength = 1 << 16 - 1
  private val config = ConfigFactory.load()
  private val hashSeed = config.getInt("hochgi.assignment.pp.serialization.hash-seed")

  def compress(data: String): Array[Byte] = {
    val bytes = data.getBytes(StandardCharsets.UTF_8)
    val length = bytes.length
    require(length <= maxMessageLength, "message is too big to compress")
    val c = lz4Factory.fastCompressor()
    val maxCompressedLength = c.maxCompressedLength(length)
    val compressed = new Array[Byte](maxCompressedLength)
    val cl = c.compress(bytes, 0, length, compressed, 0, maxCompressedLength)
    if (cl < length)
      addVerificationByte((length >> 8).toByte, length.toByte, compressed.take(cl))
    else
      addVerificationByte(0.toByte, 0.toByte, bytes)
  }

  def decompress(input: Array[Byte]): String = {
    val checksum = input.head
    val payload = input.tail
    require(verificationByte(payload) == checksum, "compressed data contains invalid checksum verification byte")
    val sizeHeader = payload.take(2)
    val compressed = payload.drop(2)
    val size = sizeHeader.foldLeft(0) { case (i, b) => (i << 8) | (255 & b) }
    if (size == 0) new String(compressed, StandardCharsets.UTF_8)
    else {
      val d = lz4Factory.fastDecompressor()
      val output = new Array[Byte](size)
      d.decompress(compressed, 0, output, 0, size)
//      new String(output, 0, uncompressedDataLength, encoding)
      new String(output, StandardCharsets.UTF_8)
    }
  }

  private def addVerificationByte(sizeHeaderMSB: Byte, sizeHeaderLSB: Byte, bytes: Array[Byte]) = {
    val payload = sizeHeaderMSB +: sizeHeaderLSB +: bytes
    val checksum = verificationByte(payload)
    checksum +: payload
  }

  def verificationByte(compressedWithLengthHeader: Array[Byte]): Byte =
    xxhashFactory.hash32().hash(compressedWithLengthHeader,0,compressedWithLengthHeader.length,hashSeed).toByte
}
