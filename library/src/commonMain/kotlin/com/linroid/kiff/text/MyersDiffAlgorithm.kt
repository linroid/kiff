package com.linroid.kiff.text

/**
 * Myers' difference algorithm, in its linear-space form: a shortest edit script in O((N+M)D) time
 * and O(N+M) memory, where D is the number of lines deleted and inserted.
 *
 * The textbook form keeps the frontier of every step it takes so it can walk back along the path it
 * found, and that is memory in the square of D: a few thousand changed lines is gigabytes, which
 * a patch encoder meets on an ordinary regenerated text file. This one searches from both ends at
 * once until the two searches meet in the middle of a shortest path, then solves each half the same
 * way, so it only ever holds two frontiers.
 *
 * Time is the other square. D is the number of lines a script changes, so two files that share
 * little take time in the product of their size and their difference - minutes for a
 * million-line file that was rewritten. [diff] takes GNU diff's two ways out: a line the other
 * file does not have anywhere is deleted or inserted without being searched for, which is what
 * makes two unrelated files cost one pass; and a search that has gone on past what a file of that
 * size ought to need stops at the furthest it has reached and splits there, which gives a script
 * that is correct but may be longer than the shortest. [diffWithin] does neither: its budget
 * bounds the search already, and the patch encoder relies on its script being a shortest one.
 *
 * Within each run of changes, deletions come before insertions, which is the order unified diffs
 * print them in.
 */
public class MyersDiffAlgorithm : LineDiffAlgorithm {
  override val name: String = "myers"

  override fun diff(source: List<String>, target: List<String>): List<Edit> {
    val ids = Ids(source, target)
    val keptA = ids.sharedPositions(ids.a, ids.inB)
    val keptB = ids.sharedPositions(ids.b, ids.inA)
    if (keptA.size == source.size && keptB.size == target.size) {
      return checkNotNull(Search(ids.a, ids.b, target, bounded = true).run(Int.MAX_VALUE))
    }
    // Searched over the lines both files have, then widened: the lines only one file has are
    // where they were, and every script deletes or inserts each of them whatever the search does.
    val reduced = Search(
      IntArray(keptA.size) { ids.a[keptA[it]] },
      IntArray(keptB.size) { ids.b[keptB[it]] },
      target = null,
      bounded = true
    ).run(Int.MAX_VALUE)
    return widen(checkNotNull(reduced), keptA, keptB, source.size, target)
  }

  /** Gives up as soon as the search knows the script is longer than [maxEdits]. */
  override fun diffWithin(source: List<String>, target: List<String>, maxEdits: Int): List<Edit>? {
    val ids = Ids(source, target)
    return Search(ids.a, ids.b, target, bounded = false).run(maxEdits)
  }

  /** Lines as small numbers, so comparing two costs the same however long they are. */
  private class Ids(source: List<String>, target: List<String>) {
    val a: IntArray
    val b: IntArray
    val inA: BooleanArray
    val inB: BooleanArray

    init {
      val ids = HashMap<String, Int>()
      a = IntArray(source.size) { ids.getOrPut(source[it]) { ids.size } }
      b = IntArray(target.size) { ids.getOrPut(target[it]) { ids.size } }
      inA = BooleanArray(ids.size).also { seen -> for (id in a) seen[id] = true }
      inB = BooleanArray(ids.size).also { seen -> for (id in b) seen[id] = true }
    }

    /** The positions in [lines] whose line the other file has somewhere, in order. */
    fun sharedPositions(lines: IntArray, other: BooleanArray): IntArray {
      var count = 0
      for (id in lines) if (other[id]) count++
      val kept = IntArray(count)
      var at = 0
      for (i in lines.indices) if (other[lines[i]]) kept[at++] = i
      return kept
    }
  }

  /**
   * [reduced], a script over the shared lines at [keptA] and [keptB], as a script over the whole
   * files: each line the search left out is deleted or inserted where it stood.
   */
  private fun widen(
    reduced: List<Edit>,
    keptA: IntArray,
    keptB: IntArray,
    sourceSize: Int,
    target: List<String>
  ): List<Edit> {
    val script = Script(target)
    var ia = 0
    var ib = 0
    var ra = 0
    var rb = 0
    // Deletes the source lines before the next one the search kept, and likewise inserts.
    fun reachA() {
      val next = keptA[ra]
      script.delete(next - ia)
      ia = next
    }
    fun reachB() {
      val next = keptB[rb]
      script.insert(next - ib)
      ib = next
    }
    for (edit in reduced) {
      when (edit) {
        is Edit.Equal -> repeat(edit.count) {
          reachA()
          reachB()
          script.equal(1)
          ia++; ib++; ra++; rb++
        }
        is Edit.Delete -> repeat(edit.count) {
          reachA()
          script.delete(1)
          ia++; ra++
        }
        is Edit.Insert -> repeat(edit.lines.size) {
          reachB()
          script.insert(1)
          ib++; rb++
        }
      }
    }
    script.delete(sourceSize - ia)
    script.insert(target.size - ib)
    return script.finish()
  }

