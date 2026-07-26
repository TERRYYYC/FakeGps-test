package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.Collection;

import org.junit.Test;

/**
 * Regression coverage for FC-9.
 *
 * Android 15 has no six-int CellIdentityLte constructor. The runtime factory must prefer the
 * modern constructor shape while retaining explicit legacy fallbacks for older/OEM frameworks.
 */
public class CellConstructorCompatTest {

    @Test
    public void stringConstructorFactoriesPreserveLeadingZeroPlmn() throws Exception {
        Method lteFactory;
        Method gsmFactory;
        Method wcdmaFactory;
        try {
            lteFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newLteIdentity", Class.class, String.class, String.class,
                    int.class, int.class, int.class, Integer.class, Integer.class);
            gsmFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newGsmIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class);
            wcdmaFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newWcdmaIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class);
        } catch (NoSuchMethodException missingStringPlmnContract) {
            fail("identity factories must accept the original PLMN strings");
            return;
        }

        FakeLteIdentityModern lteModern = (FakeLteIdentityModern) lteFactory.invoke(
                null, FakeLteIdentityModern.class, "025", "03",
                28378431, 53, 26999, 39300, 20000);
        FakeLteIdentityAndroidNine lteNine = (FakeLteIdentityAndroidNine) lteFactory.invoke(
                null, FakeLteIdentityAndroidNine.class, "025", "03",
                28378431, 53, 26999, 39300, 15000);
        FakeGsmIdentityModern gsmModern = (FakeGsmIdentityModern) gsmFactory.invoke(
                null, FakeGsmIdentityModern.class, "025", "03",
                401, 402, 975, 12);
        FakeGsmIdentityAndroidNine gsmNine = (FakeGsmIdentityAndroidNine) gsmFactory.invoke(
                null, FakeGsmIdentityAndroidNine.class, "025", "03",
                401, 402, 975, 12);
        FakeWcdmaIdentityModern wcdmaModern = (FakeWcdmaIdentityModern) wcdmaFactory.invoke(
                null, FakeWcdmaIdentityModern.class, "025", "03",
                501, 502, 73, 10613);
        FakeWcdmaIdentityAndroidNine wcdmaNine =
                (FakeWcdmaIdentityAndroidNine) wcdmaFactory.invoke(
                        null, FakeWcdmaIdentityAndroidNine.class, "025", "03",
                        501, 502, 73, 10613);

        assertEquals("025", lteModern.mcc);
        assertEquals("03", lteModern.mnc);
        assertEquals("025", lteNine.mcc);
        assertEquals("03", lteNine.mnc);
        assertEquals("025", gsmModern.mcc);
        assertEquals("03", gsmModern.mnc);
        assertEquals("025", gsmNine.mcc);
        assertEquals("03", gsmNine.mnc);
        assertEquals("025", wcdmaModern.mcc);
        assertEquals("03", wcdmaModern.mnc);
        assertEquals("025", wcdmaNine.mcc);
        assertEquals("03", wcdmaNine.mnc);
    }

    @Test
    public void lteIdentity_prefersModernTwelveArgumentShape() throws Exception {
        FakeLteIdentityModern value = (FakeLteIdentityModern)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityModern.class,
                        "255", "3", 28378431, 53, 26999, 39300, 20000);

        assertEquals(28378431, value.ci);
        assertEquals(53, value.pci);
        assertEquals(26999, value.tac);
        assertEquals(39300, value.earfcn);
        assertEquals(20000, value.bandwidth);
        assertEquals("255", value.mcc);
        assertEquals("3", value.mnc);
        assertTrue(value.additionalPlmns.isEmpty());
    }

    @Test
    public void lteIdentity_fallsBackToLegacyFiveArgumentShape() throws Exception {
        FakeLteIdentityLegacy value = (FakeLteIdentityLegacy)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityLegacy.class,
                        "255", "3", 28378431, 53, 26999, 39300, 20000);

        assertEquals(255, value.mcc);
        assertEquals(3, value.mnc);
        assertEquals(28378431, value.ci);
        assertEquals(53, value.pci);
        assertEquals(26999, value.tac);
    }

    @Test
    public void lteIdentity_supportsAndroidNineStringPlmnShape() throws Exception {
        FakeLteIdentityAndroidNine value = (FakeLteIdentityAndroidNine)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityAndroidNine.class,
                        "255", "3", 28378431, 53, 26999, 39300, 15000);

        assertEquals(28378431, value.ci);
        assertEquals(39300, value.earfcn);
        assertEquals(15000, value.bandwidth);
        assertEquals("255", value.mcc);
        assertEquals("3", value.mnc);
    }

    @Test
    public void gsmAndWcdmaIdentity_useModernStringPlmnShapes() throws Exception {
        FakeGsmIdentityModern gsm = (FakeGsmIdentityModern)
                CellConstructorCompat.newGsmIdentity(
                        FakeGsmIdentityModern.class, "255", "3", 401, 402, 975, 12);
        FakeWcdmaIdentityModern wcdma = (FakeWcdmaIdentityModern)
                CellConstructorCompat.newWcdmaIdentity(
                        FakeWcdmaIdentityModern.class, "255", "3", 501, 502, 73, 10613);

        assertEquals("255", gsm.mcc);
        assertEquals("3", gsm.mnc);
        assertEquals(401, gsm.lac);
        assertEquals(975, gsm.arfcn);
        assertEquals("255", wcdma.mcc);
        assertEquals("3", wcdma.mnc);
        assertEquals(10613, wcdma.uarfcn);
    }

    @Test
    public void gsmAndWcdmaIdentity_supportAndroidNineEightArgumentShapes() throws Exception {
        FakeGsmIdentityAndroidNine gsm = (FakeGsmIdentityAndroidNine)
                CellConstructorCompat.newGsmIdentity(
                        FakeGsmIdentityAndroidNine.class, "255", "3", 401, 402, 975, 12);
        FakeWcdmaIdentityAndroidNine wcdma = (FakeWcdmaIdentityAndroidNine)
                CellConstructorCompat.newWcdmaIdentity(
                        FakeWcdmaIdentityAndroidNine.class, "255", "3",
                        501, 502, 73, 10613);

        assertEquals("255", gsm.mcc);
        assertEquals("3", gsm.mnc);
        assertEquals(975, gsm.arfcn);
        assertEquals("255", wcdma.mcc);
        assertEquals("3", wcdma.mnc);
        assertEquals(10613, wcdma.uarfcn);
    }

    @Test
    public void lteSignal_prefersSevenArgumentShapeAndPreservesCqiPosition() throws Exception {
        FakeLteSignalModern signal = (FakeLteSignalModern)
                CellConstructorCompat.newLteSignal(
                        FakeLteSignalModern.class, -75, -96, -11, 18, 13, 4);

        assertEquals(-75, signal.rssi);
        assertEquals(-96, signal.rsrp);
        assertEquals(-11, signal.rsrq);
        assertEquals(18, signal.rssnr);
        assertEquals(Integer.MAX_VALUE, signal.cqiTableIndex);
        assertEquals(13, signal.cqi);
        assertEquals(4, signal.timingAdvance);
    }

    static final class FakeLteIdentityModern {
        final int ci, pci, tac, earfcn, bandwidth;
        final String mcc, mnc;
        final Collection<String> additionalPlmns;

        FakeLteIdentityModern(int ci, int pci, int tac, int earfcn, int[] bands,
                              int bandwidth, String mcc, String mnc,
                              String alphaLong, String alphaShort,
                              Collection<String> additionalPlmns, Object csgInfo) {
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
            this.earfcn = earfcn;
            this.bandwidth = bandwidth;
            this.mcc = mcc;
            this.mnc = mnc;
            this.additionalPlmns = additionalPlmns;
        }
    }

    static final class FakeLteIdentityLegacy {
        final int mcc, mnc, ci, pci, tac;

        FakeLteIdentityLegacy(int mcc, int mnc, int ci, int pci, int tac) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
        }
    }

    static final class FakeLteIdentityAndroidNine {
        final int ci, pci, tac, earfcn, bandwidth;
        final String mcc, mnc;

        FakeLteIdentityAndroidNine(int ci, int pci, int tac, int earfcn, int bandwidth,
                                   String mcc, String mnc, String alphaLong, String alphaShort) {
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
            this.earfcn = earfcn;
            this.bandwidth = bandwidth;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeGsmIdentityModern {
        final int lac, cid, arfcn, bsic;
        final String mcc, mnc;

        FakeGsmIdentityModern(int lac, int cid, int arfcn, int bsic,
                              String mcc, String mnc, String alphaLong, String alphaShort,
                              Collection<String> additionalPlmns) {
            this.lac = lac;
            this.cid = cid;
            this.arfcn = arfcn;
            this.bsic = bsic;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeWcdmaIdentityModern {
        final int lac, cid, psc, uarfcn;
        final String mcc, mnc;

        FakeWcdmaIdentityModern(int lac, int cid, int psc, int uarfcn,
                                String mcc, String mnc, String alphaLong, String alphaShort,
                                Collection<String> additionalPlmns, Object csgInfo) {
            this.lac = lac;
            this.cid = cid;
            this.psc = psc;
            this.uarfcn = uarfcn;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeGsmIdentityAndroidNine {
        final int lac, cid, arfcn, bsic;
        final String mcc, mnc;

        FakeGsmIdentityAndroidNine(int lac, int cid, int arfcn, int bsic,
                                   String mcc, String mnc, String alphaLong, String alphaShort) {
            this.lac = lac;
            this.cid = cid;
            this.arfcn = arfcn;
            this.bsic = bsic;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeWcdmaIdentityAndroidNine {
        final int lac, cid, psc, uarfcn;
        final String mcc, mnc;

        FakeWcdmaIdentityAndroidNine(int lac, int cid, int psc, int uarfcn,
                                     String mcc, String mnc,
                                     String alphaLong, String alphaShort) {
            this.lac = lac;
            this.cid = cid;
            this.psc = psc;
            this.uarfcn = uarfcn;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeLteSignalModern {
        final int rssi, rsrp, rsrq, rssnr, cqiTableIndex, cqi, timingAdvance;

        FakeLteSignalModern(int rssi, int rsrp, int rsrq, int rssnr,
                            int cqiTableIndex, int cqi, int timingAdvance) {
            this.rssi = rssi;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.rssnr = rssnr;
            this.cqiTableIndex = cqiTableIndex;
            this.cqi = cqi;
            this.timingAdvance = timingAdvance;
        }
    }
}
