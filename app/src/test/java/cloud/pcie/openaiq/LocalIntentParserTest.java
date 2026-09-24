package cloud.pcie.openaiq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalIntentParserTest {
    @Test
    public void nextTrackAcceptsChineseTrackSynonyms() {
        assertMediaAction("播放下一曲", "next");
        assertMediaAction("下一曲", "next");
        assertMediaAction("切到下一曲", "next");
        assertMediaAction("播放下一首歌", "next");
    }

    @Test
    public void previousTrackAcceptsChineseTrackSynonyms() {
        assertMediaAction("播放上一曲", "previous");
        assertMediaAction("上一曲", "previous");
        assertMediaAction("切到上一曲", "previous");
    }

    @Test
    public void policyAuthorizesExplicitTrackSynonyms() {
        assertTrue(DeviceActionPolicy.isExplicitlyAuthorized(
                DeviceAction.media("next"), "播放下一曲"));
        assertTrue(DeviceActionPolicy.isExplicitlyAuthorized(
                DeviceAction.media("previous"), "请播放上一曲"));
    }

    @Test
    public void navigationExtractsExplicitDestination() {
        DeviceAction action = LocalIntentParser.parse("用百度地图导航到西二旗地铁");
        assertNotNull(action);
        assertEquals(DeviceAction.Type.NAVIGATE_TO_PLACE, action.type);
        assertEquals("西二旗地铁", action.target);
        assertTrue(DeviceActionPolicy.isExplicitlyAuthorized(
                action, "用百度地图导航到西二旗地铁"));
    }

    private void assertMediaAction(String text, String target) {
        DeviceAction action = LocalIntentParser.parse(text);
        assertNotNull(action);
        assertEquals(DeviceAction.Type.MEDIA_CONTROL, action.type);
        assertEquals(target, action.target);
    }
}
