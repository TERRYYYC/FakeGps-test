package name.caiyao.fakegps.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AppDatabaseMigrationTest {

    @Test
    fun `migration adds nullable metadata without rewriting existing profile values`() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_1_2.migrate(db)

        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_1_2.endVersion)
        assertEquals(
            listOf("ALTER TABLE temp ADD COLUMN unavailable_fields TEXT DEFAULT NULL"),
            statements,
        )
    }
}
