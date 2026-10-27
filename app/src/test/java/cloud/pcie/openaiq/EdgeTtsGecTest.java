package cloud.pcie.openaiq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

public final class EdgeTtsGecTest {
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    @Test public void matchesReferenceImplementationVectors() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                EdgeTtsGec.generate(0L, TOKEN));
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                EdgeTtsGec.generate(1_700_000_000_000L, TOKEN));
        assertEquals("9E3C6465620A5A80-515C04C272DBB44052DAE411CFBB5F179FC1A62B2B35B5301549453D3AFB5E2F",
                EdgeTtsGec.generate(1_793_087_400_000L, TOKEN));
        assertEquals("A4041085BD509DF8-14BFA3B078890B8E9103909047D7289829B049F8AE2DDCFB8469C2A680D63B41",
                EdgeTtsGec.generate(1_793_087_700_000L, TOKEN));
    }

    @Test public void tokenChangesAtFiveMinuteBoundary() {
        assertEquals(EdgeTtsGec.generate(1_793_087_400_000L, TOKEN),
                EdgeTtsGec.generate(1_793_087_699_999L, TOKEN));
        assertNotEquals(EdgeTtsGec.generate(1_793_087_699_999L, TOKEN),
                EdgeTtsGec.generate(1_793_087_700_000L, TOKEN));
    }
}
