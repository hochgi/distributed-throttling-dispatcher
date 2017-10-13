package hochgi.assignment.pp.util

import net.jpountz.lz4.LZ4Factory

// TODO: if JNI binding isn't a problem we can use ZSTD instead of LZ4
object LZ4 {

  private lazy val lz4Factory = LZ4Factory.fastestJavaInstance()
  private val maxMessageLength = 1 << 16 - 1

  def compress(bytes: Array[Byte]): Array[Byte] = {
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

  def decompress(input: Array[Byte]): Array[Byte] = {
    val checksum = input.head
    val payload = input.tail
    require(verificationByte(payload) == checksum, "compressed data contains invalid checksum verification byte")
    val sizeHeader = payload.take(2)
    val compressed = payload.drop(2)
    val size = sizeHeader.foldLeft(0) { case (i, b) => (i << 8) | (255 & b) }
    if (size == 0) compressed
    else {
      val d = lz4Factory.fastDecompressor()
      val output = new Array[Byte](size)
      d.decompress(compressed, 0, output, 0, size)
      output
    }
  }

  private def addVerificationByte(sizeHeaderMSB: Byte, sizeHeaderLSB: Byte, bytes: Array[Byte]) = {
    val payload = sizeHeaderMSB +: sizeHeaderLSB +: bytes
    val checksum = verificationByte(payload)
    checksum +: payload
  }

  def verificationByte(compressedWithLengthHeader: Array[Byte]): Byte = {
    Hash(compressedWithLengthHeader).toByte
  }
}
