package name.caiyao.fakegps.hook;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Collections;

/**
 * Runtime constructor compatibility for hidden telephony value objects.
 *
 * These constructors are absent from the public SDK and have changed shape across Android
 * releases. Selecting by the constructors actually present on the device is safer than assuming
 * that one hard-coded arity applies to every API/ROM.
 */
final class CellConstructorCompat {

    private CellConstructorCompat() {}

    static Object newLteIdentity(Class<?> type, int mcc, int mnc, int ci, int pci, int tac,
                                 Integer earfcn, Integer bandwidth)
            throws ReflectiveOperationException {
        Constructor<?> modern = find(type, p ->
                p.length == 12
                        && ints(p, 0, 4)
                        && p[4] == int[].class
                        && p[5] == int.class
                        && strings(p, 6, 4)
                        && Collection.class.isAssignableFrom(p[10])
                        && !p[11].isPrimitive());
        if (modern != null) {
            return construct(modern,
                    ci, pci, tac, unavailable(earfcn), new int[0], unavailable(bandwidth),
                    String.valueOf(mcc), String.valueOf(mnc), null, null,
                    Collections.emptySet(), null);
        }

        // Android 9-10: ci/pci/tac/earfcn/bandwidth followed by string PLMN and alpha names.
        Constructor<?> androidNine = find(type, p ->
                p.length == 9 && ints(p, 0, 5) && strings(p, 5, 4));
        if (androidNine != null) {
            return construct(androidNine,
                    ci, pci, tac, unavailable(earfcn), unavailable(bandwidth),
                    String.valueOf(mcc), String.valueOf(mnc), null, null);
        }

        // Some vendor/older frameworks exposed EARFCN as a sixth integer.
        Constructor<?> vendorSix = find(type, p -> p.length == 6 && ints(p, 0, 6));
        if (vendorSix != null) {
            return construct(vendorSix, mcc, mnc, ci, pci, tac, unavailable(earfcn));
        }

        Constructor<?> legacy = find(type, p -> p.length == 5 && ints(p, 0, 5));
        if (legacy != null) {
            return construct(legacy, mcc, mnc, ci, pci, tac);
        }
        throw unsupported(type, "LTE identity (12/9/6/5)");
    }

    static Object newGsmIdentity(Class<?> type, int mcc, int mnc, int lac, int cid,
                                 Integer arfcn, Integer bsic)
            throws ReflectiveOperationException {
        Constructor<?> modern = find(type, p ->
                p.length == 9
                        && ints(p, 0, 4)
                        && strings(p, 4, 4)
                        && Collection.class.isAssignableFrom(p[8]));
        if (modern != null) {
            return construct(modern,
                    lac, cid, unavailable(arfcn), unavailable(bsic),
                    String.valueOf(mcc), String.valueOf(mnc), null, null,
                    Collections.emptySet());
        }

        // Android 9-10: the modern shape did not yet include additional PLMN collections.
        Constructor<?> androidNine = find(type, p ->
                p.length == 8 && ints(p, 0, 4) && strings(p, 4, 4));
        if (androidNine != null) {
            return construct(androidNine,
                    lac, cid, unavailable(arfcn), unavailable(bsic),
                    String.valueOf(mcc), String.valueOf(mnc), null, null);
        }

        Constructor<?> legacySix = find(type, p -> p.length == 6 && ints(p, 0, 6));
        if (legacySix != null) {
            return construct(legacySix, mcc, mnc, lac, cid,
                    unavailable(arfcn), unavailable(bsic));
        }

        Constructor<?> legacyFour = find(type, p -> p.length == 4 && ints(p, 0, 4));
        if (legacyFour != null) {
            return construct(legacyFour, mcc, mnc, lac, cid);
        }
        throw unsupported(type, "GSM identity (9/8/6/4)");
    }

    static Object newWcdmaIdentity(Class<?> type, int mcc, int mnc, int lac, int cid,
                                   Integer psc, Integer uarfcn)
            throws ReflectiveOperationException {
        Constructor<?> modern = find(type, p ->
                p.length == 10
                        && ints(p, 0, 4)
                        && strings(p, 4, 4)
                        && Collection.class.isAssignableFrom(p[8])
                        && !p[9].isPrimitive());
        if (modern != null) {
            return construct(modern,
                    lac, cid, unavailable(psc), unavailable(uarfcn),
                    String.valueOf(mcc), String.valueOf(mnc), null, null,
                    Collections.emptySet(), null);
        }

        // Android 9-10: the modern shape did not yet include additional PLMNs/CSG info.
        Constructor<?> androidNine = find(type, p ->
                p.length == 8 && ints(p, 0, 4) && strings(p, 4, 4));
        if (androidNine != null) {
            return construct(androidNine,
                    lac, cid, unavailable(psc), unavailable(uarfcn),
                    String.valueOf(mcc), String.valueOf(mnc), null, null);
        }

        Constructor<?> legacySix = find(type, p -> p.length == 6 && ints(p, 0, 6));
        if (legacySix != null) {
            return construct(legacySix, mcc, mnc, lac, cid,
                    unavailable(psc), unavailable(uarfcn));
        }

        Constructor<?> legacyFive = find(type, p -> p.length == 5 && ints(p, 0, 5));
        if (legacyFive != null) {
            return construct(legacyFive, mcc, mnc, lac, cid, unavailable(psc));
        }
        throw unsupported(type, "WCDMA identity (10/8/6/5)");
    }

    static Object newLteSignal(Class<?> type, int rssi, int rsrp, int rsrq, int rssnr,
                               int cqi, int timingAdvance)
            throws ReflectiveOperationException {
        Constructor<?> modern = find(type, p -> p.length == 7 && ints(p, 0, 7));
        if (modern != null) {
            return construct(modern,
                    rssi, rsrp, rsrq, rssnr, Integer.MAX_VALUE, cqi, timingAdvance);
        }

        Constructor<?> legacy = find(type, p -> p.length == 6 && ints(p, 0, 6));
        if (legacy != null) {
            return construct(legacy, rssi, rsrp, rsrq, rssnr, cqi, timingAdvance);
        }
        throw unsupported(type, "LTE signal (7/6)");
    }

    private interface Shape {
        boolean matches(Class<?>[] parameters);
    }

    private static Constructor<?> find(Class<?> type, Shape shape) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (shape.matches(constructor.getParameterTypes())) {
                return constructor;
            }
        }
        return null;
    }

    private static Object construct(Constructor<?> constructor, Object... args)
            throws ReflectiveOperationException {
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static boolean ints(Class<?>[] parameters, int start, int count) {
        for (int i = start; i < start + count; i++) {
            if (parameters[i] != int.class) return false;
        }
        return true;
    }

    private static boolean strings(Class<?>[] parameters, int start, int count) {
        for (int i = start; i < start + count; i++) {
            if (parameters[i] != String.class) return false;
        }
        return true;
    }

    private static int unavailable(Integer value) {
        return value != null ? value : Integer.MAX_VALUE;
    }

    private static NoSuchMethodException unsupported(Class<?> type, String expected) {
        return new NoSuchMethodException(type.getName() + " has no supported " + expected
                + " constructor");
    }
}
