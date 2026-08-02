package name.caiyao.fakegps.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Compiled contract for user actions that must never own concurrent mutations. */
public class UiActionOwnershipContractTest {

    @Test
    public void editorSaveActionsShareOneSingleFlightOwner() throws Exception {
        String editor = classBytecode(
                "name.caiyao.fakegps.ui.screen.editor.ProfileEditorViewModel")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.editor.ProfileEditorViewModel$save$1");

        assertTrue(editor.contains("SingleFlightGate"));
        assertTrue(editor.contains("tryStart"));
        assertTrue(editor.contains("finish"));
        assertTrue(editor.contains("saving"));
    }

    @Test
    public void verifyRefreshUsesTheSameSingleFlightPrimitive() throws Exception {
        String verify = classBytecode(
                "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1")
                + classBytecode(
                        "name.caiyao.fakegps.ui.screen.verify.VerifyViewModel$refresh$1$1");

        assertTrue(verify.contains("SingleFlightGate"));
        assertTrue(verify.contains("tryStart"));
        assertTrue(verify.contains("finish"));
    }

    private static String classBytecode(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = UiActionOwnershipContractTest.class
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
