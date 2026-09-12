package com.openconverter.app.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openconverter.app.engine.ProgressEvent
import com.openconverter.app.saf.SafAdapter
import com.openconverter.app.service.ConversionService
import com.openconverter.app.ui.components.FileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class FileEntry(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long = -1L,
    val state: FileState = FileState.Pending,
    val percent: Int = 0,
    val error: String? = null,
    val isSelected: Boolean = true,
    val source: String? = null,
)

data class HomeUiState(
    val files: List<FileEntry> = emptyList(),
    val outputFolderUri: String? = null,
    val outputFolderName: String? = null,
    val targetFormat: String = "mp3",
    val bitrate: String? = "320k",
    val running: Boolean = false,
    val showControlsSheet: Boolean = false,
    /** Set when the user picks a folder that Android 14 SAF rejects as
     *  "can't use this folder" (e.g. emulator's /sdcard/Music/ or any
     *  private-dir on Android 14). When non-null, Start is blocked and the
     *  Output-folder row surfaces this message. */
    val folderError: String? = null,
    val autoFetchEnabled: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var binder: ConversionService.LocalBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? ConversionService.LocalBinder ?: return
            binder = b
            viewModelScope.launch {
                b.service.progress.collect(::onEvent)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    fun bind(context: Context) {
        context.bindService(
            Intent(context, ConversionService::class.java),
            conn, Context.BIND_AUTO_CREATE,
        )
    }
    fun unbind(context: Context) {
        runCatching { context.unbindService(conn) }
        binder = null
    }

    fun setFiles(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val entries = uris.map { uri ->
            FileEntry(
                uri = uri.toString(),
                displayName = SafAdapter.queryDisplayName(ctx, uri),
                sizeBytes = mapSize(SafAdapter.querySize(ctx, uri)),
            )
        }
        _state.update { it.copy(files = entries) }
    }

    fun toggleSelection(uri: String, selected: Boolean) {
        _state.update { s ->
            s.copy(files = s.files.map {
                if (it.uri == uri) it.copy(isSelected = selected) else it
            })
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        _state.update { s ->
            s.copy(files = s.files.map { it.copy(isSelected = selected) })
        }
    }

    fun toggleAutoFetch(enabled: Boolean) {
        _state.update { it.copy(autoFetchEnabled = enabled) }
        if (enabled) {
            scanLocalMusic()
        }
    }

    private fun scanLocalMusic() {
        viewModelScope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    return@launch
                }
            }

            val scanConfigs = listOf(
                ScanConfig("/storage/emulated/0/Download/netease/cloudmusic/Music/", "网易云音乐"),
                ScanConfig("/storage/emulated/0/Download/kgmusic/", "酷狗音乐"),
                ScanConfig("/storage/emulated/0/Download/kgmusic/download/", "酷狗音乐"),
                ScanConfig("/storage/emulated/0/Download/kgmusic/download/kgmusic/", "酷狗音乐"),
                ScanConfig("/storage/emulated/0/Music/qqmusic/", "QQ音乐")
            )

            val audioExtensions = setOf(
                "mp3", "flac", "wav", "m4a", "ogg", "aac",
                "ncm", "kwm", "kgm", "kgma", "vpr", "kgg", "mgg", "mgg1", "bkc"
            )

            val newEntries = mutableListOf<FileEntry>()
            scanConfigs.forEach { config ->
                val dir = File(config.path)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && file.extension.lowercase() in audioExtensions) {
                            newEntries.add(FileEntry(
                                uri = Uri.fromFile(file).toString(),
                                displayName = file.name,
                                sizeBytes = file.length(),
                                source = config.label
                            ))
                        }
                    }
                }
            }

            if (newEntries.isNotEmpty()) {
                _state.update { s ->
                    val existingUris = s.files.map { it.uri }.toSet()
                    val filteredNew = newEntries.filter { it.uri !in existingUris }
                    s.copy(files = s.files + filteredNew)
                }
            }
        }
    }

    data class ScanConfig(val path: String, val label: String)

    fun setOutputFolder(uri: Uri) {
        val ctx = getApplication<Application>()
        val takeResult = runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (takeResult.isFailure) {
            _state.update { it.copy(
                outputFolderUri = null,
                outputFolderName = null,
                folderError = "Android refused permission for this folder. Pick another.",
            ) }
            return
        }
        val name = runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { uri.lastPathSegment }
        }.getOrNull() ?: "folder"
        val writeProbe = runCatching {
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri),
            )
            val probeName = ".oc-write-probe-${System.currentTimeMillis()}"
            val probeUri = DocumentsContract.createDocument(
                ctx.contentResolver, treeDocUri, "application/octet-stream", probeName,
            )
            if (probeUri == null) error("createDocument returned null")
            DocumentsContract.deleteDocument(ctx.contentResolver, probeUri)
        }
        if (writeProbe.isFailure) {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            _state.update { it.copy(
                outputFolderUri = null,
                outputFolderName = null,
                folderError = "Can't write to '$name'. Pick a folder you can create files in.",
            ) }
            return
        }
        _state.update { it.copy(
            outputFolderUri = uri.toString(),
            outputFolderName = name,
            folderError = null,
        ) }
    }
    fun setTargetFormat(fmt: String) = _state.update { it.copy(targetFormat = fmt) }
    fun setBitrate(b: String?) = _state.update { it.copy(bitrate = b) }

    fun openControlsSheet() = _state.update { it.copy(showControlsSheet = true) }
    fun closeControlsSheet() = _state.update { it.copy(showControlsSheet = false) }

    fun start(context: Context) {
        val s = _state.value
        val selectedFiles = s.files.filter { it.isSelected }
        if (selectedFiles.isEmpty() || s.outputFolderUri == null || s.running) return
        if (s.folderError != null) return
        
        _state.update { it.copy(
            running = true, 
            files = it.files.map { f -> 
                if (f.isSelected) f.copy(state = FileState.Pending, percent = 0, error = null) 
                else f 
            }
        ) }

        val intent = ConversionService.makeIntent(
            context,
            inputs = selectedFiles.map { it.uri },
            names  = selectedFiles.map { it.displayName },
            folder = s.outputFolderUri,
            target = s.targetFormat,
            bitrate = s.bitrate,
        )
        ContextCompat.startForegroundService(context, intent)
    }

    fun retryFailed(context: Context) {
        val retries = retryEntries(_state.value.files)
        if (retries.isEmpty()) return
        _state.update { it.copy(files = it.files.map { f ->
            if (f.state == FileState.Failed) f.copy(state = FileState.Pending, percent = 0, error = null)
            else f
        }) }
        start(context)
    }

    fun cancel(context: Context) {
        val cancel = Intent(context, ConversionService::class.java).setAction(ConversionService.ACTION_CANCEL)
        ContextCompat.startForegroundService(context, cancel)
    }

    fun clearFiles() {
        _state.update {
            it.copy(
                files = emptyList(),
                running = false,
                showControlsSheet = false,
                autoFetchEnabled = false,
            )
        }
    }

    private fun onEvent(ev: ProgressEvent) {
        _state.update { s ->
            val files = s.files.toMutableList()
            val selectedFiles = s.files.filter { it.isSelected }
            
            val originalIndex = if (ev is ProgressEvent.Progress || ev is ProgressEvent.Start || ev is ProgressEvent.Done || ev is ProgressEvent.Failed) {
                val indexInSelected = when(ev) {
                    is ProgressEvent.Start -> ev.index
                    is ProgressEvent.Progress -> ev.index
                    is ProgressEvent.Done -> ev.index
                    is ProgressEvent.Failed -> ev.index
                    else -> -1
                }
                if (indexInSelected in selectedFiles.indices) {
                    val targetUri = selectedFiles[indexInSelected].uri
                    files.indexOfFirst { it.uri == targetUri }
                } else -1
            } else -1

            when (ev) {
                is ProgressEvent.Start    -> if (originalIndex != -1) files[originalIndex] = files[originalIndex].copy(state = FileState.Running, percent = 0)
                is ProgressEvent.Progress -> if (originalIndex != -1) files[originalIndex] = files[originalIndex].copy(state = FileState.Running, percent = ev.percent)
                is ProgressEvent.Done     -> if (originalIndex != -1) files[originalIndex] = files[originalIndex].copy(state = FileState.Done, percent = 100)
                is ProgressEvent.Failed   -> if (originalIndex != -1) files[originalIndex] = files[originalIndex].copy(state = FileState.Failed, error = ev.message)
                ProgressEvent.BatchDone   -> return@update s.copy(running = false, files = files)
            }
            s.copy(files = files)
        }
    }

    companion object {
        fun mapSize(raw: Long): Long = if (raw > 0) raw else -1L

        fun retryEntries(files: List<FileEntry>): List<FileEntry> = files
            .filter { it.state == FileState.Failed && it.isSelected }
            .map { it.copy(state = FileState.Pending, percent = 0, error = null) }
    }
}
