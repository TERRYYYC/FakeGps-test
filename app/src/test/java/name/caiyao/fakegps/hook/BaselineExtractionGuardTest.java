package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Regression coverage for FC-10: real-baseline reads must bypass this module's own getter hooks. */
public class BaselineExtractionGuardTest {

    @Test
    public void guardIsActiveOnlyInsideExtraction() {
        assertFalse(BaselineExtractionGuard.isActive());
        boolean activeInside = BaselineExtractionGuard.call(BaselineExtractionGuard::isActive);
        assertTrue(activeInside);
        assertFalse(BaselineExtractionGuard.isActive());
    }

    @Test
    public void nestedExtractionKeepsGuardActiveUntilOutermostReturn() {
        BaselineExtractionGuard.call(() -> {
            assertTrue(BaselineExtractionGuard.isActive());
            BaselineExtractionGuard.call(() -> {
                assertTrue(BaselineExtractionGuard.isActive());
                return null;
            });
            assertTrue(BaselineExtractionGuard.isActive());
            return null;
        });
        assertFalse(BaselineExtractionGuard.isActive());
    }

    @Test
    public void exceptionCannotLeakExtractionModeIntoLaterAppCalls() {
        try {
            BaselineExtractionGuard.call(() -> {
                throw new IllegalStateException("boom");
            });
            fail("expected exception");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertFalse(BaselineExtractionGuard.isActive());
    }
}
