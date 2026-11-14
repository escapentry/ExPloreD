package com.example.files

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DirectoryListingResult(
  val entries: List<FileEntry>,
  val accessDenied: Boolean = false,
  val errorMessage: String? = null,
)

data class ShellOpResult(
  val success: Boolean,
  val message: String = "",
)

class RootShellManager(
  private val scope: CoroutineScope,
) {
  companion object {
    private const val MARKER_PREFIX = "__EXP_MARK_"
    private const val SU_PROBE_PREFIX = "__EXP_SU_OK_"

    fun resolveShBinary(): String =
      listOf("/system/bin/sh", "/bin/sh")
        .firstOrNull { File(it).canExecute() }
        ?: "sh"

    fun resolveSuBinary(): String? =
      listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
      ).firstOrNull { File(it).canExecute() }
        ?: findExecutableInPath("su")

    fun resolveRarTool(): String? =
      listOf(
        "/system/bin/unrar",
        "/system/xbin/unrar",
        "/system/bin/7z",
        "/system/xbin/7z",
        "/system/bin/bsdtar",
        "/usr/bin/unrar",
        "/usr/bin/7z",
      ).firstOrNull { File(it).canExecute() }
        ?: findExecutableInPath("unrar")
        ?: findExecutableInPath("7z")
        ?: findExecutableInPath("7za")
        ?: findExecutableInPath("bsdtar")

    private fun findExecutableInPath(name: String): String? {
      val pathEnv = System.getenv("PATH").orEmpty()
      for (dir in pathEnv.split(':')) {
        if (dir.isBlank()) continue
        val candidate = File(dir, name)
        if (candidate.exists() && candidate.canExecute()) {
          return candidate.absolutePath
        }
      }
      return null
    }

    fun shellQuote(value: String): String =
      "'" + value.replace("'", "'\"'\"'") + "'"
  }

  private data class PendingCommand(
    val token: String,
    val output: StringBuilder,
    val errors: StringBuilder,
    val deferred: CompletableDeferred<Int>,
  )

  private class SuSession(
    val process: Process,
    val writer: BufferedWriter,
    val stdoutJob: Job,
    val stderrJob: Job,
    val monitorJob: Job,
  ) {
    val closed = AtomicBoolean(false)
  }

  private val mutex = Mutex()
  private var suSession: SuSession? = null

  @Volatile
  private var pendingSuProbe: Pair<String, CompletableDeferred<Boolean>>? = null

  @Volatile
  private var pendingCommand: PendingCommand? = null

  val isSuActive: Boolean
    get() = suSession?.let { isProcessAlive(it.process) } == true

  suspend fun enableSu(): ShellOpResult = withContext(Dispatchers.IO) {
    mutex.withLock {
      if (isSuActive) {
        return@withLock ShellOpResult(true, "SU already active")
      }
      val suBinary = resolveSuBinary()
        ?: return@withLock ShellOpResult(false, "su binary not found on this device")

      val process = runCatching {
        ProcessBuilder(suBinary).apply {
          directory(File("/").takeIf { it.exists() })
          redirectErrorStream(false)
        }.start()
      }.getOrElse { err ->
        return@withLock ShellOpResult(false, err.message ?: "Failed to launch su")
      }

      val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
      val probeToken = UUID.randomUUID().toString().replace("-", "").take(12)
      val probeDeferred = CompletableDeferred<Boolean>()
      pendingSuProbe = probeToken to probeDeferred

      val stderrBuffer = StringBuilder()
      val stdoutJob = scope.launch(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        runCatching {
          while (true) {
            val line = reader.readLine() ?: break
            handleSuStdout(line)
          }
        }
      }
      val stderrJob = scope.launch(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(process.errorStream))
        runCatching {
          while (true) {
            val line = reader.readLine() ?: break
            pendingCommand?.errors?.appendLine(line) ?: stderrBuffer.appendLine(line)
          }
        }
      }
      val monitorJob = scope.launch(Dispatchers.IO) {
        runCatching { process.waitFor() }
        pendingSuProbe?.second?.complete(false)
        pendingCommand?.deferred?.complete(1)
        if (suSession?.process === process) {
          suSession = null
        }
      }

      val session = SuSession(process, writer, stdoutJob, stderrJob, monitorJob)
      val sent = runCatching {
        writer.write("printf '${SU_PROBE_PREFIX}%s\\n' '$probeToken'\n")
        writer.flush()
      }.isSuccess

      if (!sent) {
        pendingSuProbe = null
        closeSuSession(session)
        return@withLock ShellOpResult(false, "SU permission denied")
      }

      val ok = withTimeoutOrNull(8_000L) { probeDeferred.await() } ?: false
      pendingSuProbe = null
      if (!ok || !isProcessAlive(process)) {
        closeSuSession(session)
        val detail = stderrBuffer.toString().trim().ifEmpty { "SU permission denied or timed out" }
        return@withLock ShellOpResult(false, detail)
      }

      suSession = session
      ShellOpResult(true, "SU shell elevated")
    }
  }

  suspend fun disableSu() = withContext(Dispatchers.IO) {
    mutex.withLock {
      suSession?.let { closeSuSession(it) }
      suSession = null
    }
  }

  fun close() {
    suSession?.let { closeSuSession(it) }
    suSession = null
  }

  suspend fun listDirectory(
    path: String,
    useSu: Boolean,
    showHidden: Boolean,
  ): DirectoryListingResult = withContext(Dispatchers.IO) {
    val cleanPath = if (path.isBlank()) "/" else path
    if (useSu && isSuActive) {
      val (code, out, err) = runSuCommand("ls -la ${shellQuote(cleanPath)}")
      val parsed = ExploreDParsers.parseLsOutput(out, cleanPath, showHidden)
      if (code == 0 || parsed.isNotEmpty()) {
        return@withContext DirectoryListingResult(entries = parsed)
      }
      return@withContext DirectoryListingResult(
        entries = emptyList(),
        accessDenied = err.contains("Permission denied", ignoreCase = true),
        errorMessage = err.trim().ifEmpty { "Unable to read $cleanPath" },
      )
    }

    val targetDir = File(cleanPath)
    val javaFiles = runCatching { targetDir.listFiles() }.getOrNull()
    val shResult = runNonRootSh("ls -la ${shellQuote(cleanPath)}")
    val shEntries = ExploreDParsers.parseLsOutput(shResult.second, cleanPath, showHidden)

    if (shEntries.isNotEmpty()) {
      val shByPath = shEntries.associateBy { it.path }.toMutableMap()
      javaFiles?.forEach { file ->
        if ((showHidden || !file.name.startsWith(".")) && !shByPath.containsKey(file.absolutePath)) {
          shByPath[file.absolutePath] = fileToEntry(file)
        }
      }
      return@withContext DirectoryListingResult(entries = shByPath.values.toList())
    }

    if (javaFiles != null) {
      val mapped = javaFiles
        .filter { showHidden || !it.name.startsWith(".") }
        .map { fileToEntry(it) }
      return@withContext DirectoryListingResult(entries = mapped)
    }

    val denied = shResult.third.contains("Permission denied", ignoreCase = true) ||
      shResult.third.contains("Operation not permitted", ignoreCase = true) ||
      !targetDir.canRead()

    DirectoryListingResult(
      entries = emptyList(),
      accessDenied = denied,
      errorMessage = shResult.third.trim().ifEmpty { "Permission denied for $cleanPath" },
    )
  }

  suspend fun ensureWritableMounts(
    paths: Collection<String>,
    mounts: List<MountPartition>,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    if (!isSuActive) {
      return@withContext ShellOpResult(false, "SU required to auto-mount partition")
    }
    val effectiveMounts = mounts.ifEmpty { readMounts(useSu = true) }
    val targetMountPoints = paths.mapNotNull { path ->
      val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
      effectiveMounts
        .filter { mp ->
          mp.mountPoint == "/" ||
            normalized == mp.mountPoint ||
            normalized.startsWith(mp.mountPoint.trimEnd('/') + "/")
        }
        .maxByOrNull { it.mountPoint.length }
    }.distinctBy { it.mountPoint }

    for (mount in targetMountPoints) {
      if (!mount.isReadWrite) {
        val remounted = remountRw(mount.mountPoint)
        if (!remounted.success && mount.mountPoint != "/") {
          // System-as-root fallback
          val rootFallback = remountRw("/")
          if (!rootFallback.success) {
            return@withContext remounted
          }
        } else if (!remounted.success) {
          return@withContext remounted
        }
      }
    }
    ShellOpResult(true, "Mount ready")
  }

  private suspend fun remountRw(mountPoint: String): ShellOpResult {
    val qMount = shellQuote(mountPoint)
    val attempts = listOf(
      "mount -o rw,remount $qMount",
      "mount -o remount,rw $qMount",
      "toolbox mount -o remount,rw $qMount",
    )
    var lastErr = ""
    for (cmd in attempts) {
      val (code, _, err) = runSuCommand(cmd)
      if (code == 0) {
        return ShellOpResult(true, "Auto-mounted $mountPoint as RW")
      }
      lastErr = err.trim()
    }
    return ShellOpResult(false, lastErr.ifEmpty { "Auto-remount failed for $mountPoint" })
  }

  suspend fun createFolder(parentDir: String, name: String, useSu: Boolean): ShellOpResult =
    withContext(Dispatchers.IO) {
      val target = File(parentDir, name)
      if (!useSu) {
        if (target.exists()) return@withContext ShellOpResult(false, "Already exists")
        if (runCatching { target.mkdirs() }.getOrDefault(false)) {
          return@withContext ShellOpResult(true, "Created folder $name")
        }
      }
      val cmd = "mkdir -p ${shellQuote(target.absolutePath)}"
      execWriteCommand(cmd, useSu, "Created folder $name")
    }

  suspend fun createFile(parentDir: String, name: String, useSu: Boolean): ShellOpResult =
    withContext(Dispatchers.IO) {
      val target = File(parentDir, name)
      if (!useSu) {
        if (target.exists()) return@withContext ShellOpResult(false, "Already exists")
        if (runCatching { target.createNewFile() }.getOrDefault(false)) {
          return@withContext ShellOpResult(true, "Created file $name")
        }
      }
      val cmd = "touch ${shellQuote(target.absolutePath)}"
      execWriteCommand(cmd, useSu, "Created file $name")
    }

  suspend fun renameEntry(oldPath: String, newName: String, useSu: Boolean): ShellOpResult =
    withContext(Dispatchers.IO) {
      val source = File(oldPath)
      val parent = source.parentFile ?: return@withContext ShellOpResult(false, "Invalid parent")
      val dest = File(parent, newName)
      if (dest.exists()) return@withContext ShellOpResult(false, "$newName already exists")
      if (!useSu) {
        if (runCatching { source.renameTo(dest) }.getOrDefault(false)) {
          return@withContext ShellOpResult(true, "Renamed to $newName")
        }
      }
      val cmd = "mv ${shellQuote(oldPath)} ${shellQuote(dest.absolutePath)}"
      execWriteCommand(cmd, useSu, "Renamed to $newName")
    }

  suspend fun copyEntries(
    sourcePaths: Collection<String>,
    targetDir: String,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    if (sourcePaths.isEmpty()) return@withContext ShellOpResult(false, "Nothing selected")
    if (!useSu) {
      val allJavaOk = runCatching {
        for (srcPath in sourcePaths) {
          val src = File(srcPath)
          val dst = resolveUniqueCopyTarget(File(targetDir), src.name)
          src.copyRecursively(dst, overwrite = false)
        }
        true
      }.getOrDefault(false)
      if (allJavaOk) return@withContext ShellOpResult(true, "Copied ${sourcePaths.size} item(s)")
    }
    val quotedSources = sourcePaths.joinToString(" ") { shellQuote(it) }
    val cmd = "cp -rf $quotedSources ${shellQuote(targetDir.trimEnd('/') + "/")}"
    execWriteCommand(cmd, useSu, "Copied ${sourcePaths.size} item(s)")
  }

  suspend fun moveEntries(
    sourcePaths: Collection<String>,
    targetDir: String,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    if (sourcePaths.isEmpty()) return@withContext ShellOpResult(false, "Nothing selected")
    if (!useSu) {
      val allJavaOk = runCatching {
        for (srcPath in sourcePaths) {
          val src = File(srcPath)
          val dst = File(targetDir, src.name)
          if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
          }
        }
        true
      }.getOrDefault(false)
      if (allJavaOk) return@withContext ShellOpResult(true, "Moved ${sourcePaths.size} item(s)")
    }
    val quotedSources = sourcePaths.joinToString(" ") { shellQuote(it) }
    val cmd = "mv -f $quotedSources ${shellQuote(targetDir.trimEnd('/') + "/")}"
    execWriteCommand(cmd, useSu, "Moved ${sourcePaths.size} item(s)")
  }

  suspend fun deleteEntries(paths: Collection<String>, useSu: Boolean): ShellOpResult =
    withContext(Dispatchers.IO) {
      if (paths.isEmpty()) return@withContext ShellOpResult(false, "Nothing selected")
      if (!useSu) {
        val allDeleted = runCatching {
          paths.all { path ->
            val file = File(path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
          }
        }.getOrDefault(false)
        if (allDeleted) return@withContext ShellOpResult(true, "Deleted ${paths.size} item(s)")
      }
      val quotedPaths = paths.joinToString(" ") { shellQuote(it) }
      val cmd = "rm -rf $quotedPaths"
      execWriteCommand(cmd, useSu, "Deleted ${paths.size} item(s)")
    }

  suspend fun changePermissions(
    path: String,
    octal: String,
    recursive: Boolean,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    val cleanOctal = octal.trim()
    if (PermissionBits.fromOctal(cleanOctal) == null) {
      return@withContext ShellOpResult(false, "Invalid octal mode: $octal")
    }
    val flag = if (recursive) "-R " else ""
    val cmd = "chmod $flag$cleanOctal ${shellQuote(path)}"
    execWriteCommand(cmd, useSu, "Permissions set to $cleanOctal")
  }

  suspend fun changeOwnership(
    path: String,
    ownerGroup: String,
    recursive: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    if (!isSuActive) return@withContext ShellOpResult(false, "SU required for chown")
    val cleanOwner = ownerGroup.trim()
    if (cleanOwner.isEmpty()) return@withContext ShellOpResult(false, "Owner cannot be empty")
    val flag = if (recursive) "-R " else ""
    val cmd = "chown $flag${shellQuote(cleanOwner)} ${shellQuote(path)}"
    execWriteCommand(cmd, useSu = true, "Owner updated to $cleanOwner")
  }

  suspend fun readTextFile(path: String, useSu: Boolean): Result<String> =
    withContext(Dispatchers.IO) {
      val file = File(path)
      if (!useSu && file.canRead()) {
        return@withContext runCatching {
          val bytes = file.inputStream().use { input ->
            val buf = ByteArray(262_144)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
          }
          bytes.toString(Charsets.UTF_8)
        }
      }
      val cmd = "head -c 262144 ${shellQuote(path)}"
      val (code, out, err) = if (useSu && isSuActive) runSuCommand(cmd) else runNonRootSh(cmd)
      if (code == 0) {
        Result.success(out)
      } else {
        Result.failure(IllegalStateException(err.trim().ifEmpty { "Unable to read $path" }))
      }
    }

  suspend fun writeTextFile(path: String, content: String, useSu: Boolean): ShellOpResult =
    withContext(Dispatchers.IO) {
      val file = File(path)
      if (!useSu) {
        val written = runCatching {
          file.writeText(content, Charsets.UTF_8)
          true
        }.getOrDefault(false)
        if (written) return@withContext ShellOpResult(true, "Saved ${file.name}")
      }
      val eofMarker = "__EXP_EOF_${UUID.randomUUID().toString().replace("-", "").take(10)}"
      val normalized = if (content.endsWith("\n")) content else "$content\n"
      val cmd = "cat << '$eofMarker' > ${shellQuote(path)}\n$normalized$eofMarker"
      execWriteCommand(cmd, useSu, "Saved ${file.name}")
    }

  suspend fun readMounts(useSu: Boolean): List<MountPartition> = withContext(Dispatchers.IO) {
    val direct = runCatching { File("/proc/mounts").takeIf { it.canRead() }?.readText() }.getOrNull()
    if (!direct.isNullOrBlank()) {
      return@withContext ExploreDParsers.parseProcMounts(direct)
    }
    val (_, out, _) = if (useSu && isSuActive) {
      runSuCommand("cat /proc/mounts")
    } else {
      runNonRootSh("cat /proc/mounts")
    }
    ExploreDParsers.parseProcMounts(out)
  }

  suspend fun copyReadableToTemp(
    sourcePath: String,
    tempFile: File,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    tempFile.parentFile?.mkdirs()
    val src = File(sourcePath)
    if (!useSu && src.canRead()) {
      val ok = runCatching {
        src.copyTo(tempFile, overwrite = true)
        true
      }.getOrDefault(false)
      if (ok) return@withContext ShellOpResult(true, "Ready")
    }
    val cmd = "cp -f ${shellQuote(sourcePath)} ${shellQuote(tempFile.absolutePath)} && chmod 0644 ${shellQuote(tempFile.absolutePath)}"
    execWriteCommand(cmd, useSu, "Prepared local copy")
  }

  suspend fun copyDirContents(
    tempSrcDir: File,
    targetDir: String,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    val children = tempSrcDir.listFiles()?.map { it.absolutePath }.orEmpty()
    if (children.isEmpty()) return@withContext ShellOpResult(true, "Extracted 0 items")
    copyEntries(children, targetDir, useSu)
  }

  suspend fun listRarEntries(archivePath: String, useSu: Boolean): Result<List<ArchiveEntryItem>> =
    withContext(Dispatchers.IO) {
      val tool = resolveRarTool()
        ?: return@withContext Result.failure(
          IllegalStateException("RAR inspection requires unrar, 7z, or bsdtar on device"),
        )
      val qTool = shellQuote(tool)
      val qArchive = shellQuote(archivePath)
      val cmd = when {
        tool.endsWith("unrar") -> "$qTool lb $qArchive"
        tool.endsWith("bsdtar") -> "$qTool -tf $qArchive"
        else -> "$qTool l -ba $qArchive"
      }
      val (code, out, err) = if (useSu && isSuActive) runSuCommand(cmd) else runNonRootSh(cmd)
      if (code != 0) {
        return@withContext Result.failure(IllegalStateException(err.trim().ifEmpty { "Failed to read RAR archive" }))
      }
      val entries = out.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
          val cleanName = if (tool.endsWith("7z") || tool.endsWith("7za")) {
            line.split(Regex("\\s+")).lastOrNull().orEmpty()
          } else {
            line
          }
          ArchiveEntryItem(
            name = cleanName,
            compressedSize = 0L,
            uncompressedSize = 0L,
            isDirectory = cleanName.endsWith("/"),
          )
        }
        .filter { it.name.isNotEmpty() }
        .toList()
      Result.success(entries)
    }

  suspend fun extractRarArchive(
    archivePath: String,
    targetDir: String,
    useSu: Boolean,
  ): ShellOpResult = withContext(Dispatchers.IO) {
    val tool = resolveRarTool()
      ?: return@withContext ShellOpResult(false, "RAR extraction requires unrar, 7z, or bsdtar binary on device")
    val qTool = shellQuote(tool)
    val qArchive = shellQuote(archivePath)
    val qDest = shellQuote(targetDir)
    val cmd = when {
      tool.endsWith("unrar") -> "mkdir -p $qDest && $qTool x -o+ $qArchive $qDest/"
      tool.endsWith("bsdtar") -> "mkdir -p $qDest && $qTool -xf $qArchive -C $qDest"
      else -> "mkdir -p $qDest && $qTool x -y -o$qDest $qArchive"
    }
    execWriteCommand(cmd, useSu, "Extracted RAR archive")
  }

  suspend fun getSeLinuxMode(): String = withContext(Dispatchers.IO) {
    val (code, out, _) = if (isSuActive) {
      runSuCommand("getenforce")
    } else {
      runNonRootSh("getenforce")
    }
    if (code == 0 && out.isNotBlank()) out.trim() else "Unknown"
  }

  suspend fun setSeLinuxPermissive(permissive: Boolean): ShellOpResult = withContext(Dispatchers.IO) {
    if (!isSuActive) return@withContext ShellOpResult(false, "SU required to toggle SELinux")
    val flag = if (permissive) "0" else "1"
    val label = if (permissive) "Permissive" else "Enforcing"
    val (code, _, err) = runSuCommand("setenforce $flag")
    if (code == 0) {
      ShellOpResult(true, "SELinux set to $label")
    } else {
      ShellOpResult(false, err.trim().ifEmpty { "Failed to change SELinux mode" })
    }
  }

  suspend fun runRootCommand(command: String): ShellOpResult = withContext(Dispatchers.IO) {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return@withContext ShellOpResult(false, "Enter a command")
    val (code, out, err) = if (isSuActive) {
      runSuCommand(trimmed)
    } else {
      runNonRootSh(trimmed)
    }
    val combined = buildString {
      if (out.isNotBlank()) append(out.trimEnd())
      if (err.isNotBlank()) {
        if (isNotEmpty()) append("\n")
        append(err.trimEnd())
      }
      if (isEmpty()) append("(exit $code)")
    }
    ShellOpResult(code == 0, combined)
  }

  suspend fun rebootDevice(target: String): ShellOpResult = withContext(Dispatchers.IO) {
    if (!isSuActive) return@withContext ShellOpResult(false, "SU required to reboot")
    val arg = target.trim()
    val cmd = if (arg.isEmpty()) "svc power reboot || reboot" else "svc power reboot $arg || reboot $arg"
    execWriteCommand(cmd, useSu = true, "Rebooting device…")
  }

  private suspend fun execWriteCommand(
    cmd: String,
    useSu: Boolean,
    successMessage: String,
  ): ShellOpResult {
    val (code, _, err) = if (useSu && isSuActive) {
      runSuCommand(cmd)
    } else {
      runNonRootSh(cmd)
    }
    return if (code == 0) {
      ShellOpResult(true, successMessage)
    } else {
      val cleanErr = err.trim().ifEmpty { "Operation failed (exit $code)" }
      ShellOpResult(false, cleanErr)
    }
  }

  private suspend fun runSuCommand(command: String): Triple<Int, String, String> =
    mutex.withLock {
      val session = suSession
      if (session == null || !isProcessAlive(session.process)) {
        suSession = null
        return@withLock Triple(1, "", "SU session inactive")
      }

      val token = UUID.randomUUID().toString().replace("-", "").take(12)
      val deferred = CompletableDeferred<Int>()
      val stdoutBuf = StringBuilder()
      val stderrBuf = StringBuilder()
      pendingCommand = PendingCommand(token, stdoutBuf, stderrBuf, deferred)

      val sent = runCatching {
        session.writer.write(command)
        session.writer.write("\n__EXP_EC=$?\n")
        session.writer.write("printf '${MARKER_PREFIX}%s__:%d\\n' '$token' \"\$__EXP_EC\"\n")
        session.writer.flush()
      }.isSuccess

      if (!sent) {
        pendingCommand = null
        return@withLock Triple(1, "", "SU write error")
      }

      val exitCode = withTimeoutOrNull(15_000L) { deferred.await() } ?: 124
      pendingCommand = null
      Triple(exitCode, stdoutBuf.toString(), stderrBuf.toString())
    }

  private fun runNonRootSh(command: String): Triple<Int, String, String> {
    return runCatching {
      val proc = ProcessBuilder(resolveShBinary(), "-c", command)
        .redirectErrorStream(false)
        .start()
      val out = proc.inputStream.bufferedReader().readText()
      val err = proc.errorStream.bufferedReader().readText()
      val code = proc.waitFor()
      Triple(code, out, err)
    }.getOrElse { err ->
      Triple(1, "", err.message ?: "sh execution failed")
    }
  }

  private fun handleSuStdout(line: String) {
    val probe = pendingSuProbe
    if (probe != null) {
      val tag = "${SU_PROBE_PREFIX}${probe.first}"
      if (line.contains(tag)) {
        probe.second.complete(true)
        return
      }
    }

    val pending = pendingCommand
    if (pending != null) {
      val tag = "${MARKER_PREFIX}${pending.token}__:"
      val idx = line.indexOf(tag)
      if (idx >= 0) {
        val before = line.substring(0, idx)
        if (before.isNotEmpty()) pending.output.appendLine(before)
        val code = line.substring(idx + tag.length).trim().toIntOrNull() ?: 0
        pending.deferred.complete(code)
        return
      }
      pending.output.appendLine(line)
    }
  }

  private fun fileToEntry(file: File): FileEntry {
    val isDir = file.isDirectory
    val perms = buildString {
      append(if (isDir) 'd' else '-')
      append(if (file.canRead()) 'r' else '-')
      append(if (file.canWrite()) 'w' else '-')
      append(if (file.canExecute()) 'x' else '-')
      append("------")
    }
    val octal = PermissionBits.fromSymbolic(perms).toOctalString()
    return FileEntry(
      name = file.name,
      path = file.absolutePath,
      isDirectory = isDir,
      isSymlink = false,
      sizeBytes = if (isDir) 0L else file.length(),
      modifiedDisplay = ExploreDParsers.formatTimestamp(file.lastModified()),
      modifiedEpochMillis = file.lastModified(),
      permissionsSymbolic = perms,
      permissionsOctal = octal,
    )
  }

  private fun resolveUniqueCopyTarget(targetDir: File, fileName: String): File {
    val initial = File(targetDir, fileName)
    if (!initial.exists()) return initial
    val dotIdx = fileName.lastIndexOf('.')
    val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
    val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
    var counter = 1
    while (true) {
      val candidate = File(targetDir, "${base}_copy$counter$ext")
      if (!candidate.exists()) return candidate
      counter++
    }
  }

  private fun closeSuSession(session: SuSession) {
    if (!session.closed.compareAndSet(false, true)) return
    runCatching { session.writer.close() }
    runCatching { session.process.destroy() }
    if (isProcessAlive(session.process)) {
      runCatching { session.process.destroyForcibly() }
    }
    session.stdoutJob.cancel()
    session.stderrJob.cancel()
    session.monitorJob.cancel()
  }

  private fun isProcessAlive(process: Process): Boolean =
    runCatching {
      process.exitValue()
      false
    }.getOrDefault(true)
}
