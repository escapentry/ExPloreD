package com.example.files

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PinPreferences
import com.example.system.StoragePermissionHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TextEditorState(
  val entry: FileEntry,
  val content: String,
  val isSaving: Boolean = false,
)

@Immutable
data class ArchiveViewerState(
  val entry: FileEntry,
  val formatLabel: String,
  val items: List<ArchiveEntryItem>,
  val isExtracting: Boolean = false,
  val error: String? = null,
)

sealed interface PendingSystemAction {
  data class CreateFolder(val parentDir: String, val name: String) : PendingSystemAction
  data class CreateFile(val parentDir: String, val name: String) : PendingSystemAction
  data class Rename(val entry: FileEntry, val newName: String) : PendingSystemAction
  data class Paste(val clip: ClipboardState, val targetDir: String) : PendingSystemAction
  data class Delete(val paths: List<String>) : PendingSystemAction
  data class ApplyPermissions(
    val entry: FileEntry,
    val octal: String,
    val recursive: Boolean,
    val ownerGroup: String?,
  ) : PendingSystemAction
  data class SaveText(val entry: FileEntry, val content: String) : PendingSystemAction
  data class ExtractArchive(val entry: FileEntry, val targetDir: String) : PendingSystemAction
}

@Immutable
data class SystemModificationWarning(
  val actionLabel: String,
  val targetPath: String,
  val action: PendingSystemAction,
)

enum class PinDialogMode {
  CREATE,
  UNLOCK,
  CHANGE_CURRENT,
  CHANGE_NEW,
}

@Immutable
data class ExploreDUiState(
  val currentPath: String = "/",
  val internalStoragePath: String = "/storage/emulated/0",
  val rawEntries: List<FileEntry> = emptyList(),
  val visibleEntries: List<FileEntry> = emptyList(),
  val searchQuery: String = "",
  val isLoading: Boolean = false,
  val accessDenied: Boolean = false,
  val isSuActive: Boolean = false,
  val suAvailable: Boolean = true,
  val hasPinConfigured: Boolean = false,
  val showHiddenFiles: Boolean = true,
  val sortOrder: SortOrder = SortOrder.NAME,
  val selectedPaths: Set<String> = emptySet(),
  val inspectedEntry: FileEntry? = null,
  val clipboard: ClipboardState? = null,
  val mounts: List<MountPartition> = emptyList(),
  val hasStoragePermission: Boolean = true,
  val permissionBannerDismissed: Boolean = false,
  val pinDialogMode: PinDialogMode? = null,
  val pinError: String? = null,
  val permissionsTarget: FileEntry? = null,
  val textEditor: TextEditorState? = null,
  val archiveViewer: ArchiveViewerState? = null,
  val sqliteViewer: SqliteDatabaseInfo? = null,
  val systemWarning: SystemModificationWarning? = null,
  val showToolsSheet: Boolean = false,
  val selinuxMode: String = "Unknown",
  val quickToolOutput: String? = null,
  val statusMessage: String? = null,
  val statusIsError: Boolean = false,
) {
  val isSelectionMode: Boolean
    get() = selectedPaths.isNotEmpty()

  val isCurrentSystemPartition: Boolean
    get() = ExploreDParsers.isSystemPartitionPath(currentPath, internalStoragePath)
}

class ExploreDViewModel(application: Application) : AndroidViewModel(application) {

  private val pinPrefs = PinPreferences(application)
  private val shellManager = RootShellManager(viewModelScope)

  private val internalStorageRoot: String = runCatching {
    Environment.getExternalStorageDirectory().absolutePath
  }.getOrDefault("/storage/emulated/0")

  private val _uiState = MutableStateFlow(
    ExploreDUiState(
      currentPath = internalStorageRoot.takeIf { File(it).exists() } ?: "/",
      internalStoragePath = internalStorageRoot,
      suAvailable = RootShellManager.resolveSuBinary() != null,
      hasStoragePermission = StoragePermissionHelper.hasFilesPermission(application),
    ),
  )
  val uiState: StateFlow<ExploreDUiState> = _uiState.asStateFlow()

