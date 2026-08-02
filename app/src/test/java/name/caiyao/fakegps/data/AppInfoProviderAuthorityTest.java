package name.caiyao.fakegps.data;

import static org.junit.Assert.assertEquals;

import name.caiyao.fakegps.BuildConfig;
import org.junit.Test;

/** Variant identity contract shared by the manifest, publisher and provider URI matcher. */
public class AppInfoProviderAuthorityTest {

    @Test
    public void providerAuthorityTracksTheCurrentApplicationId() {
        assertEquals(
                BuildConfig.APPLICATION_ID + ".data.AppInfoProvider",
                AppInfoProvider.AUTHORITY);
    }
}
