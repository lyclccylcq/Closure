package app.service;

import app.ai.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ChatServiceReasoningTest {
    @Test
    void routesActualRequestsWithoutAnExtraClassificationCall() throws Exception {
        List<AiChatRequest> requests=new CopyOnWriteArrayList<>();
        AiModelClient client=(request,listener)->{
            requests.add(request);
            listener.onToken(ChatServiceTest.JSON);
            listener.onComplete();
        };
        try(var service=new ChatService(client)){
            for(String text:new String[]{"今天的 Python 项目写完了，终于可以休息", "帮我检查 Java 的空指针报错", "早上好，可露"}){
                var result=new ChatServiceTest.Outcome();
                service.streamReply(text,result);
                result.success();
            }
            assertEquals(3,requests.size());
            assertEquals("low",requests.get(0).getReasoningEffort());
            assertNull(requests.get(1).getReasoningEffort());
            assertEquals("low",requests.get(2).getReasoningEffort());
            assertTrue(requests.getFirst().getMessages().getFirst().getContent().contains("可露希尔"));
        }
    }

    @Test
    void cancelledTechnicalAttemptDoesNotChangeTheNextFollowupBudget() throws Exception {
        var client=new ChatServiceTest.ManualClient();
        try(var service=new ChatService(client)){
            var cancelled=new ChatServiceTest.Outcome();
            var handle=service.streamReply("帮我分析 Java 死锁。",cancelled);
            client.take();
            handle.cancel();
            cancelled.failure();
            var followup=new ChatServiceTest.Outcome();
            service.streamReply("继续。",followup);
            var next=client.take();
            assertEquals("low",next.request().getReasoningEffort());
            next.complete(); followup.success();
        }
    }
}
