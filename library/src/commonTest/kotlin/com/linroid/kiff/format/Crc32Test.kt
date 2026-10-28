package com.linroid.kiff.format

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {

  @Test
  fun matchesKnownVectors() {
    assertEquals(0x00000000u, Crc32.compute(ByteArray(0)))
    assertEquals(0xE8B7BE43u, Crc32.compute("a".encodeToByteArray()))
    assertEquals(0x352441C2u, Crc32.compute("abc".encodeToByteArray()))
    assertEquals(0xCBF43926u, Crc32.compute("123456789".encodeToByteArray()))
  }

  @Test
  fun honoursRange() {
    val data = "xxabcxx".encodeToByteArray()
    assertEquals(0x352441C2u, Crc32.compute(data, 2, 5))
  }

  /**
   * The platform's checksum and the slicing tables both agree with the plain table walk over
   * ranges of every length and alignment, including the tails shorter than one eight-byte step.
   */
  @Test
  fun agreesWithPlainTableWalkOverOddRanges() {
    val random = Random(0x6B696666)
    val data = random.nextBytes(4099)
    for (from in 0 until 24) {
      for (length in listOf(0, 1, 7, 8, 9, 15, 16, 17, 31, 33, 255, 1023, 1024, 1025, 4001)) {
        val to = from + length
        val expected = plain(data, from, to)
        assertEquals(expected, Crc32.compute(data, from, to), "platform, from=$from length=$length")
        assertEquals(
          expected,
          TableCrc32State.update(-1, data, from, to).inv().toUInt(),
          "tables, from=$from length=$length"
        )
      }
    }
  }

  /** Feeding the bytes in pieces of odd sizes gives the checksum of the whole. */
  @Test
  fun runningChecksumIsIndependentOfChunking() {
    val random = Random(42)
    val data = random.nextBytes(65_537)
    val expected = plain(data, 0, data.size)
    for (seed in 1..8) {
      val chunks = Random(seed)
      val running = Crc32.Running()
      val tables = TableCrc32State()
      var at = 0
      while (at < data.size) {
        val next = minOf(data.size, at + chunks.nextInt(1, 1000))
        running.update(data, at, next)
        tables.update(data, at, next)
        at = next
      }
      assertEquals(expected, running.value(), "platform, chunking $seed")
      assertEquals(expected, tables.value(), "tables, chunking $seed")
    }
  }

  /** The textbook one-byte-at-a-time table walk, kept here as the reference. */
  private fun plain(data: ByteArray, from: Int, to: Int): UInt {
    var crc = -1
    for (i in from until to) {
      crc = PLAIN_TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv().toUInt()
  }

  private companion object {
    val PLAIN_TABLE = IntArray(256) { index ->
      var c = index
      repeat(8) {
        c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
      }
      c
    }
  }
}
