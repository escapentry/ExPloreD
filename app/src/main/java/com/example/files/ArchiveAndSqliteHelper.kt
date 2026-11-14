package com.example.files

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

object ArchiveAndSqliteHelper {

  fun listZipOrApkEntries(file: File): Result<List<ArchiveEntryItem>> = runCatching {
    ZipFile(file).use { zip ->
      val list = mutableListOf<ArchiveEntryItem>()
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        list += ArchiveEntryItem(
          name = entry.name,
          compressedSize = entry.compressedSize.coerceAtLeast(0L),
          uncompressedSize = entry.size.coerceAtLeast(0L),
          isDirectory = entry.isDirectory,
        )
      }
      list.sortedWith(compareBy<ArchiveEntryItem> { !it.isDirectory }.thenBy { it.name })
    }
  }

  fun extractZipOrApk(archiveFile: File, destDir: File): Result<Int> = runCatching {
    destDir.mkdirs()
    val canonicalDest = destDir.canonicalPath
    var count = 0
    ZipFile(archiveFile).use { zip ->
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        val outFile = File(destDir, entry.name)
        if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator) &&
          outFile.canonicalPath != canonicalDest
        ) {
          throw SecurityException("Blocked unsafe path in archive: ${entry.name}")
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          zip.getInputStream(entry).use { input ->
            FileOutputStream(outFile).use { output ->
              input.copyTo(output)
            }
          }
          count++
        }
      }
    }
    count
  }

  fun listTarEntries(file: File): Result<List<ArchiveEntryItem>> = runCatching {
    openTarStream(file).use { stream ->
      val items = mutableListOf<ArchiveEntryItem>()
      val header = ByteArray(512)
      while (true) {
        val read = readFully(stream, header)
        if (read < 512 || header.all { it == 0.toByte() }) break
        val name = extractNullTerminatedString(header, 0, 100)
        if (name.isEmpty()) break
        val prefix = extractNullTerminatedString(header, 345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        val size = parseTarOctal(header, 124, 12)
        val typeFlag = header[156].toInt().toChar()
        val isDir = typeFlag == '5' || fullName.endsWith("/")
        if (typeFlag != 'L' && typeFlag != 'x' && typeFlag != 'g') {
          items += ArchiveEntryItem(
            name = fullName,
            compressedSize = size,
            uncompressedSize = size,
            isDirectory = isDir,
          )
        }
        val padded = ((size + 511L) / 512L) * 512L
        skipExactly(stream, padded)
      }
      items.sortedWith(compareBy<ArchiveEntryItem> { !it.isDirectory }.thenBy { it.name })
    }
  }

  fun extractTar(archiveFile: File, destDir: File): Result<Int> = runCatching {
    destDir.mkdirs()
    val canonicalDest = destDir.canonicalPath
    var count = 0
    openTarStream(archiveFile).use { stream ->
      val header = ByteArray(512)
      while (true) {
        val read = readFully(stream, header)
        if (read < 512 || header.all { it == 0.toByte() }) break
        val name = extractNullTerminatedString(header, 0, 100)
        if (name.isEmpty()) break
        val prefix = extractNullTerminatedString(header, 345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        val size = parseTarOctal(header, 124, 12)
        val typeFlag = header[156].toInt().toChar()
        val isDir = typeFlag == '5' || fullName.endsWith("/")

        val outFile = File(destDir, fullName)
        if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator) &&
          outFile.canonicalPath != canonicalDest
        ) {
          throw SecurityException("Blocked unsafe path in tar: $fullName")
        }

        if (isDir) {
          outFile.mkdirs()
          val padded = ((size + 511L) / 512L) * 512L
          skipExactly(stream, padded)
        } else if (typeFlag == '0' || typeFlag == '\u0000') {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { output ->
            copyExactBytes(stream, output, size)
          }
          val remainder = (512L - (size % 512L)) % 512L
          skipExactly(stream, remainder)
          count++
        } else {
          val padded = ((size + 511L) / 512L) * 512L
          skipExactly(stream, padded)
        }
      }
    }
    count
  }

  fun inspectSqliteDatabase(
    entry: FileEntry,
    readableFile: File,
    tempCopyPath: String?,
    requestedTable: String? = null,
  ): SqliteDatabaseInfo {
    return runCatching {
      SQLiteDatabase.openDatabase(
        readableFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
      ).use { db ->
        val tables = mutableListOf<String>()
        db.rawQuery(
          "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
          null,
        ).use { cursor ->
          while (cursor.moveToNext()) {
            tables += cursor.getString(0)
          }
        }
        val activeTable = requestedTable?.takeIf { it in tables }
          ?: tables.firstOrNull { it != "android_metadata" && it != "sqlite_sequence" }
          ?: tables.firstOrNull()

        if (activeTable == null) {
          return@use SqliteDatabaseInfo(
            entry = entry,
            tempCopyPath = tempCopyPath,
            tables = emptyList(),
            selectedTable = null,
            columns = emptyList(),
            rows = emptyList(),
            totalRows = 0,
          )
        }

        val escapedTable = activeTable.replace("\"", "\"\"")
        var totalRows = 0
        db.rawQuery("SELECT COUNT(*) FROM \"$escapedTable\"", null).use { c ->
          if (c.moveToFirst()) totalRows = c.getInt(0)
        }

        val columns = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        db.rawQuery("SELECT * FROM \"$escapedTable\" LIMIT 200", null).use { cursor ->
          columns.addAll(cursor.columnNames)
          while (cursor.moveToNext()) {
            val row = ArrayList<String>(columns.size)
            for (i in columns.indices) {
              row += formatCursorCell(cursor, i)
            }
            rows += row
          }
        }

        SqliteDatabaseInfo(
          entry = entry,
          tempCopyPath = tempCopyPath,
          tables = tables,
          selectedTable = activeTable,
          columns = columns,
          rows = rows,
          totalRows = totalRows,
        )
      }
    }.getOrElse { err ->
      SqliteDatabaseInfo(
        entry = entry,
        tempCopyPath = tempCopyPath,
        tables = emptyList(),
        selectedTable = null,
        columns = emptyList(),
        rows = emptyList(),
        totalRows = 0,
        error = err.message ?: "Unable to read SQLite database",
      )
    }
  }

  private fun formatCursorCell(cursor: Cursor, colIndex: Int): String {
    return when (cursor.getType(colIndex)) {
      Cursor.FIELD_TYPE_NULL -> "NULL"
      Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(colIndex).toString()
      Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(colIndex).toString()
      Cursor.FIELD_TYPE_BLOB -> "<BLOB ${cursor.getBlob(colIndex).size} B>"
      else -> cursor.getString(colIndex).orEmpty()
    }
  }

  private fun openTarStream(file: File): InputStream {
    val lower = file.name.lowercase(Locale.ROOT)
    val buffered = BufferedInputStream(FileInputStream(file))
    return if (lower.endsWith(".gz") || lower.endsWith(".tgz")) {
      GZIPInputStream(buffered)
    } else {
      buffered
    }
  }

  private fun readFully(stream: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
      val read = stream.read(buffer, total, buffer.size - total)
      if (read < 0) break
      total += read
    }
    return total
  }

  private fun skipExactly(stream: InputStream, bytes: Long) {
    var remaining = bytes
    val scratch = ByteArray(4096)
    while (remaining > 0L) {
      val toRead = minOf(remaining, scratch.size.toLong()).toInt()
      val read = stream.read(scratch, 0, toRead)
      if (read <= 0) break
      remaining -= read.toLong()
    }
  }

  private fun copyExactBytes(stream: InputStream, out: FileOutputStream, bytes: Long) {
    var remaining = bytes
    val buffer = ByteArray(8192)
    while (remaining > 0L) {
      val toRead = minOf(remaining, buffer.size.toLong()).toInt()
      val read = stream.read(buffer, 0, toRead)
      if (read <= 0) break
      out.write(buffer, 0, read)
      remaining -= read.toLong()
    }
  }

  private fun extractNullTerminatedString(buf: ByteArray, offset: Int, length: Int): String {
    var end = offset
    val max = minOf(buf.size, offset + length)
    while (end < max && buf[end] != 0.toByte()) end++
    return String(buf, offset, end - offset, Charsets.UTF_8).trim()
  }

  private fun parseTarOctal(buf: ByteArray, offset: Int, length: Int): Long {
    val raw = extractNullTerminatedString(buf, offset, length).trim()
    if (raw.isEmpty()) return 0L
    return raw.toLongOrNull(8) ?: 0L
  }
}
