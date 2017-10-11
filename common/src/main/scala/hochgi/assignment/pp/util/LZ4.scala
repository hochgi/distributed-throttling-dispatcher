package hochgi.assignment.pp.util

import net.jpountz.lz4.LZ4Factory

/* TODO:
 * https://github.com/thomsonreuters/CM-Well/blob/b9c9d4001dca60741c4ba4c3b9e7159a5c465a0a/server/cmwell-util/src/main/scala/cmwell/util/string/package.scala#L96-L167
 * but with LZ4 (or ZSTD if JNI binding isn't a problem) instead of ZLIB
 */
object LZ4 {
  lazy val lz4Factory = LZ4Factory.fastestJavaInstance()

  def compress(data: String): Array[Byte] = {
    val l = data.length
    val c = lz4Factory.fastCompressor()
    ???
  }
}
