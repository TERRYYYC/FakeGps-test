package name.caiyao.fakegps.data;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import name.caiyao.fakegps.BuildConfig;
import org.junit.Test;

/** Variant identity contract shared by the manifest, publisher and provider URI matcher. */
public class AppInfoProviderAuthorityTest {

    @Test
    public void providerWiresTheVariantApplicationIdThroughThePureAuthorityHelper() throws Exception {
        String provider = classBytecode("name.caiyao.fakegps.data.AppInfoProvider");

        assertTrue(provider.contains("ProviderAuthority"));
        assertTrue(provider.contains("forApplicationId"));
        assertTrue(provider.contains(BuildConfig.APPLICATION_ID));
        assertTrue(provider.contains("AUTHORITY"));
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = AppInfoProviderAuthorityTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
