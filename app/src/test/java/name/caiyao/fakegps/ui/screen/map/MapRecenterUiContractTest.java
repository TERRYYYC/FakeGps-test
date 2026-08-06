package name.caiyao.fakegps.ui.screen.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Compiled contract for the map button's single "current effective location" meaning.
 *
 * <p>INV-5 (recentering must not mutate the map selection) is deliberately NOT asserted here.
 * Inside {@code recenterMap} it is a compile error: that function receives a
 * {@code () -> MapRecenterTarget} producer instead of the ViewModel, so {@code onMapTap} is out
 * of scope. At the call sites it is a review obligation — the function type constrains shape,
 * not purity — so a lambda with a selection side effect would still compile there.
 *
 * <p>Re-adding a source-parsing guard for the remaining half would re-introduce the silent
 * false-green this file used to carry: the old parser located function bodies via
 * {@code indexOf(") {")} and truncated without failing whenever a parameter list contained a
 * default lambda with braces. See issue #19.
 */
public class MapRecenterUiContractTest {

    @Test
    public void recenterRequestsCurrentLocationInsteadOfReadingAnUnboundedCache() throws Exception {
        String screen = classBytecode("name.caiyao.fakegps.ui.screen.map.MapScreenKt");

        assertTrue("recenter must request a current fix", screen.contains("getCurrentLocation"));
        assertFalse(
                "getLastKnownLocation can return the same minutes-old point forever",
                screen.contains("getLastKnownLocation"));
    }

    @Test
    public void buttonLabelsTheOneMeaningTruthfully() throws Exception {
        String screen = screenSource();

        assertTrue(screen.contains("归位到当前有效位置"));
    }

    private static String screenSource() throws Exception {
        Path fromRoot = Paths.get(
                "app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt");
        Path fromModule = Paths.get(
                "src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt");
        Path source = Files.exists(fromRoot) ? fromRoot : fromModule;
        assertTrue("MapScreen.kt source must be available to the contract", Files.exists(source));
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = MapRecenterUiContractTest.class
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
