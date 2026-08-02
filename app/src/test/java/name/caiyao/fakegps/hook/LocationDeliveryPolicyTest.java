package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class LocationDeliveryPolicyTest {

    @Test
    public void systemMockClearsOnlyLocationFields() {
        Snapshot snapshot = configuredSnapshot();

        Snapshot result = LocationDeliveryPolicy.apply(snapshot, "system_mock");

        assertFalse(result.hasLocation());
        assertEquals(null, result.latitude);
        assertEquals(null, result.longitude);
        assertEquals(null, result.altitude);
        assertEquals(null, result.speed);
        assertEquals(null, result.bearing);
        assertEquals(null, result.accuracy);
        assertEquals(Integer.valueOf(27101), result.tac);
        assertEquals("Kyiv-Lab", result.wifiSsid);
    }

    @Test
    public void hookAndUnknownModesKeepTheConfiguredSnapshot() {
        Snapshot hook = configuredSnapshot();
        Snapshot unknown = configuredSnapshot();

        assertEquals(hook, LocationDeliveryPolicy.apply(hook, "hook"));
        assertEquals(unknown, LocationDeliveryPolicy.apply(unknown, "future_mode"));
    }

    private static Snapshot configuredSnapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.latitude = 50.4501;
        snapshot.longitude = 30.5234;
        snapshot.altitude = 179.0;
        snapshot.speed = 0.0f;
        snapshot.bearing = 0.0f;
        snapshot.accuracy = 3.0f;
        snapshot.tac = 27101;
        snapshot.wifiSsid = "Kyiv-Lab";
        return snapshot;
    }
}