  private var pendingPostSuAction: (suspend () -> Unit)? = null
  private var activeTempInspectorFile: File? = null

  init {
    viewModelScope.launch {
      pinPrefs.settingsFlow.collect { settings ->
        _uiState.update { state ->
          val updated = state.copy(
            hasPinConfigured = settings.hasPin,
            showHiddenFiles = settings.showHiddenFiles,
            sortOrder = settings.sortOrder,
          )
          updated.copy(
            visibleEntries = filterAndSort(
              entries = updated.rawEntries,
              query = updated.searchQuery,
              showHidden = updated.showHiddenFiles,
              sortOrder = updated.sortOrder,
            ),
          )
        }
      }
    }
    refreshAll()
  }

  override fun onCleared() {
    activeTempInspectorFile?.delete()
    shellManager.close()
    super.onCleared()
  }

  fun refreshStoragePermissionState() {
    val granted = StoragePermissionHelper.hasFilesPermission(getApplication())
    _uiState.update { it.copy(hasStoragePermission = granted) }
    if (granted) refreshDirectory()
  }

  fun dismissPermissionBanner() {
    _uiState.update { it.copy(permissionBannerDismissed = true) }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null, statusIsError = false) }
  }

  fun refreshAll() {
    refreshDirectory()
    refreshMounts()
  }

  fun navigateTo(path: String) {
    val clean = normalizePath(path)
    _uiState.update {
      it.copy(
        currentPath = clean,
        selectedPaths = emptySet(),
        inspectedEntry = null,
        searchQuery = "",
      )
    }
    refreshDirectory(clean)
  }

  fun navigateUp(): Boolean {
    val current = _uiState.value.currentPath
    if (current == "/" || current.isEmpty()) return false
    val parent = File(current).parent ?: "/"
    navigateTo(parent)
    return true
  }

  fun updateSearchQuery(query: String) {
    _uiState.update { state ->
      state.copy(
        searchQuery = query,
        visibleEntries = filterAndSort(
          entries = state.rawEntries,
          query = query,
          showHidden = state.showHiddenFiles,
          sortOrder = state.sortOrder,
        ),
      )
    }
  }

  fun refreshDirectory(targetPath: String = _uiState.value.currentPath) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true) }
      val state = _uiState.value
      val result = shellManager.listDirectory(
        path = targetPath,
        useSu = state.isSuActive,
        showHidden = state.showHiddenFiles,
      )
      _uiState.update { latest ->
        if (latest.currentPath != targetPath) return@update latest.copy(isLoading = false)
        val filtered = filterAndSort(
          entries = result.entries,
          query = latest.searchQuery,
          showHidden = latest.showHiddenFiles,
          sortOrder = latest.sortOrder,
        )
        latest.copy(
          rawEntries = result.entries,
          visibleEntries = filtered,
          isLoading = false,
          accessDenied = result.accessDenied,
        )
      }
    }
  }

  private fun refreshMounts() {
    viewModelScope.launch {
      val mounts = shellManager.readMounts(_uiState.value.isSuActive)
      _uiState.update { it.copy(mounts = mounts) }
    }
  }

  fun onEntryClicked(entry: FileEntry) {
    val current = _uiState.value
    if (current.isSelectionMode) {
      toggleSelection(entry.path)
      return
    }
    when {
      entry.isDirectory -> navigateTo(entry.path)
      ExploreDParsers.isArchiveFile(entry) -> openArchiveViewer(entry)
      ExploreDParsers.isSqliteFile(entry) -> openSqliteViewer(entry)
      ExploreDParsers.isTextInspectable(entry) -> openTextViewer(entry)
      else -> _uiState.update { it.copy(inspectedEntry = entry) }
    }
  }

  fun onEntryLongPressed(entry: FileEntry) {
    toggleSelection(entry.path)
    _uiState.update { it.copy(inspectedEntry = entry) }
  }

  fun toggleSelection(path: String) {
    _uiState.update { state ->
      val next = state.selectedPaths.toMutableSet()
      if (!next.add(path)) next.remove(path)
      state.copy(selectedPaths = next)
    }
  }

  fun clearSelection() {
    _uiState.update { it.copy(selectedPaths = emptySet()) }
  }

  fun selectAllVisible() {
    _uiState.update { state ->
      state.copy(selectedPaths = state.visibleEntries.map { it.path }.toSet())
    }
  }

  // --- PIN-Protected SU Flow (no clearing once set) ---

  fun onSuToggleClicked() {
    if (_uiState.value.isSuActive) lockSu() else requestEnableSu()
  }

  fun requestEnableSu(afterUnlock: (suspend () -> Unit)? = null) {
    pendingPostSuAction = afterUnlock
    viewModelScope.launch {
      val hasPin = pinPrefs.hasPin()
      _uiState.update {
        it.copy(
          hasPinConfigured = hasPin,
          pinError = null,
          pinDialogMode = if (hasPin) PinDialogMode.UNLOCK else PinDialogMode.CREATE,
        )
      }
    }
  }

  fun dismissPinDialog() {
    pendingPostSuAction = null
    _uiState.update { it.copy(pinDialogMode = null, pinError = null) }
  }

  fun submitCreatePin(pin: String, confirmPin: String) {
    val clean = pin.trim()
    if (clean.length !in 4..8 || !clean.all { it.isDigit() }) {
      _uiState.update { it.copy(pinError = "PIN must be 4–8 digits") }
      return
    }
    if (clean != confirmPin.trim()) {
      _uiState.update { it.copy(pinError = "PINs do not match") }
      return
    }
    viewModelScope.launch {
      pinPrefs.setPin(clean)
      val wasChangeFlow = _uiState.value.pinDialogMode == PinDialogMode.CHANGE_NEW
      _uiState.update {
        it.copy(
          hasPinConfigured = true,
          pinDialogMode = null,
          pinError = null,
        )
      }
      if (wasChangeFlow) {
        setStatus("SU PIN updated", isError = false)
      } else {
        performSuElevation()
      }
    }
  }

  fun submitUnlockPin(pin: String) {
    val clean = pin.trim()
    if (clean.isEmpty()) {
      _uiState.update { it.copy(pinError = "Enter your PIN") }
      return
    }
    viewModelScope.launch {
      val valid = pinPrefs.verifyPin(clean)
      if (!valid) {
        _uiState.update { it.copy(pinError = "Incorrect PIN") }
        return@launch
      }
      val mode = _uiState.value.pinDialogMode
      if (mode == PinDialogMode.CHANGE_CURRENT) {
        _uiState.update {
          it.copy(
            pinDialogMode = PinDialogMode.CHANGE_NEW,
            pinError = null,
          )
        }
        return@launch
      }
      _uiState.update { it.copy(pinDialogMode = null, pinError = null) }
      performSuElevation()
    }
  }

  private suspend fun performSuElevation() {
    val result = shellManager.enableSu()
    if (!result.success) {
      pendingPostSuAction = null
      _uiState.update {
        it.copy(
          isSuActive = false,
          statusMessage = result.message,
          statusIsError = true,
        )
      }
      return
    }
    _uiState.update {
      it.copy(
        isSuActive = true,
        statusMessage = "Superuser unlocked",
        statusIsError = false,
      )
    }
    refreshAll()
    val post = pendingPostSuAction
    pendingPostSuAction = null
    post?.invoke()
  }

  fun lockSu() {
    viewModelScope.launch {
      shellManager.disableSu()
      _uiState.update {
        it.copy(
          isSuActive = false,
          statusMessage = "SU locked",
          statusIsError = false,
        )
      }
      refreshAll()
    }
  }

  fun openChangePinFlow() {
    viewModelScope.launch {
      val hasPin = pinPrefs.hasPin()
      _uiState.update {
        it.copy(
          showToolsSheet = false,
          pinError = null,
          pinDialogMode = if (hasPin) PinDialogMode.CHANGE_CURRENT else PinDialogMode.CREATE,
        )
      }
    }
  }

  // --- Protected System Modification Gate + Auto-Mount ---

  private fun isPathProtected(path: String): Boolean =
    ExploreDParsers.isSystemPartitionPath(path, internalStorageRoot)

  private fun gateOrExecute(
    actionLabel: String,
    affectedPaths: List<String>,
    action: PendingSystemAction,
  ) {
    val protectedTarget = affectedPaths.firstOrNull { isPathProtected(it) }
    if (protectedTarget != null) {
      _uiState.update {
        it.copy(
          systemWarning = SystemModificationWarning(
            actionLabel = actionLabel,
            targetPath = protectedTarget,
            action = action,
          ),
        )
      }
      return
    }
    viewModelScope.launch {
      executeAction(action, autoMountPaths = emptyList())
    }
  }

  fun dismissSystemWarning() {
    _uiState.update { it.copy(systemWarning = null) }
  }

  fun confirmSystemModification() {
    val warning = _uiState.value.systemWarning ?: return
    _uiState.update { it.copy(systemWarning = null) }
    val affected = collectAffectedPaths(warning.action)
    if (!_uiState.value.isSuActive) {
      requestEnableSu {
        executeAction(warning.action, autoMountPaths = affected)
      }
      return
    }
    viewModelScope.launch {
      executeAction(warning.action, autoMountPaths = affected)
    }
  }

  private fun collectAffectedPaths(action: PendingSystemAction): List<String> = when (action) {
    is PendingSystemAction.CreateFolder -> listOf(action.parentDir)
    is PendingSystemAction.CreateFile -> listOf(action.parentDir)
    is PendingSystemAction.Rename -> listOf(action.entry.path)
    is PendingSystemAction.Paste -> {
      if (action.clip.mode == ClipboardMode.MOVE) {
        listOf(action.targetDir) + action.clip.paths
      } else {
        listOf(action.targetDir)
      }
    }
    is PendingSystemAction.Delete -> action.paths
    is PendingSystemAction.ApplyPermissions -> listOf(action.entry.path)
    is PendingSystemAction.SaveText -> listOf(action.entry.path)
    is PendingSystemAction.ExtractArchive -> listOf(action.targetDir)
  }

  private suspend fun executeAction(
    action: PendingSystemAction,
    autoMountPaths: List<String>,
  ) {
    val useSu = _uiState.value.isSuActive
    if (useSu && autoMountPaths.isNotEmpty()) {
      val mountRes = shellManager.ensureWritableMounts(autoMountPaths, _uiState.value.mounts)
      if (!mountRes.success) {
        setStatus(mountRes.message, isError = true)
        return
      }
      refreshMounts()
    }

    when (action) {
      is PendingSystemAction.CreateFolder -> {
        val res = shellManager.createFolder(action.parentDir, action.name, useSu)
        setStatus(res.message, isError = !res.success)
        if (res.success) refreshDirectory()
      }

      is PendingSystemAction.CreateFile -> {
        val res = shellManager.createFile(action.parentDir, action.name, useSu)
        setStatus(res.message, isError = !res.success)
        if (res.success) refreshDirectory()
      }

      is PendingSystemAction.Rename -> {
        val res = shellManager.renameEntry(action.entry.path, action.newName, useSu)
        setStatus(res.message, isError = !res.success)
        if (res.success) {
          clearSelection()
          refreshDirectory()
        }
      }

      is PendingSystemAction.Paste -> {
        val res = when (action.clip.mode) {
          ClipboardMode.COPY -> shellManager.copyEntries(action.clip.paths, action.targetDir, useSu)
          ClipboardMode.MOVE -> shellManager.moveEntries(action.clip.paths, action.targetDir, useSu)
        }
        setStatus(res.message, isError = !res.success)
        if (res.success) {
          if (action.clip.mode == ClipboardMode.MOVE) {
            _uiState.update { it.copy(clipboard = null) }
          }
          refreshDirectory()
        }
      }

      is PendingSystemAction.Delete -> {
        val res = shellManager.deleteEntries(action.paths, useSu)
        setStatus(res.message, isError = !res.success)
        if (res.success) {
          _uiState.update { it.copy(selectedPaths = emptySet(), inspectedEntry = null) }
          refreshDirectory()
        }
      }

      is PendingSystemAction.ApplyPermissions -> {
        val res = shellManager.changePermissions(action.entry.path, action.octal, action.recursive, useSu)
        var finalMsg = res.message
        var isErr = !res.success
        if (res.success && !action.ownerGroup.isNullOrBlank() && useSu &&
          action.ownerGroup != "${action.entry.owner}:${action.entry.group}"
        ) {
          val chownRes = shellManager.changeOwnership(action.entry.path, action.ownerGroup, action.recursive)
          if (!chownRes.success) {
            finalMsg = "Permissions updated, chown failed: ${chownRes.message}"
            isErr = true
          } else {
            finalMsg = "Updated mode ${action.octal} & owner ${action.ownerGroup}"
          }
        }
        _uiState.update { it.copy(permissionsTarget = null) }
        setStatus(finalMsg, isError = isErr)
        refreshDirectory()
      }

      is PendingSystemAction.SaveText -> {
        _uiState.update { it.copy(textEditor = it.textEditor?.copy(isSaving = true)) }
        val res = shellManager.writeTextFile(action.entry.path, action.content, useSu)
        _uiState.update {
          it.copy(textEditor = if (res.success) null else it.textEditor?.copy(isSaving = false))
        }
        setStatus(res.message, isError = !res.success)
        if (res.success) refreshDirectory()
      }

      is PendingSystemAction.ExtractArchive -> {
        performArchiveExtraction(action.entry, action.targetDir, useSu)
      }
    }
  }

  // --- File Operations ---

  fun createFolder(name: String) {
    val clean = name.trim()
    if (clean.isEmpty() || clean.contains('/')) {
      setStatus("Invalid folder name", isError = true)
      return
    }
    val parent = _uiState.value.currentPath
    gateOrExecute("Create folder \"$clean\"", listOf(parent), PendingSystemAction.CreateFolder(parent, clean))
  }

  fun createFile(name: String) {
    val clean = name.trim()
    if (clean.isEmpty() || clean.contains('/')) {
      setStatus("Invalid file name", isError = true)
      return
    }
    val parent = _uiState.value.currentPath
    gateOrExecute("Create file \"$clean\"", listOf(parent), PendingSystemAction.CreateFile(parent, clean))
  }

  fun renameEntry(entry: FileEntry, newName: String) {
    val clean = newName.trim()
    if (clean.isEmpty() || clean.contains('/')) {
      setStatus("Invalid name", isError = true)
      return
    }
    gateOrExecute("Rename \"${entry.name}\"", listOf(entry.path), PendingSystemAction.Rename(entry, clean))
  }

  fun stageClipboard(mode: ClipboardMode, paths: Set<String> = _uiState.value.selectedPaths) {
    if (paths.isEmpty()) return
    val currentDir = _uiState.value.currentPath
    _uiState.update {
      it.copy(
        clipboard = ClipboardState(mode = mode, paths = paths, sourceDir = currentDir),
        selectedPaths = emptySet(),
        statusMessage = if (mode == ClipboardMode.COPY) {
          "${paths.size} item(s) ready to copy"
        } else {
          "${paths.size} item(s) ready to move"
        },
        statusIsError = false,
      )
    }
  }

  fun clearClipboard() {
    _uiState.update { it.copy(clipboard = null) }
  }

  fun pasteClipboardHere() {
    val clip = _uiState.value.clipboard ?: return
    val targetDir = _uiState.value.currentPath
    val pathsToCheck = if (clip.mode == ClipboardMode.MOVE) {
      listOf(targetDir) + clip.paths
    } else {
      listOf(targetDir)
    }
    val label = if (clip.mode == ClipboardMode.COPY) "Copy ${clip.paths.size} item(s)" else "Move ${clip.paths.size} item(s)"
    gateOrExecute(label, pathsToCheck, PendingSystemAction.Paste(clip, targetDir))
  }

  fun deletePaths(paths: Collection<String>) {
    val list = paths.toList()
    if (list.isEmpty()) return
    gateOrExecute("Delete ${list.size} item(s)", list, PendingSystemAction.Delete(list))
  }

  // --- Permissions ---

  fun openPermissionsEditor(entry: FileEntry) {
    if (!ExploreDParsers.isSystemPartitionPath(entry.path, internalStorageRoot)) {
      setStatus("Permissions editor is only enabled on system partitions", isError = false)
      return
    }
    _uiState.update { it.copy(permissionsTarget = entry) }
  }

  fun closePermissionsEditor() {
    _uiState.update { it.copy(permissionsTarget = null) }
  }

  fun applyPermissions(
    entry: FileEntry,
    octal: String,
    recursive: Boolean,
    ownerGroup: String?,
  ) {
    gateOrExecute(
      "Change permissions to $octal",
      listOf(entry.path),
      PendingSystemAction.ApplyPermissions(entry, octal, recursive, ownerGroup),
    )
  }

  // --- Text Editor ---

  fun openTextViewer(entry: FileEntry) {
    viewModelScope.launch {
      val res = shellManager.readTextFile(entry.path, _uiState.value.isSuActive)
      res.onSuccess { text ->
        _uiState.update { it.copy(textEditor = TextEditorState(entry = entry, content = text)) }
      }.onFailure { err ->
        setStatus(err.message ?: "Unable to open ${entry.name}", isError = true)
      }
    }
  }

  fun closeTextViewer() {
    _uiState.update { it.copy(textEditor = null) }
  }

  fun saveTextFile(entry: FileEntry, newContent: String) {
    gateOrExecute(
      "Modify file \"${entry.name}\"",
      listOf(entry.path),
      PendingSystemAction.SaveText(entry, newContent),
    )
  }

  // --- Archive Viewer & Extractor (ZIP / APK / TAR / RAR) ---

  fun openArchiveViewer(entry: FileEntry) {
    viewModelScope.launch {
      val useSu = _uiState.value.isSuActive
      val formatLabel = when {
        entry.extension == "apk" -> "APK Package"
        ExploreDParsers.isZipOrApkFile(entry) -> "ZIP Archive"
        ExploreDParsers.isTarFile(entry) -> "TAR Archive"
        ExploreDParsers.isRarFile(entry) -> "RAR Archive"
        else -> "Archive"
      }

      if (ExploreDParsers.isRarFile(entry)) {
        val rarRes = shellManager.listRarEntries(entry.path, useSu)
        rarRes.onSuccess { items ->
          _uiState.update {
            it.copy(archiveViewer = ArchiveViewerState(entry, formatLabel, items))
          }
        }.onFailure { err ->
          _uiState.update {
            it.copy(archiveViewer = ArchiveViewerState(entry, formatLabel, emptyList(), error = err.message))
          }
        }
        return@launch
      }

      val localFile = resolveReadableLocalFile(entry, useSu)
      if (localFile == null) {
        setStatus("Unable to read ${entry.name}", isError = true)
        return@launch
      }

      val res = if (ExploreDParsers.isZipOrApkFile(entry)) {
        ArchiveAndSqliteHelper.listZipOrApkEntries(localFile)
      } else {
        ArchiveAndSqliteHelper.listTarEntries(localFile)
      }

      res.onSuccess { items ->
        _uiState.update {
          it.copy(archiveViewer = ArchiveViewerState(entry, formatLabel, items))
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(archiveViewer = ArchiveViewerState(entry, formatLabel, emptyList(), error = err.message))
        }
      }
    }
  }

  fun closeArchiveViewer() {
    activeTempInspectorFile?.delete()
    activeTempInspectorFile = null
    _uiState.update { it.copy(archiveViewer = null) }
  }

  fun extractArchive(entry: FileEntry, intoSubfolder: Boolean) {
    val parentDir = File(entry.path).parent ?: _uiState.value.currentPath
    val folderName = entry.name
      .removeSuffix(".tar.gz")
      .removeSuffix(".tgz")
      .substringBeforeLast('.', entry.name + "_extracted")
    val targetDir = if (intoSubfolder) File(parentDir, folderName).absolutePath else parentDir
    gateOrExecute(
      "Extract \"${entry.name}\"",
      listOf(targetDir),
      PendingSystemAction.ExtractArchive(entry, targetDir),
    )
  }

  private suspend fun performArchiveExtraction(
    entry: FileEntry,
    targetDir: String,
    useSu: Boolean,
  ) {
    _uiState.update { it.copy(archiveViewer = it.archiveViewer?.copy(isExtracting = true)) }

    if (ExploreDParsers.isRarFile(entry)) {
      val res = shellManager.extractRarArchive(entry.path, targetDir, useSu)
      _uiState.update { it.copy(archiveViewer = null) }
      setStatus(res.message, isError = !res.success)
      if (res.success) refreshDirectory()
      return
    }

    val readableArchive = resolveReadableLocalFile(entry, useSu)
    if (readableArchive == null) {
      _uiState.update { it.copy(archiveViewer = it.archiveViewer?.copy(isExtracting = false)) }
      setStatus("Unable to read ${entry.name}", isError = true)
      return
    }

    val directTarget = File(targetDir)
    val canWriteDirectly = !useSu && (directTarget.canWrite() || directTarget.parentFile?.canWrite() == true)
    if (canWriteDirectly) {
      val extractRes = if (ExploreDParsers.isZipOrApkFile(entry)) {
        ArchiveAndSqliteHelper.extractZipOrApk(readableArchive, directTarget)
      } else {
        ArchiveAndSqliteHelper.extractTar(readableArchive, directTarget)
      }
      _uiState.update { it.copy(archiveViewer = null) }
      extractRes.onSuccess { count ->
        setStatus("Extracted $count item(s)", isError = false)
        refreshDirectory()
      }.onFailure { err ->
        setStatus(err.message ?: "Extraction failed", isError = true)
      }
      return
    }

    // Root/system extraction via cache stage + su copy
    val cacheStage = File(getApplication<Application>().cacheDir, "explored_extract_${System.nanoTime()}")
    val extractRes = if (ExploreDParsers.isZipOrApkFile(entry)) {
      ArchiveAndSqliteHelper.extractZipOrApk(readableArchive, cacheStage)
    } else {
      ArchiveAndSqliteHelper.extractTar(readableArchive, cacheStage)
    }
    extractRes.onSuccess { count ->
      shellManager.createFolder(File(targetDir).parent ?: "/", File(targetDir).name, useSu)
      val copyRes = shellManager.copyDirContents(cacheStage, targetDir, useSu)
      cacheStage.deleteRecursively()
      _uiState.update { it.copy(archiveViewer = null) }
      setStatus(
        if (copyRes.success) "Extracted $count item(s)" else copyRes.message,
        isError = !copyRes.success,
      )
      if (copyRes.success) refreshDirectory()
    }.onFailure { err ->
      cacheStage.deleteRecursively()
      _uiState.update { it.copy(archiveViewer = it.archiveViewer?.copy(isExtracting = false)) }
      setStatus(err.message ?: "Extraction failed", isError = true)
    }
  }

  // --- SQLite Viewer ---

  fun openSqliteViewer(entry: FileEntry, tableName: String? = null) {
    viewModelScope.launch {
      val useSu = _uiState.value.isSuActive
      val localFile = resolveReadableLocalFile(entry, useSu)
      if (localFile == null) {
        setStatus("Unable to open ${entry.name}", isError = true)
        return@launch
      }
      val info = ArchiveAndSqliteHelper.inspectSqliteDatabase(
        entry = entry,
        readableFile = localFile,
        tempCopyPath = if (localFile.absolutePath != entry.path) localFile.absolutePath else null,
        requestedTable = tableName,
      )
      _uiState.update { it.copy(sqliteViewer = info) }
    }
  }

  fun selectSqliteTable(tableName: String) {
    val current = _uiState.value.sqliteViewer ?: return
    val readable = current.tempCopyPath?.let { File(it) } ?: File(current.entry.path)
    viewModelScope.launch {
      val updated = ArchiveAndSqliteHelper.inspectSqliteDatabase(
        entry = current.entry,
        readableFile = readable,
        tempCopyPath = current.tempCopyPath,
        requestedTable = tableName,
      )
      _uiState.update { it.copy(sqliteViewer = updated) }
    }
  }

  fun closeSqliteViewer() {
    activeTempInspectorFile?.delete()
    activeTempInspectorFile = null
    _uiState.update { it.copy(sqliteViewer = null) }
  }

  private suspend fun resolveReadableLocalFile(entry: FileEntry, useSu: Boolean): File? {
    val direct = File(entry.path)
    if (direct.canRead()) return direct
    val cacheFile = File(getApplication<Application>().cacheDir, "inspect_${System.nanoTime()}_${entry.name}")
    val res = shellManager.copyReadableToTemp(entry.path, cacheFile, useSu)
    if (!res.success || !cacheFile.exists()) {
      cacheFile.delete()
      return null
    }
    activeTempInspectorFile?.delete()
    activeTempInspectorFile = cacheFile
    return cacheFile
  }

  // --- Root Tools & Preferences ---

  fun openToolsSheet() {
    _uiState.update { it.copy(showToolsSheet = true) }
    viewModelScope.launch {
      val mode = shellManager.getSeLinuxMode()
      _uiState.update { it.copy(selinuxMode = mode) }
    }
  }

  fun closeToolsSheet() {
    _uiState.update { it.copy(showToolsSheet = false) }
  }

  fun toggleSeLinuxMode() {
    val currentlyEnforcing = _uiState.value.selinuxMode.equals("Enforcing", ignoreCase = true)
    viewModelScope.launch {
      val res = shellManager.setSeLinuxPermissive(currentlyEnforcing)
      val refreshed = shellManager.getSeLinuxMode()
      _uiState.update { it.copy(selinuxMode = refreshed) }
      setStatus(res.message, isError = !res.success)
    }
  }

  fun runQuickRootCommand(cmd: String) {
    viewModelScope.launch {
      val res = shellManager.runRootCommand(cmd)
      _uiState.update { it.copy(quickToolOutput = res.message) }
    }
  }

  fun triggerReboot(target: String) {
    viewModelScope.launch {
      val res = shellManager.rebootDevice(target)
      setStatus(res.message, isError = !res.success)
    }
  }

  fun setShowHiddenFiles(show: Boolean) {
    viewModelScope.launch {
      pinPrefs.setShowHiddenFiles(show)
      refreshDirectory()
    }
  }

  fun setSortOrder(order: SortOrder) {
    viewModelScope.launch {
      pinPrefs.setSortOrder(order)
    }
  }

  private fun setStatus(msg: String, isError: Boolean) {
    _uiState.update { it.copy(statusMessage = msg, statusIsError = isError) }
  }

  private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    return trimmed.trimEnd('/')
  }

  private fun filterAndSort(
    entries: List<FileEntry>,
    query: String,
    showHidden: Boolean,
    sortOrder: SortOrder,
  ): List<FileEntry> {
    val cleanQuery = query.trim().lowercase(Locale.ROOT)
    val filtered = entries.filter { entry ->
      (showHidden || !entry.isHidden) &&
        (cleanQuery.isEmpty() || entry.name.lowercase(Locale.ROOT).contains(cleanQuery))
    }

    return filtered.sortedWith(
      compareBy<FileEntry> { !it.isDirectory }
        .thenBy { entry ->
          when (sortOrder) {
            SortOrder.NAME -> entry.name.lowercase(Locale.ROOT)
            SortOrder.TYPE -> entry.extension + "|" + entry.name.lowercase(Locale.ROOT)
            SortOrder.SIZE -> String.format(Locale.US, "%020d", Long.MAX_VALUE - entry.sizeBytes)
            SortOrder.DATE -> entry.modifiedDisplay
          }
        }
        .let { baseComparator ->
          if (sortOrder == SortOrder.DATE) {
            compareBy<FileEntry> { !it.isDirectory }
              .thenByDescending { it.modifiedDisplay }
              .thenBy { it.name.lowercase(Locale.ROOT) }
          } else {
            baseComparator
          }
        },
    )
  }
}
