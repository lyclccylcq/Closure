package app.service;

import app.chat.model.AssistantReply;
import app.voice.VoiceProfileRegistry;
import app.voice.FishAudioConfig;
import app.voice.VoiceSynthesisClient;
import app.voice.model.TtsRequest;
import app.voice.model.TtsAudioResult;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSpeechControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void speechSessionsOnlyStopTheirOwnCurrentReply() throws Exception {
        FakeSpeechService service = new FakeSpeechService();
        var controller = controller(service, true, new ArrayList<>());
        var first = controller.createSession();
        var second = controller.createSession();
        var reply = new AssistantReply("正文。", "朗读。", SpeechEmotion.NEUTRAL);
        first.speak(reply);
        second.speak(reply);
        int stops = service.stopCount;
        first.stop();
        assertEquals(stops, service.stopCount);
        second.stop();
        assertEquals(stops + 1, service.stopCount);
        controller.speakAssistantReply(reply);
        first.stop();
        second.stop();
        assertEquals(stops + 2, service.stopCount);
        first.speak(reply);
        service.futures.getFirst().complete(null);
        first.stop();
        assertEquals(stops + 4, service.stopCount);
    }

    @Test
    void fishFactoryPreparesWithoutSynthesisAndCancelsTheFirstWholeReply() {
        Properties settings = new Properties();
        settings.setProperty("fish.apiKey", "synthetic-test-key");
        FishAudioConfig config = FishAudioConfig.fromProperties(settings);
        List<TtsRequest> requests = new ArrayList<>();
        CompletableFuture<Void> download = new CompletableFuture<>();
        VoiceSynthesisClient client = new VoiceSynthesisClient() {
            public CompletableFuture<Void> checkAvailable() {
                throw new AssertionError("Hosted readiness must not call the provider");
            }
            public CompletableFuture<Void> initializeModels() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) {
                throw new AssertionError("Expected cancellable whole-WAV delivery");
            }
            public CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> sink) {
                requests.add(request);
                return download;
            }
        };
        VoiceAudioPlayer silentPlayer = new VoiceAudioPlayer() {
            public void play(Path path) { throw new AssertionError("No audio delivered"); }
            public CompletableFuture<Void> playAndWait(Path path) {
                throw new AssertionError("No audio delivered");
            }
            public void stop() { }
        };
        var controller = ChatSpeechController.fromFishConfig(config, client, silentPlayer, message -> { });
        controller.readyForSpeechFuture().join();
        assertTrue(requests.isEmpty());
        controller.speakStartupGreetingIfIdle("自动问候。").join();
        controller.speakBackgroundText("自动闲聊。");
        assertTrue(requests.isEmpty());

        controller.speakAssistantReply(new AssistantReply("找到了？这回算它输啦。",
                "找到了？这回算它输啦。", SpeechEmotion.TEASING));
        assertEquals(1, requests.size());
        assertEquals("找到了？这回算它输啦。", requests.getFirst().getText());
        assertEquals(config.getVoiceProfile().getReferenceAudioPath(),
                requests.getFirst().getVoiceProfile().getReferenceAudioPath());
        controller.stop();
        assertTrue(download.isCancelled());
    }

    @Test
    void fixedTouchUsesExplicitReuseRouteWhileOrdinaryChatAndTextStayUncached() throws Exception {
        FakeSpeechService service = new FakeSpeechService();
        VoiceProfile neutral = profile("neutral", "neutral.wav");
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL, neutral), message -> { });
        var controller = new ChatSpeechController(service, profiles, true, message -> { }, false, true);
        controller.speakFixedPhraseAsync("**别戳啦**，我在呢。");
        controller.speakAssistantReplyAsync(new AssistantReply("别戳啦，我在呢。", "别戳啦，我在呢。", SpeechEmotion.NEUTRAL));
        controller.speakFullTextAsync("别戳啦，我在呢。");
        controller.speakBackgroundText("后台闲聊。");
        assertEquals(List.of("别戳啦，我在呢。"), service.fixedTexts);
        assertEquals(List.of("别戳啦，我在呢。", "别戳啦，我在呢。"), service.spokenTexts);
        controller.setAutoSpeakEnabled(false);
        controller.speakFixedPhraseAsync("静音。").join();
        assertEquals(1, service.fixedTexts.size());
    }

    @Test
    void hostedPreviewKeepsFixedVoiceAndSkipsAutomaticBackgroundSpeech() {
        FakeSpeechService service = new FakeSpeechService();
        VoiceProfile hosted = new VoiceProfile("fish-closure", tempDir.resolve("hosted-reference"),
                "Hosted reference", "zh");
        VoiceProfile question = new VoiceProfile("other-voice", tempDir.resolve("other-reference"),
                "Other reference", "zh");
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL, hosted,
                SpeechEmotion.QUESTION, question), message -> { }, true);
        var controller = new ChatSpeechController(service, profiles, true, message -> { }, false, true);

        assertTrue(controller.usesHostedVoice());
        assertFalse(controller.usesLocalQwen());
        controller.speakStartupGreetingIfIdle("自动启动问候。").join();
        controller.speakBackgroundText("自动闲聊或提醒。");
        assertTrue(service.spokenTexts.isEmpty());

        controller.speakAssistantReply(new AssistantReply("找到啦？", "找到啦？", SpeechEmotion.QUESTION));
        controller.speakFullText("再看一眼。");
        assertEquals(List.of("找到啦？", "再看一眼。"), service.spokenTexts);
        assertEquals(List.of(hosted, hosted), service.spokenProfiles);
    }

    @Test
    void hostedQuotaFailureKeepsUsefulProviderMessageWithoutLegacyBackendLabel() {
        FakeSpeechService service = new FakeSpeechService();
        service.failureMessage = "Fish API 额度不足（HTTP 402）";
        VoiceProfile hosted = new VoiceProfile("fish-closure", tempDir.resolve("hosted-reference"),
                "Hosted reference", "zh");
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL, hosted), message -> { }, true);
        List<String> warnings = new ArrayList<>();
        var controller = new ChatSpeechController(service, profiles, true, warnings::add, false, true);
        controller.speakFullText("测试。");
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("额度不足"));
        assertFalse(warnings.getFirst().contains("GPT-SoVITS"));
    }

    @Test
    void localQwenKeepsOneSpeakerAnchorAcrossQuestionAndTeasingReplies() throws Exception {
        FakeSpeechService service=new FakeSpeechService();
        VoiceProfile neutral=profile("neutral","neutral.wav");
        VoiceProfile question=profile("question","question.wav");
        VoiceProfile teasing=profile("teasing","teasing.wav");
        var profiles=new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,neutral,
                SpeechEmotion.QUESTION,question,SpeechEmotion.TEASING,teasing),m -> { });
        var controller=new ChatSpeechController(service,profiles,true,m -> { },true);
        controller.speakAssistantReply(new AssistantReply("完成了。","完成了。",SpeechEmotion.NEUTRAL));
        controller.speakAssistantReply(new AssistantReply("这次选哪个？","这次选哪个？",SpeechEmotion.QUESTION));
        controller.speakAssistantReply(new AssistantReply("不是吧，又找到一个小机关？","不是吧，又找到一个小机关？",SpeechEmotion.TEASING));
        assertEquals(List.of("neutral","neutral","neutral"),service.spokenProfiles.stream().map(VoiceProfile::getId).toList());
        assertEquals(List.of("完成了。","这次选哪个？","不是吧，又找到一个小机关？"),service.spokenTexts);
    }

    @Test
    void speaksSanitizedReply() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakText("**博士**，资料在这里：https://example.com");

        assertEquals(List.of("博士，资料在这里。"), speechService.spokenTexts);
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void speaksAssistantReplySpeechTextOnlyOnce() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        AssistantReply reply = new AssistantReply(
                "第一句。第二句？第三句！",
                "第一句。第二句？第三句！",
                SpeechEmotion.NEUTRAL
        );

        controller.speakAssistantReply(reply);

        assertEquals(List.of("第一句。第二句？第三句！"), speechService.spokenTexts);
        assertEquals(1, speechService.spokenProfiles.size());
    }

    @Test
    void usesDominantEmotionProfile() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply(new AssistantReply("真的？", "真的？", SpeechEmotion.QUESTION));

        assertEquals("question", speechService.spokenProfiles.getFirst().getId());
    }

    @Test
    void directBubbleQuestionUsesQuestionVoiceWithoutNeedingModelEmotionMetadata() throws Exception {
        FakeSpeechService speechService=new FakeSpeechService();
        ChatSpeechController controller=controller(speechService,true,new ArrayList<>());
        controller.speakFullText("博士，现在要不要先休息一下？");
        assertEquals("question",speechService.spokenProfiles.getFirst().getId());
        controller.speakFullText("我正在假装很忙，配合一下。");
        assertEquals("neutral",speechService.spokenProfiles.getLast().getId());
    }

    @Test
    void skipsEmptySanitizedReply() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply("""
                ```java
                System.out.println("skip");
                ```
                https://example.com
                """);

        assertTrue(speechService.spokenTexts.isEmpty());
        assertEquals(0, speechService.stopCount);
    }

    @Test
    void disabledAutoSpeakDoesNotCallTtsAndStopsCurrentSpeechWhenTurnedOff() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.setAutoSpeakEnabled(false);
        controller.speakAssistantReply("博士，测试。");

        assertTrue(speechService.spokenTexts.isEmpty());
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void newReplyStopsPreviousSpeechWithoutBlockingNewReply() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply("第一条。");
        controller.speakAssistantReply("第二条。");

        assertEquals(List.of("第一条。", "第二条。"), speechService.spokenTexts);
        assertEquals(2, speechService.stopCount);
    }

    @Test
    void delayedStartupGreetingDoesNotStopASpeechRequestOrReplay() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        controller.speakFullText("博士，日常朗读。");
        controller.speakStartupGreetingIfIdle("后台启动问候。").join();

        assertEquals(List.of("博士，日常朗读。"), speechService.spokenTexts);
        assertEquals(1, speechService.stopCount);

        FakeSpeechService idleService = new FakeSpeechService();
        ChatSpeechController idleController = controller(idleService, true, new ArrayList<>());
        idleController.speakStartupGreetingIfIdle("启动问候。");
        idleController.speakStartupGreetingIfIdle("重复问候。").join();
        assertEquals(List.of("启动问候。"), idleService.spokenTexts);
    }

    @Test
    void repeatedSameFailureWarningsAreCollapsed() throws Exception {
        List<String> warnings = new ArrayList<>();
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "语音合成失败，HTTP 400: bad request";
        ChatSpeechController controller = controller(speechService, true, warnings);

        controller.speakAssistantReply("第一条。");
        controller.speakAssistantReply("第二条。");

        assertEquals(1, warnings.size());
    }

    @Test
    void ttsFailureDoesNotEscapeChatFlow() throws Exception {
        List<String> warnings = new ArrayList<>();
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "Connection refused";
        ChatSpeechController controller = controller(speechService, true, warnings);

        assertDoesNotThrow(() -> controller.speakAssistantReply(new AssistantReply(
                "文字已经显示。",
                "文字已经显示。",
                SpeechEmotion.NEUTRAL
        )));
    }

    @Test
    void speakTextAsyncReturnsFailedFutureWhenTtsFails() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "Connection refused";
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        CompletableFuture<Void> speechFuture = controller.speakTextAsync("博士，启动问候测试。");

        assertTrue(speechFuture.isCompletedExceptionally());
    }

    @Test
    void fullTextSpeechKeepsDisplayedBubbleTextIntact() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        String bubbleText = "天气有点冷，出门记得加一件外套。".repeat(8) + "最后这句也应该被朗读。";

        controller.speakText(bubbleText);
        controller.speakFullText(bubbleText);

        assertEquals(2, speechService.spokenTexts.size());
        assertTrue(speechService.spokenTexts.getFirst()
                .codePointCount(0, speechService.spokenTexts.getFirst().length())
                <= SpeechTextSanitizer.MAX_SEGMENT_CODE_POINTS);
        assertFalse(speechService.spokenTexts.getFirst().contains("最后这句也应该被朗读。"));
        assertEquals(SpeechTextSanitizer.sanitizeFull(bubbleText), speechService.spokenTexts.getLast());
        assertTrue(speechService.spokenTexts.getLast().contains("最后这句也应该被朗读。"));
    }

    @Test
    void readyFutureCompletesAfterWarmup() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        CompletableFuture<Void> warmUpFuture = new CompletableFuture<>();
        speechService.warmUpFuture = warmUpFuture;

        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        assertEquals(1, speechService.warmUpCount);
        assertFalse(controller.readyForSpeechFuture().isDone());

        warmUpFuture.complete(null);

        assertTrue(controller.readyForSpeechFuture().isDone());
    }

    @Test
    void notifiesSpeechLifecycleAroundActiveSpeech() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        List<String> events = new ArrayList<>();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        controller.setSpeechLifecycleListener(listener(events));

        controller.speakAssistantReply("第一条。");

        assertEquals(List.of("start"), events);

        speechService.futures.getFirst().complete(null);

        assertEquals(List.of("start", "finish"), events);
    }

    @Test
    void stopFinishesActiveSpeechLifecycle() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        List<String> events = new ArrayList<>();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        controller.setSpeechLifecycleListener(listener(events));

        controller.speakAssistantReply("第一条。");
        controller.stop();

        assertEquals(List.of("start", "finish"), events);
    }

    private ChatSpeechController controller(
            FakeSpeechService speechService,
            boolean autoSpeakEnabled,
            List<String> warnings
    ) throws IOException {
        return new ChatSpeechController(
                speechService,
                registry(),
                autoSpeakEnabled,
                warnings::add
        );
    }

    private VoiceProfileRegistry registry() throws IOException {
        VoiceProfile neutral = profile("neutral", "neutral.wav");
        VoiceProfile question = profile("question", "question.wav");
        return new VoiceProfileRegistry(
                Map.of(
                        SpeechEmotion.NEUTRAL, neutral,
                        SpeechEmotion.QUESTION, question
                ),
                message -> {
                }
        );
    }

    private VoiceProfile profile(String id, String fileName) throws IOException {
        Path audioPath = tempDir.resolve(fileName);
        Files.write(audioPath, "RIFF".getBytes(StandardCharsets.US_ASCII));
        return new VoiceProfile(id, audioPath, "参考文本。", "zh");
    }

    private SpeechLifecycleListener listener(List<String> events) {
        return new SpeechLifecycleListener() {
            @Override
            public void onSpeechStarted() {
                events.add("start");
            }

            @Override
            public void onSpeechFinished() {
                events.add("finish");
            }
        };
    }

    private static final class FakeSpeechService implements AssistantSpeechService {
        private final List<String> spokenTexts = new ArrayList<>();
        private final List<String> fixedTexts = new ArrayList<>();
        private final List<VoiceProfile> spokenProfiles = new ArrayList<>();
        private final List<CompletableFuture<Void>> futures = new ArrayList<>();
        private CompletableFuture<Void> warmUpFuture = CompletableFuture.completedFuture(null);
        private int warmUpCount;
        private int stopCount;
        private String failureMessage;

        @Override
        public CompletableFuture<Void> warmUp(VoiceProfile profile) {
            warmUpCount++;
            return warmUpFuture;
        }

        @Override
        public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
            spokenTexts.add(text);
            spokenProfiles.add(profile);
            return nextFuture();
        }

        @Override
        public CompletableFuture<Void> speakFixedPhrase(String text, VoiceProfile profile) {
            fixedTexts.add(text);
            return nextFuture();
        }

        private CompletableFuture<Void> nextFuture() {
            if (failureMessage != null) {
                return CompletableFuture.failedFuture(new RuntimeException(failureMessage));
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }
}
