package cloud.pcie.openaiq;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class EdgeTtsVoicesTest {
    @Test public void curatedVoicesKeepOriginalOptionsAndRouteToSupportedTransport() {
        List<EdgeTtsClient.Voice> voices = EdgeTtsClient.fallbackVoices();
        assertEquals(19, voices.size());
        assertTrue(EdgeTtsClient.requiresTranslator("zh-CN-XiaomoNeural"));
        assertTrue(EdgeTtsClient.requiresTranslator("zh-CN-XiaohanNeural"));
        assertFalse(EdgeTtsClient.requiresTranslator("zh-CN-XiaoxiaoNeural"));
        assertFalse(EdgeTtsClient.requiresTranslator("zh-CN-YunxiNeural"));
    }

    @Test public void ssmlEscapesTextAndAttributes() {
        String ssml = TranslatorTtsClient.ssml("你<&\"好", "zh-CN-XiaomoNeural", "calm", 0, 0, 0);
        assertTrue(ssml.contains("你&lt;&amp;&quot;好"));
        assertTrue(ssml.contains("style=\"calm\""));
        assertFalse(TranslatorTtsClient.ssml("你好", "zh-CN-XiaomoNeural", "bad\"style", 0, 0, 0)
                .contains("express-as"));
    }
}
