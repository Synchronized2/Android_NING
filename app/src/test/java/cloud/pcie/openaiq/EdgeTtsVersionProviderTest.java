package cloud.pcie.openaiq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EdgeTtsVersionProviderTest {
    @Test
    public void gecV2VersionUsesFullBrowserVersion() {
        assertEquals("2-156.0.4314.42",
                EdgeTtsVersionProvider.toGecVersion("156.0.4314.42"));
    }

    @Test
    public void invalidBrowserVersionFallsBack() {
        assertEquals("2-156.0.4314.42", EdgeTtsVersionProvider.toGecVersion("invalid"));
        assertFalse(EdgeTtsVersionProvider.isValidBrowserVersion("156.0.4314"));
        assertTrue(EdgeTtsVersionProvider.isValidBrowserVersion("156.0.4314.42"));
    }

    @Test
    public void versionComparisonIsNumeric() {
        assertTrue(EdgeTtsVersionProvider.compareVersions(
                "156.0.4314.42", "99.0.9999.999") > 0);
        assertTrue(EdgeTtsVersionProvider.compareVersions(
                "156.0.4314.42", "156.0.4314.9") > 0);
        assertEquals(0, EdgeTtsVersionProvider.compareVersions(
                "156.0.4314.42", "156.0.4314.42"));
    }

    @Test
    public void stableWindowsX64ParserIgnoresOtherChannelsAndArchitectures() throws Exception {
        String json = "["
                + "{\"Product\":\"Dev\",\"Releases\":[{\"Platform\":\"Windows\","
                + "\"Architecture\":\"x64\",\"ProductVersion\":\"999.0.0.0\"}]},"
                + "{\"Product\":\"Stable\",\"Releases\":["
                + "{\"Platform\":\"Windows\",\"Architecture\":\"arm64\","
                + "\"ProductVersion\":\"200.0.0.0\"},"
                + "{\"Platform\":\"Windows\",\"Architecture\":\"x64\","
                + "\"ProductVersion\":\"155.0.4283.62\"},"
                + "{\"Platform\":\"Windows\",\"Architecture\":\"x64\","
                + "\"ProductVersion\":\"156.0.4314.42\"}]}]";

        assertEquals("156.0.4314.42",
                EdgeTtsVersionProvider.parseLatestStableVersion(json));
    }

    @Test
    public void parserReturnsEmptyWhenNoMatchingReleaseExists() throws Exception {
        assertEquals("", EdgeTtsVersionProvider.parseLatestStableVersion(
                "[{\"Product\":\"Beta\",\"Releases\":[]}]"));
    }

    @Test
    public void cacheExpiresAfterTwentyFourHours() {
        long now = 1_000_000_000L;
        assertTrue(EdgeTtsVersionProvider.isCacheFresh(now - 1_000L, now));
        assertFalse(EdgeTtsVersionProvider.isCacheFresh(now - 86_400_000L, now));
        assertFalse(EdgeTtsVersionProvider.isCacheFresh(now + 1L, now));
    }
}