  /**
   * Collects a script as runs: equal lines, then within a run of changes the deletions before the
   * insertions.
   */
  private class Script(private val target: List<String>?) {
    private val edits = ArrayList<Edit>()
    private var sourceAt = 0
    private var targetAt = 0
    private var equal = 0
    private var deleted = 0
    private var inserted = 0

    fun equal(count: Int) {
      if (count == 0) return
      flushChange()
      equal += count
    }

    fun delete(count: Int) {
      if (count == 0) return
      flushEqual()
      deleted += count
    }

    fun insert(count: Int) {
      if (count == 0) return
      flushEqual()
      inserted += count
    }

    fun finish(): List<Edit> {
      flushEqual()
      flushChange()
      return edits
    }

    private fun flushEqual() {
      if (equal == 0) return
      edits.add(Edit.Equal(sourceAt, equal))
      sourceAt += equal
      targetAt += equal
      equal = 0
    }

    private fun flushChange() {
      if (deleted > 0) {
        edits.add(Edit.Delete(sourceAt, deleted))
        sourceAt += deleted
        deleted = 0
      }
      if (inserted > 0) {
        // A search over reduced lines has no text to give, only a count; widening fills it in.
        val lines =
          target?.subList(targetAt, targetAt + inserted)?.toList() ?: List(inserted) { "" }
        edits.add(Edit.Insert(sourceAt, lines))
        targetAt += inserted
        inserted = 0
      }
    }
  }

