package name.caiyao.fakegps.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoProviderInstrumentedTest {

    @Test
    fun settingsRouteMatchesTheInstalledVariantAuthority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.data.AppInfoProvider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        assertEquals(authority, provider!!.authority)
        context.contentResolver.query(
            Uri.parse("content://$authority/settings"),
            null,
            null,
            null,
            null,
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
        }
    }
}
