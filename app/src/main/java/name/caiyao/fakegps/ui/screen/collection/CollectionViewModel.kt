package name.caiyao.fakegps.ui.screen.collection

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.data.importer.ImportIssueCode
import name.caiyao.fakegps.data.importer.ProfileArchiveParser
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import name.caiyao.fakegps.data.importer.ProfileImportIssue
import name.caiyao.fakegps.data.repository.ProfileRepository

class CollectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)
    private val parser = ProfileArchiveParser()
    private var importGeneration = 0L
    private var parseJob: Job? = null

    private val _importState = MutableStateFlow<ProfileImportUiState>(ProfileImportUiState.Idle)
    val importState: StateFlow<ProfileImportUiState> = _importState

    val profiles: StateFlow<List<ProfileSummary>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Id of the profile the hook is actually running on, or null when there is none.
     *
     * ConfigPrefsSync publishes the FIRST row of `id ASC`, i.e. the OLDEST profile — while this list
     * is ordered `id DESC`, so the effective profile renders at the BOTTOM. Users had no way to tell
     * which of several profiles was live, and editing any other one appeared to do nothing. Derived
     * from the same rule the transport uses so the badge cannot drift away from the real behaviour.
     */
    val effectiveProfileId: StateFlow<Long?> = repo.observeAll()
        .map { list -> list.minByOrNull { it.id }?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteById(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { repo.deleteAll() }
    }

    fun previewImport(uri: Uri) {
        if (_importState.value is ProfileImportUiState.Importing) return
        parseJob?.cancel()
        val generation = ++importGeneration
        _importState.value = ProfileImportReducer.start(generation, "所选文件")
        parseJob = viewModelScope.launch {
            val (fileName, analysis) = withContext(Dispatchers.IO) {
                val resolvedName = resolveDisplayName(uri)
                val result = runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        parser.parse(resolvedName, input)
                    } ?: throw IOException("无法打开所选文件")
                }.getOrElse { failure ->
                    ProfileImportAnalysis.Invalid(
                        listOf(
                            ProfileImportIssue(
                                ImportIssueCode.MALFORMED_FILE,
                                failure.message ?: "文件读取失败",
                            ),
                        ),
                    )
                }
                resolvedName to result
            }
            _importState.value = ProfileImportReducer.analysis(
                current = _importState.value,
                generation = generation,
                fileName = fileName,
                result = analysis,
            )
        }
    }

    fun confirmImport() {
        val begin = ProfileImportReducer.beginImport(_importState.value) ?: return
        _importState.value = begin.state
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.importAll(begin.records) } }
                .onSuccess { result ->
                    _importState.value = ProfileImportReducer.imported(_importState.value, result)
                }
                .onFailure { failure ->
                    _importState.value = ProfileImportReducer.failed(
                        _importState.value,
                        failure.message ?: failure.javaClass.simpleName,
                    )
                }
        }
    }

    fun dismissImport() {
        if (_importState.value is ProfileImportUiState.Importing) return
        parseJob?.cancel()
        importGeneration++
        _importState.value = ProfileImportUiState.Idle
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "所选文件"
    }
}
