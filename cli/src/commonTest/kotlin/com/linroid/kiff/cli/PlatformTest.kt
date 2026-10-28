package com.linroid.kiff.cli

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path

class PlatformTest {

  private val dir = TestDirectory()

  @AfterTest
  fun cleanUp() {
    dir.delete()
  }

  @Test
  fun runsACommandWithItsOutputInFilesAndAnswersItsStatus() {
    val out = dir.resolve("out")
    val err = dir.resolve("err")
    val command = if (isWindows) {
      listOf("cmd.exe", "/c", "echo out& echo err 1>&2& exit 3")
    } else {
      listOf("sh", "-c", "echo out; echo err >&2; exit 3")
    }

    assertEquals(3, runCommand(command, out, err))
    assertEquals("out", readBytes(out).decodeToString().trim())
    assertEquals("err", readBytes(err).decodeToString().trim())
  }

  @Test
  fun passesEachArgumentAsItIs() {
    if (isWindows) return
    val out = dir.resolve("out with 'quotes'")
    val script = "printf '[%s]' \"$@\" > \"$0\""
    val args = listOf("two words", "it's", "\$HOME", "", "*", "a\nb")

    assertEquals(0, runCommand(listOf("sh", "-c", script, out.toString()) + args, null, null))
    assertEquals(args.joinToString("") { "[$it]" }, readBytes(out).decodeToString())
  }

  @Test
  fun saysWhenThereIsNoSuchProgram() {
    val program = "kiff-no-such-program-${Random.nextLong().toULong().toString(16)}"
    val failure = assertFailsWith<ProgramNotFoundException> {
      runCommand(listOf(program, "x"), dir.resolve("out"), dir.resolve("err"))
    }
    assertEquals(program, failure.program)
  }

  @Test
  fun makesAFileItCanRun() {
    if (isWindows) return
    val script = dir.resolve("script")
    systemFileSystem.write(script) { writeUtf8("#!/bin/sh\nexit 4\n") }
    makeExecutable(script)

    assertEquals(4, runCommand(listOf(script.toString()), null, null))
  }

  @Test
  fun namesItsPlatformAsReleasesDo() {
    val platform = nativePlatform ?: return
    val names = setOf("linux-x64", "linux-arm64", "macos-arm64", "macos-x64", "windows-x64")
    assertTrue(platform in names, platform)
    assertEquals(isWindows, platform.startsWith("windows"))
  }

  @Test
  fun findsAWindowsProgramInSystem32BeforeThePath() {
    val found = windowsProgram("curl", "C:\\WINDOWS", "C:\\tools;D:\\bin") {
      it.windows in setOf("C:\\WINDOWS\\System32\\curl.exe", "C:\\tools\\curl.exe")
    }
    assertEquals("C:\\WINDOWS\\System32\\curl.exe", found?.windows)
  }

  @Test
  fun findsAWindowsProgramOnThePathInItsOrderWhenWindowsHasNone() {
    val path = "C:\\tools;\"C:\\Program Files\\Git\\usr\\bin\";D:/bin"
    val found = windowsProgram("tar", "C:\\WINDOWS", path) {
      it.windows in setOf("C:\\Program Files\\Git\\usr\\bin\\tar.exe", "D:\\bin\\tar.exe")
    }
    assertEquals("C:\\Program Files\\Git\\usr\\bin\\tar.exe", found?.windows)
  }

  @Test
  fun neverLooksForAWindowsProgramInTheWorkingDirectory() {
    // Every entry here is relative to it, as the directory itself is.
    val relative = ".;;sub;.\\sub;\\fromroot;C:nodir;\"\""
    assertNull(windowsProgram("curl", null, relative) { true })
    assertNull(windowsProgram("curl", "", relative) { true })
    val asked = mutableListOf<String>()
    assertNull(windowsProgram("curl", "C:\\WINDOWS", relative) { asked += it.windows; false })
    assertEquals(listOf("C:\\WINDOWS\\System32\\curl.exe"), asked)
  }

  @Test
  fun keepsTheExtensionAWindowsProgramIsNamedWith() {
    val found = windowsProgram("cmd.exe", "C:\\WINDOWS", null) { true }
    assertEquals("C:\\WINDOWS\\System32\\cmd.exe", found?.windows)
  }

  /** This path as Windows writes it, whatever slash okio chose for it on this platform. */
  private val Path.windows: String get() = toString().replace('/', '\\')
}
