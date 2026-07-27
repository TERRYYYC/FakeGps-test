package name.caiyao.fakegps.ui.screen.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.data.repository.ProfileRepository

class CollectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)

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
}
