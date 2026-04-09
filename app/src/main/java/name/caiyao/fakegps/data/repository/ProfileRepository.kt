package name.caiyao.fakegps.data.repository

import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileSummary
import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val db: AppDatabase) {

    private val dao get() = db.profileDao()

    fun observeAll(): Flow<List<ProfileSummary>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getById(id: Long): ProfileEntity? = dao.getById(id)

    suspend fun save(profile: ProfileEntity): Long {
        return if (profile.id == 0L) {
            dao.insert(profile)
        } else {
            dao.update(profile)
            profile.id
        }
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
