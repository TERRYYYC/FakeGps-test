package name.caiyao.fakegps.data.repository

import android.content.Context
import android.util.Log
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileSummary
import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val db: AppDatabase, private val context: Context? = null) {

    private val dao get() = db.profileDao()

    fun observeAll(): Flow<List<ProfileSummary>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getById(id: Long): ProfileEntity? = dao.getById(id)

    suspend fun save(profile: ProfileEntity): Long {
        val id = if (profile.id == 0L) {
            dao.insert(profile)
        } else {
            dao.update(profile)
            profile.id
        }
        republish()
        return id
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
        republish()
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        republish()
    }

    /**
     * Re-publish the effective config to the world-readable prefs the hook reads.
     *
     * This lives in the REPOSITORY, not in a screen: the app has two parallel UIs (legacy
     * Fragments + Compose) and wiring the sync per-screen already caused a real bug — saving a
     * new location from the Compose UI left the hook running on a profile the user had deleted
     * (DB said 50.615936,26.278774 while the hook still read 50.257091,28.688807). Every
     * create/update/delete funnels through here, so the transport can no longer go stale.
     */
    private fun republish() {
        val ctx = context ?: return
        runCatching { ConfigPrefsSync.sync(ctx) }
            .onFailure { Log.e("ProfileRepository", "config republish failed", it) }
    }
}
