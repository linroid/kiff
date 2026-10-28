package com.linroid.kiff.cli

import okio.BufferedSink
import okio.FileSystem
import okio.Path

/** This machine's files, which okio declares for each platform rather than for common code. */
internal expect val systemFileSystem: FileSystem

/** Where this machine keeps temporary files. */
internal expect val temporaryDirectory: Path

/**
 * Standard output as bytes, written as they are: no newline translation and no re-encoding, so a
 * diff reaches a file or patch(1) exactly as it was built.
 */
internal expect fun standardOutput(): BufferedSink

/** Writes [line] and a newline to standard error. */
internal expect fun printError(line: String)

/** Ends this process with [status]. */
internal expect fun exit(status: Int): Nothing

/** The environment variable [name], or null when it is unset. */
internal expect fun environment(name: String): String?

/** The directory `~` stands for. */
internal expect fun homeDirectory(): String

/**
 * What a release calls this machine in the names of its native builds, such as `linux-x64`, or null
 * where releases have none.
 */
internal expect val nativePlatform: String?

/**
 * The Java this build runs on, to hand to a release that needs one, or null for a native build,
 * which has none of its own.
 */
internal expect val runningJavaHome: String?

/** Whether this is Windows, whose launchers end in .exe or .bat and whose PATH splits on `;`. */
internal expect val isWindows: Boolean

/**
 * Runs [command] to its end and answers its exit status. Its standard output and error go to
 * [stdout] and [stderr] when they are given, and otherwise wherever this process's go.
 *
 * @throws ProgramNotFoundException when there is no program [command] could name.
 * @throws okio.IOException when it cannot be started for any other reason.
 */
internal expect fun runCommand(command: List<String>, stdout: Path?, stderr: Path?): Int

/**
 * Hands this process over to [command], with [environment] added to its own.
 *
 * On Linux and macOS [command] takes this process's place, so its exit status and any signal reach
 * the caller as they would have from it, and this never returns. Elsewhere it runs [command], waits
 * for it, and answers its exit status.
 *
 * @throws okio.IOException when [command] cannot be started.
 */
internal expect fun handOver(command: List<String>, environment: Map<String, String>): Int

/** Lets anyone run the file at [path], as an unpacked launcher needs. */
internal expect fun makeExecutable(path: Path)

/** There is no program at all by the name a command starts with. */
internal class ProgramNotFoundException(val program: String) :
  okio.IOException("$program was not found")

/**
 * The file Windows should start for [program], a bare name such as `curl` or `cmd.exe`, or null
 * when there is none: `%SystemRoot%\System32`, where Windows's own curl, tar and cmd.exe are, is
 * looked in first, and then each directory on the PATH, [path], that is named from a root.
 *
 * The working directory is never looked in, though it is where CreateProcess and cmd.exe look
 * first when given a name alone: `kiff --use` run inside a directory someone else wrote, with a
 * curl.exe of its own in it, must not run that. Nor is a PATH entry relative to it.
 */
internal fun windowsProgram(
  program: String,
  systemRoot: String?,
  path: String?,
  exists: (Path) -> Boolean
): Path? {
  val name = if (program.endsWith(".exe", ignoreCase = true)) program else "$program.exe"
  val system = systemRoot?.takeIf { it.isNotBlank() }?.let { "$it\\System32" }
  // cmd.exe takes a PATH entry in quotes, so one may be written that way.
  val directories = listOfNotNull(system) +
    path.orEmpty().split(';').map { it.trim().removeSurrounding("\"") }
  return directories
    .filter { isAbsolutePath(it, windows = true) }
    .map { platformPath(it, windows = true) / name }
    .firstOrNull(exists)
}