  /**
   * The search itself, over lines as numbers. [bounded] lets a search that has run past
   * [tooExpensive] steps settle for the furthest point it reached rather than a shortest path.
   */
  private class Search(
    private val a: IntArray,
    private val b: IntArray,
    target: List<String>?,
    private val bounded: Boolean
  ) {
    private val script = Script(target)

    fun run(maxEdits: Int): List<Edit>? {
      if (!solve(0, a.size, 0, b.size, maxEdits)) return null
      return script.finish()
    }

    /**
     * Emits a shortest script for `a[aFrom, aTo)` against `b[bFrom, bTo)`, or answers false when
     * it would need more than [budget] edits.
     *
     * Only the outermost call has a budget. Once that search has met in the middle the whole cost
     * is known, and the halves below it cost no more than it together.
     */
    private fun solve(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, budget: Int): Boolean {
      var aStart = aFrom
      var bStart = bFrom
      while (aStart < aTo && bStart < bTo && a[aStart] == b[bStart]) {
        aStart++
        bStart++
      }
      var aEnd = aTo
      var bEnd = bTo
      while (aEnd > aStart && bEnd > bStart && a[aEnd - 1] == b[bEnd - 1]) {
        aEnd--
        bEnd--
      }
      val n = aEnd - aStart
      val m = bEnd - bStart
      when {
        n == 0 || m == 0 -> {
          if (n.toLong() + m > budget) return false
          script.equal(aStart - aFrom)
          script.delete(n)
          script.insert(m)
        }
        else -> {
          val middle = middle(aStart, aEnd, bStart, bEnd, budget) ?: return false
          script.equal(aStart - aFrom)
          solve(aStart, middle.x, bStart, middle.y, Int.MAX_VALUE)
          solve(middle.x, aEnd, middle.y, bEnd, Int.MAX_VALUE)
        }
      }
      script.equal(aTo - aEnd)
      return true
    }

    /**
     * A point on a shortest path through `a[aFrom, aTo)` and `b[bFrom, bTo)` that splits its edits
     * roughly in half, or null when that path is longer than [budget].
     *
     * Both ranges are non-empty and share no first or last line, which is what guarantees the point
     * is strictly inside the path, so each half is a smaller problem. The forward search runs from
     * the start and the reverse one from the end, a step each in turn, until one reaches a diagonal
     * the other has already gone past.
     *
     * A [bounded] search that has taken [tooExpensive] steps without meeting answers the furthest
     * point either side reached instead. That point is on some path, just not a shortest one, and
     * it is strictly inside the ranges for the same reason: a step from a shared-nothing start
     * always moves.
     */
    private fun middle(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, budget: Int): Point? {
      val n = aTo - aFrom
      val m = bTo - bFrom
      val steps = (n + m + 1) / 2
      val offset = steps
      val size = 2 * steps + 2
      // How far along `a` each diagonal k (= x - y) has reached, from each end; -1 if not yet.
      val forward = IntArray(size) { -1 }
      val reverse = IntArray(size) { -1 }
      forward[offset + 1] = 0
      reverse[offset + 1] = 0
      val delta = n - m
      // With an odd difference the searches meet on a forward step, with an even one on a reverse.
      val odd = delta % 2 != 0
      // Diagonals that have run off the edge of the grid need no further steps.
      var forwardStart = 0
      var forwardEnd = 0
      var reverseStart = 0
      var reverseEnd = 0
      // A meeting at step d costs 2d - 1 edits or 2d, so a budget allows about half its size.
      val lastStep = minOf(steps - 1, budget / 2 + 1)
      val tooExpensive = if (bounded) tooExpensive(n + m) else Int.MAX_VALUE

      for (d in 0..lastStep) {
        if (d > tooExpensive) {
          return furthest(forward, reverse, offset, size, n, m, aFrom, bFrom, aTo, bTo)
        }
        var k = -d + forwardStart
        while (k <= d - forwardEnd) {
          val at = offset + k
          var x = if (k == -d || (k != d && forward[at - 1] < forward[at + 1])) {
            forward[at + 1]
          } else {
            forward[at - 1] + 1
          }
          var y = x - k
          while (x < n && y < m && a[aFrom + x] == b[bFrom + y]) {
            x++
            y++
          }
          forward[at] = x
          if (x > n) {
            forwardEnd += 2
          } else if (y > m) {
            forwardStart += 2
          } else if (odd) {
            val opposite = offset + delta - k
            if (opposite in 0 until size && reverse[opposite] != -1 && x >= n - reverse[opposite]) {
              return if (2 * d - 1 > budget) null else Point(aFrom + x, bFrom + y)
            }
          }
          k += 2
        }

        k = -d + reverseStart
        while (k <= d - reverseEnd) {
          val at = offset + k
          var x = if (k == -d || (k != d && reverse[at - 1] < reverse[at + 1])) {
            reverse[at + 1]
          } else {
            reverse[at - 1] + 1
          }
          var y = x - k
          while (x < n && y < m && a[aTo - 1 - x] == b[bTo - 1 - y]) {
            x++
            y++
          }
          reverse[at] = x
          if (x > n) {
            reverseEnd += 2
          } else if (y > m) {
            reverseStart += 2
          } else if (!odd) {
            val opposite = offset + delta - k
            if (opposite in 0 until size && forward[opposite] != -1) {
              val forwardX = forward[opposite]
              if (forwardX >= n - x) {
                val forwardY = offset + forwardX - opposite
                return if (2 * d > budget) null else Point(aFrom + forwardX, bFrom + forwardY)
              }
            }
          }
          k += 2
        }
      }
      // The searches only fail to meet when the ranges have no line in common, and then the
      // shortest script deletes one side and inserts the other - or they ran out of budget first.
      if (lastStep < steps - 1 || n.toLong() + m > budget) return null
      return Point(aTo, bFrom)
    }

    /**
     * The point furthest along either search: the most lines it has got past, from its own end.
     * Diagonals that ran off the grid, and ones no step has reached, do not count.
     */
    private fun furthest(
      forward: IntArray,
      reverse: IntArray,
      offset: Int,
      size: Int,
      n: Int,
      m: Int,
      aFrom: Int,
      bFrom: Int,
      aTo: Int,
      bTo: Int
    ): Point {
      var bestForward = -1
      var forwardX = 0
      var forwardY = 0
      var bestReverse = -1
      var reverseX = 0
      var reverseY = 0
      for (at in 0 until size) {
        val k = at - offset
        val fx = forward[at]
        if (fx in 0..n && fx - k in 0..m && fx + fx - k > bestForward) {
          bestForward = fx + fx - k
          forwardX = fx
          forwardY = fx - k
        }
        val rx = reverse[at]
        if (rx in 0..n && rx - k in 0..m && rx + rx - k > bestReverse) {
          bestReverse = rx + rx - k
          reverseX = rx
          reverseY = rx - k
        }
      }
      check(bestForward > 0 && bestReverse > 0) { "a search past its first step has moved" }
      return if (bestForward >= bestReverse) {
        Point(aFrom + forwardX, bFrom + forwardY)
      } else {
        Point(aTo - reverseX, bTo - reverseY)
      }
    }

    /**
     * GNU diff's limit: about the square root of the lines in play, rounded up to a power of two
     * and never under 256. Up to it a search is cheap whatever it finds; past it, each further
     * step costs another pass over every line.
     */
    private fun tooExpensive(lines: Int): Int {
      var limit = 1
      var remaining = lines
      while (remaining != 0) {
        limit = limit shl 1
        remaining = remaining ushr 2
      }
      return maxOf(256, limit)
    }
  }

  private class Point(val x: Int, val y: Int)
}
