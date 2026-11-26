package app.service;

import app.ai.*;
import app.chat.model.AssistantReply;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTest {
    static final String JSON="{\"displayText\":\"已接收这条合成测试输入。\",\"speechText\":\"已接收测试输入。\",\"dominantEmotion\":\"NEUTRAL\"}";

    static final class Outcome implements ChatStreamListener {
        final CompletableFuture<AssistantReply> result=new CompletableFuture<>();
        final AtomicInteger completed=new AtomicInteger();
        final AtomicInteger errors=new AtomicInteger();
        final StringBuilder tokens=new StringBuilder();
        @Override public void onToken(String token){ tokens.append(token); }
        @Override public void onComplete(AssistantReply reply){ completed.incrementAndGet(); result.complete(reply); }
        @Override public void onError(Throwable error){ errors.incrementAndGet(); result.completeExceptionally(error); }
        AssistantReply success() throws Exception { return result.get(5,TimeUnit.SECONDS); }
        Throwable failure() throws Exception {
            try{
                success();
                return fail("Expected a failed request");
            }catch(ExecutionException failure){
                return failure.getCause();
            }catch(CancellationException cancelled){
                return cancelled;
            }
        }
    }

    static final class ManualClient implements AiModelClient {
        record Call(AiChatRequest request,AiStreamListener listener,AtomicBoolean cancelled) {
            void complete(){ listener.onToken(JSON); listener.onComplete(); }
        }
        final BlockingQueue<Call> calls=new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed=new CompletableFuture<>();
        @Override public void streamChat(AiChatRequest request,AiStreamListener listener){ streamChatCancellable(request,listener); }
        @Override public AiStreamCall streamChatCancellable(AiChatRequest request,AiStreamListener listener){
            AtomicBoolean cancelled=new AtomicBoolean();
            calls.add(new Call(request,listener,cancelled));
            return () -> cancelled.set(true);
        }
        Call take() throws Exception {
            Call call=calls.poll(5,TimeUnit.SECONDS);
            assertNotNull(call);
            return call;
        }
        @Override public void close(){ closed.complete(null); }
    }

    @Test
    void actualRequestUsesTheReviewedCardSeparateContractAndNoInventedGreeting() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var outcome=new Outcome();
            service.streamReply("这是合成的首条输入。",outcome);
            var request=client.take().request();
            String card=Files.readString(Path.of("docs/character/closure-role-card.zh-CN.txt")).strip();
            String bundled=new String(ChatService.class.getResourceAsStream("/prompts/closure-role-card.zh-CN.txt").readAllBytes(),StandardCharsets.UTF_8).strip();
            assertEquals(card,bundled);
            assertEquals(List.of(AiChatRole.SYSTEM,AiChatRole.USER),request.getMessages().stream().map(AiChatMessage::getRole).toList());
            String system=request.getMessages().getFirst().getContent();
            try(var resource=ChatService.class.getResourceAsStream("/prompts/closure-system-prompt.txt")){
                assertNotNull(resource);
                String prompt=new String(resource.readAllBytes(),StandardCharsets.UTF_8).strip();
                String shipped=card+"\n\n"+prompt;
                assertTrue(system.startsWith(shipped));
                assertTrue(system.substring(shipped.length()).stripLeading().startsWith("当前会话能力："));
            }
            assertTrue(system.contains("当前日期"));
            assertTrue(system.contains("重启后清空"));
            assertEquals("这是合成的首条输入。",request.getMessages().getLast().getContent());
        }
    }

    @Test
    void queuedRequestIsBuiltAfterCommitAndContainsTheVisibleReplyOnlyOnce() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var a=new Outcome(); var b=new Outcome();
            service.streamReply("备份文件叫 kite.json。",a);
            var first=client.take();
            service.streamReply("刚才选了什么名字？",b);
            assertNull(client.calls.poll(100,TimeUnit.MILLISECONDS));
            first.complete();
            a.success();
            var second=client.take();
            var messages=second.request().getMessages();
            assertEquals(List.of(AiChatRole.SYSTEM,AiChatRole.USER,AiChatRole.ASSISTANT,AiChatRole.USER),messages.stream().map(AiChatMessage::getRole).toList());
            assertEquals("备份文件叫 kite.json。",messages.get(1).getContent());
            assertEquals("已接收这条合成测试输入。",messages.get(2).getContent());
            assertFalse(messages.get(2).getContent().contains("speechText"));
            assertFalse(messages.get(2).getContent().contains("dominantEmotion"));
            assertEquals("刚才选了什么名字？",messages.get(3).getContent());
            second.complete();
            b.success();
            assertEquals(2,service.completedTurns().size());
        }
    }

    @Test
    void transportFailureDoesNotCommitPartialMessagesAndRetryCommitsOnlySuccess() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var failed=new Outcome();
            service.streamReply("合成输入。",failed);
            var first=client.take();
            first.listener().onToken("{\"displayText\":\"只有半截");
            first.listener().onError(new IllegalStateException("合成断流"));
            assertInstanceOf(IllegalStateException.class,failed.failure());
            assertTrue(service.completedTurns().isEmpty());
            var retry=new Outcome();
            service.streamReply("合成输入。",retry);
            var next=client.take();
            assertEquals(2,next.request().getMessages().size());
            next.complete();
            retry.success();
            assertEquals(1,service.completedTurns().size());
            assertEquals(0,failed.completed.get());
        }
    }

    @Test
    void completionWithIncompleteJsonIsAnErrorAndNotHistory() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var failed=new Outcome();
            service.streamReply("合成输入。",failed);
            var call=client.take();
            call.listener().onToken(JSON.substring(0,JSON.length()-1));
            call.listener().onComplete();
            assertInstanceOf(IllegalArgumentException.class,failed.failure());
            assertTrue(service.completedTurns().isEmpty());
        }
    }

    @Test
    void cancellationStopsTransportAndDiscardsAllLateEvents() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var a=new Outcome(); var b=new Outcome();
            var request=service.streamReply("第一条待取消。",a);
            var old=client.take();
            var queued=service.streamReply("排队后也取消。",b);
            request.cancel();
            queued.cancel();
            assertInstanceOf(CancellationException.class,a.failure());
            assertInstanceOf(CancellationException.class,b.failure());
            assertTrue(old.cancelled().get());
            var c=new Outcome();
            service.streamReply("全新输入。",c);
            var fresh=client.take();
            assertEquals("全新输入。",fresh.request().getMessages().getLast().getContent());
            assertEquals(2,fresh.request().getMessages().size());
            old.listener().onToken(JSON);
            old.listener().onComplete();
            old.listener().onError(new IllegalStateException("迟到的错误"));
            fresh.complete();
            c.success();
            assertEquals(1,service.completedTurns().size());
            assertEquals("全新输入。",service.completedTurns().getFirst().userText());
            assertEquals(0,a.completed.get());
            assertEquals(1,a.errors.get());
            assertEquals("",a.tokens.toString());
        }
    }

    @Test
    void explicitNewConversationClearsHistoryAndCancelsPendingCalls() throws Exception {
        var client=new ManualClient();
        try(var service=new ChatService(client)){
            var done=new Outcome();
            service.streamReply("已完成的旧轮次。",done);
            client.take().complete(); done.success();
            var pending=new Outcome();
            service.streamReply("未完成的旧轮次。",pending);
            var old=client.take();
            service.newConversation().get(5,TimeUnit.SECONDS);
            assertInstanceOf(CancellationException.class,pending.failure());
            assertTrue(service.completedTurns().isEmpty());
            old.complete();
            var fresh=new Outcome();
            service.streamReply("新会话第一条。",fresh);
            var next=client.take();
            assertEquals(2,next.request().getMessages().size());
            next.complete(); fresh.success();
            assertEquals(1,service.completedTurns().size());
        }
    }

    @Test
    void closeCancelsActiveAndQueuedRequestsAndRejectsFurtherInput() throws Exception {
        var client=new ManualClient();
        var service=new ChatService(client);
        var a=new Outcome(); var b=new Outcome();
        service.streamReply("活动请求。",a);
        var call=client.take();
        service.streamReply("排队请求。",b);
        service.close();
        assertInstanceOf(CancellationException.class,a.failure());
        assertInstanceOf(CancellationException.class,b.failure());
        client.closed.get(5,TimeUnit.SECONDS);
        assertTrue(call.cancelled().get());
        var rejected=new Outcome();
        service.streamReply("关闭之后。",rejected);
        assertInstanceOf(IllegalStateException.class,rejected.failure());
        assertThrows(ExecutionException.class,()->service.newConversation().get(5,TimeUnit.SECONDS));
    }

    @Test
    void synchronousFakeCallbacksStillProduceOrderedBoundedContext() throws Exception {
        var requests=new CopyOnWriteArrayList<AiChatRequest>();
        AiModelClient immediate=(request,listener)->{
            requests.add(request);
            listener.onToken(JSON);
            listener.onComplete();
            listener.onComplete(); // A duplicate terminal callback cannot commit twice.
        };
        try(var service=new ChatService(immediate)){
            for(int i=0;i<20;i++){
                var result=new Outcome();
                service.streamReply("合成轮次"+i,result);
                result.success();
            }
            assertEquals(20,service.completedTurns().size());
            assertEquals(26,requests.getLast().getMessages().size());
            assertEquals("合成轮次7",requests.getLast().getMessages().get(1).getContent());
        }
    }

    @Test
    void unavailableClientNeverPretendsToRememberAnOfflineReply() throws Exception {
        try(var service=new ChatService(null)){
            var result=new Outcome();
            service.streamReply("合成输入。",result);
            assertInstanceOf(IllegalStateException.class,result.failure());
            assertTrue(service.completedTurns().isEmpty());
        }
    }
}
