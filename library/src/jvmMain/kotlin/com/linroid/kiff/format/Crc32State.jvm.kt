package com.linroid.kiff.format

import java.util.zip.CRC32

/** The JDK's own CRC-32, which runs as native code and several times faster than the tables. */
internal actual class Crc32State actual constructor() {
  private val crc = CRC32()

  actual fun update(data: ByteArray, from: Int, to: Int) {
    crc.update(data, from, to - from)
  }

  actual fun value(): UInt = crc.value.toUInt()
}
