package com.linroid.kiff.format

import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readFully

/** Standard CRC-32 (IEEE 802.3, the polynomial used by zip and gzip). */
internal object Crc32 {

  fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): UInt {
    val state = Crc32State()
    state.update(data, from, to)
    return state.value()
  }

  /**
   * Checksums a whole source a chunk at a time, so a file need not be held in memory for it.
   *
   * Every byte the source claims is read or the call fails: a source that ended early used to stop
   * the loop quietly, and a checksum of a prefix passes for one of the whole.
   */
  fun compute(source: SeekableSource): UInt {
    val buffer = ByteArray(CHUNK)
    val state = Crc32State()
    var position = 0L
    val size = source.size
    while (position < size) {
      val count = minOf(CHUNK.toLong(), size - position).toInt()
      source.readFully(position, buffer, 0, count)
      state.update(buffer, 0, count)
      position += count
    }
    return state.value()
  }

  /**
   * A checksum computed as the bytes go past, for a restore that never holds them all at once.
   *
   * Two run at a time while a patch is applied: one over the whole target, and one that is started
   * and finished around each region so a failure can name where it happened.
   */
  class Running {
    private val state = Crc32State()

    fun update(data: ByteArray, from: Int, to: Int) {
      state.update(data, from, to)
    }

    fun value(): UInt = state.value()
  }

  private const val CHUNK = 1 shl 16
}

/**
 * The CRC-32 of the bytes given so far, computed the fastest way the platform has.
 *
 * Applying a patch checksums the source once and every restored byte twice, so this is most of
 * what an apply costs. The JVM delegates to its own native implementation; everywhere else it is
 * [TableCrc32State], eight bytes per step rather than one.
 */
internal expect class Crc32State() {
  fun update(data: ByteArray, from: Int, to: Int)

  /** The checksum of everything given so far; more bytes may still follow. */
  fun value(): UInt
}

/**
 * CRC-32 by the "slicing-by-8" method: eight table lookups fold eight bytes into the register at
 * once, where the plain table walk folds one, and the lookups of a step are independent of each
 * other so the processor can overlap them.
 */
internal class TableCrc32State {
  private var crc = -1

  fun update(data: ByteArray, from: Int, to: Int) {
    crc = update(crc, data, from, to)
  }

  fun value(): UInt = crc.inv().toUInt()

  companion object {
    private const val SLICES = 8

    /**
     * Eight tables of 256 entries, laid end to end. Table 0 is the plain byte-at-a-time table;
     * table k gives what a byte contributes to the register once k more bytes have been folded in
     * after it, so the eight bytes of a step can each be looked up from the register as it was
     * before the step.
     */
    private val tables = IntArray(SLICES * 256).also { t ->
      for (i in 0 until 256) {
        var c = i
        repeat(8) {
          c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        t[i] = c
      }
      for (k in 1 until SLICES) {
        for (i in 0 until 256) {
          val previous = t[(k - 1) * 256 + i]
          t[k * 256 + i] = (previous ushr 8) xor t[previous and 0xFF]
        }
      }
    }

    /** Folds `data[from, to)` into [seed], the register as it stands with no final inversion. */
    fun update(seed: Int, data: ByteArray, from: Int, to: Int): Int {
      val t = tables
      var crc = seed
      var i = from
      while (to - i >= 8) {
        val low = crc xor (
          (data[i].toInt() and 0xFF) or
            ((data[i + 1].toInt() and 0xFF) shl 8) or
            ((data[i + 2].toInt() and 0xFF) shl 16) or
            (data[i + 3].toInt() shl 24)
          )
        val high =
          (data[i + 4].toInt() and 0xFF) or
            ((data[i + 5].toInt() and 0xFF) shl 8) or
            ((data[i + 6].toInt() and 0xFF) shl 16) or
            (data[i + 7].toInt() shl 24)
        crc = t[7 * 256 + (low and 0xFF)] xor
          t[6 * 256 + ((low ushr 8) and 0xFF)] xor
          t[5 * 256 + ((low ushr 16) and 0xFF)] xor
          t[4 * 256 + (low ushr 24)] xor
          t[3 * 256 + (high and 0xFF)] xor
          t[2 * 256 + ((high ushr 8) and 0xFF)] xor
          t[1 * 256 + ((high ushr 16) and 0xFF)] xor
          t[high ushr 24]
        i += 8
      }
      while (i < to) {
        crc = t[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        i++
      }
      return crc
    }
  }
}

/** Eight lowercase hex digits, the way a CRC-32 is conventionally shown. */
internal fun UInt.toHex(): String = toString(16).padStart(8, '0')
