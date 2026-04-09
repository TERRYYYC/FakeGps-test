package name.caiyao.fakegps.ui.screen.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.data.repository.ProfileRepository

data class TapPoint(val lat: Double, val lon: Double)

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app))

    val profiles: StateFlow<List<ProfileSummary>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profileCount: StateFlow<Int> = repo.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _tappedPoint = MutableStateFlow<TapPoint?>(null)
    val tappedPoint: StateFlow<TapPoint?> = _tappedPoint

    fun onMapTap(lat: Double, lon: Double) {
        _tappedPoint.value = TapPoint(lat, lon)
    }

    fun clearTap() {
        _tappedPoint.value = null
    }

    fun deleteAll() {
        viewModelScope.launch { repo.deleteAll() }
    }
}
