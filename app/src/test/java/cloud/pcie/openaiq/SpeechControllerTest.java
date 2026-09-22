package cloud.pcie.openaiq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class SpeechControllerTest {
    @Test
    public void shortReplyFlushesAtEnd() {
        SpeechController.ChunkResult pending =
                SpeechController.takeSpeechChunks("你好。", false);
        assertTrue(pending.chunks.isEmpty());
        assertEquals("你好。", pending.rest);
        assertEquals("你好。",
                SpeechController.takeSpeechChunks(pending.rest, true).chunks.get(0));
    }

    @Test
    public void streamingDeltasEmitFirstSentenceWithoutWaitingForThree() {
        SpeechController.ChunkResult first = SpeechController.takeSpeechChunks(
                "我可以先解释主要原因", false);
        assertTrue(first.chunks.isEmpty());
        SpeechController.ChunkResult second = SpeechController.takeSpeechChunks(
                first.rest + "，然后提供可操作的办法。后续还有细节", false);
        assertEquals(1, second.chunks.size());
        assertTrue(second.chunks.get(0).endsWith("。"));
        assertEquals("后续还有细节", second.rest);
        assertEquals("后续还有细节",
                SpeechController.takeSpeechChunks(second.rest, true).chunks.get(0));
    }

    @Test
    public void completeResponseSplitsAndKeepsOrder() {
        String response = "第一个要点有足够的文字可以开始合成。"
                + "第二个要点也有足够的文字能够接着合成！短尾";
        SpeechController.ChunkResult result =
                SpeechController.takeSpeechChunks(response, true);
        assertEquals(3, result.chunks.size());
        assertEquals(response, String.join("", result.chunks));
        assertEquals("", result.rest);
    }

    @Test
    public void longUnpunctuatedResponseDoesNotWaitForCompletion() {
        String response = "这是一段没有标点但一直持续返回的文本内容需要按长度进行及时切分保证首段朗读能够尽快开始并在后续继续预取音频";
        SpeechController.ChunkResult result =
                SpeechController.takeSpeechChunks(response, false);
        assertFalse(result.chunks.isEmpty());
        List<String> all = new ArrayList<>(result.chunks);
        all.addAll(SpeechController.takeSpeechChunks(result.rest, true).chunks);
        assertEquals(response, String.join("", all));
        for (String chunk : all) {
            assertTrue(chunk.length() <= 48);
        }
    }

    @Test
    public void emojiAreOmittedFromSpeechButOriginalMessageStaysUntouched() {
        ChatMessage message = new ChatMessage(
                ChatMessage.ROLE_ASSISTANT, "你好👋🏽，今天☀️真好！一起去吧 👩‍💻 🇨🇳 1️⃣");
        SpeechController.ChunkResult result =
                SpeechController.takeSpeechChunks(message.content, true);
        assertEquals("你好 ，今天 真好！一起去吧",
                SpeechController.speechText(String.join("", result.chunks)));
        assertTrue(String.join("", result.chunks).contains("👋🏽"));
        assertTrue(message.content.contains("👋🏽"));
        assertTrue(message.content.contains("👩‍💻"));
    }

    @Test
    public void emojiOnlyReplyDoesNotTriggerEmptySynthesis() {
        assertEquals("", SpeechController.speechText("🙂👍🏽🇨🇳"));
        assertEquals("房间 101，金额 $20，序号 #2",
                SpeechController.speechText("房间 101，金额 $20，序号 #2"));
    }
}
