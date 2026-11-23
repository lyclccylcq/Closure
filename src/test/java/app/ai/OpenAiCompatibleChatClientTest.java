package app.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleChatClientTest {
    private static final String REPLY="{\"displayText\":\"合成回复。\",\"speechText\":\"合成回复。\",\"dominantEmotion\":\"NEUTRAL\"}";
    private static final String STOP="data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    private static final String DONE="data: [DONE]\n\n";

    private static String token(String value){
        return "data: {\"choices\":[{\"delta\":{\"content\":\""+AiJson.escape(value)+"\"},\"finish_reason\":null}]}\n\n";
    }

    @FunctionalInterface interface ExchangeAction { void run(HttpExchange exchange) throws Exception; }

    static final class Server implements AutoCloseable {
        final HttpServer http;
        final ExecutorService workers=Executors.newCachedThreadPool(r -> {
            Thread t=new Thread(r,"synthetic-sse-test"); t.setDaemon(true); return t;
        });
        final BlockingQueue<String> requests=new LinkedBlockingQueue<>();
        Server(ExchangeAction action) throws Exception {
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.setExecutor(workers);
            http.createContext("/",exchange -> {
                try{
                    requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    action.run(exchange);
                }catch(Exception ignored){
                    exchange.close();
                }
            });
            http.start();
        }
        URI endpoint(){ return URI.create("http://127.0.0.1:"+http.getAddress().getPort()+"/chat/completions"); }
        AiModelConfig config(String model,String effort){
            return new AiModelConfig(endpoint(),"synthetic-test-key",model,Duration.ofSeconds(3),Duration.ofSeconds(5),effort);
        }
        String request() throws Exception {
            String body=requests.poll(5,TimeUnit.SECONDS); assertNotNull(body); return body;
        }
        @Override public void close(){ http.stop(0); workers.shutdownNow(); }
    }

    static void respond(HttpExchange exchange,String stream) throws Exception {
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");
        exchange.sendResponseHeaders(200,0);
        try(var output=exchange.getResponseBody()){
            output.write(stream.getBytes(StandardCharsets.UTF_8)); output.flush();
        }
    }

    static final class Observer implements AiStreamListener {
        final CompletableFuture<String> result=new CompletableFuture<>();
        final CompletableFuture<Void> firstToken=new CompletableFuture<>();
        final StringBuilder text=new StringBuilder();
        final AtomicInteger completions=new AtomicInteger();
        final AtomicInteger errors=new AtomicInteger();
        @Override public void onToken(String value){ text.append(value); firstToken.complete(null); }
        @Override public void onComplete(){ completions.incrementAndGet(); result.complete(text.toString()); }
        @Override public void onError(Throwable error){ errors.incrementAndGet(); result.completeExceptionally(error); }
        String success() throws Exception { return result.get(5,TimeUnit.SECONDS); }
        Throwable failure() throws Exception {
            try{ success(); return fail("Expected failure"); }
            catch(ExecutionException failure){ return failure.getCause(); }
        }
    }

    @Test
    void preservesSolReasoningAndOrderedMessagesWithoutUnsupportedSampling() throws Exception {
        try(var server=new Server(e -> respond(e,token(REPLY)+STOP+DONE));
            var client=new OpenAiCompatibleChatClient(server.config("gpt-6.1-sol",null))){
            var observer=new Observer();
            var request=new AiChatRequest(List.of(AiChatMessage.system("合成角色卡"),
                    AiChatMessage.user("前轮"),AiChatMessage.assistant(REPLY),AiChatMessage.user("继续")),null,0.8)
                    .withReasoningEffort("low");
            client.streamChat(request,observer);
            assertEquals(REPLY,observer.success());
            String body=server.request();
            assertTrue(body.contains("\"model\":\"gpt-6.1-sol\""));
            assertTrue(body.contains("\"reasoning_effort\":\"low\""));
            assertFalse(body.contains("\"temperature\""));
            assertEquals(1,body.split("\"role\":\"assistant\"",-1).length-1);
            assertTrue(body.indexOf("前轮")<body.indexOf("继续"));
        }
    }

    @Test
    void keepsSamplingForNonReasoningAdaptersAndConfiguredDefaultForSol() throws Exception {
        try(var server=new Server(e -> respond(e,token(REPLY)+DONE))){
            try(var client=new OpenAiCompatibleChatClient(server.config("fixture-model",null))){
                var observer=new Observer();
                client.streamChat(AiChatRequest.singleTurn("合成","输入").withTemperature(0.8).withReasoningEffort("low"),observer);
                observer.success();
                String body=server.request();
                assertTrue(body.contains("\"temperature\":0.8"));
                assertFalse(body.contains("reasoning_effort"));
            }
            try(var client=new OpenAiCompatibleChatClient(server.config("gpt-6.1-sol","high"))){
                var observer=new Observer();
                client.streamChat(AiChatRequest.singleTurn("合成","输入").withTemperature(0.8),observer);
                observer.success();
                String body=server.request();
                assertTrue(body.contains("\"reasoning_effort\":\"high\""));
                assertFalse(body.contains("temperature"));
            }
        }
    }

    @Test
    void invalidSolEffortFailsBeforeAnyHttpCall() throws Exception {
        try(var server=new Server(e -> respond(e,DONE));
            var client=new OpenAiCompatibleChatClient(server.config("gpt-6.1-sol",null))){
            var observer=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","输入").withReasoningEffort("none"),observer);
            assertInstanceOf(AiModelException.class,observer.failure());
            assertNull(server.requests.poll(100,TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void endOfStreamWithoutATerminalEventIsNotSuccess() throws Exception {
        try(var server=new Server(e -> respond(e,token(REPLY)));
            var client=new OpenAiCompatibleChatClient(server.config("fixture-model",null))){
            var observer=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","输入"),observer);
            assertInstanceOf(AiModelException.class,observer.failure());
            assertEquals(0,observer.completions.get());
        }
    }

    @Test
    void stopWithoutDoneIsAcceptedButLengthFinishIsNot() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try(var server=new Server(e -> respond(e,token(REPLY)+(calls.getAndIncrement()==0 ? STOP
                : "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"+DONE)));
            var client=new OpenAiCompatibleChatClient(server.config("fixture-model",null))){
            var complete=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","输入"),complete);
            assertEquals(REPLY,complete.success());
            var truncated=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","输入"),truncated);
            assertInstanceOf(AiModelException.class,truncated.failure());
            assertEquals(0,truncated.completions.get());
        }
    }

    @Test
    void explicitSseErrorIsReportedOnceWithoutACompletion() throws Exception {
        try(var server=new Server(e -> respond(e,"data: { \"error\": {\"message\":\"synthetic service error\"}}\n\n"+DONE));
            var client=new OpenAiCompatibleChatClient(server.config("fixture-model",null))){
            var observer=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","输入"),observer);
            assertTrue(observer.failure().getMessage().contains("synthetic service error"));
            assertEquals(0,observer.completions.get());
            assertEquals(1,observer.errors.get());
        }
    }

    @Test
    void cancellationInterruptsTheReaderAndLetsTheNextRequestRun() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        CountDownLatch releaseOld=new CountDownLatch(1);
        try(var server=new Server(e -> {
            if(calls.getAndIncrement()==0){
                e.getResponseHeaders().set("Content-Type","text/event-stream");
                e.sendResponseHeaders(200,0);
                try(var out=e.getResponseBody()){
                    out.write(token("合成的半截回复").getBytes(StandardCharsets.UTF_8)); out.flush();
                    releaseOld.await(10,TimeUnit.SECONDS);
                    out.write(DONE.getBytes(StandardCharsets.UTF_8)); out.flush();
                }
            }else respond(e,token(REPLY)+STOP+DONE);
        }); var client=new OpenAiCompatibleChatClient(server.config("fixture-model",null))){
            var old=new Observer();
            AiStreamCall call=client.streamChatCancellable(AiChatRequest.singleTurn("合成","旧输入"),old);
            old.firstToken.get(5,TimeUnit.SECONDS);
            call.cancel();
            var next=new Observer();
            client.streamChat(AiChatRequest.singleTurn("合成","新输入"),next);
            assertEquals(REPLY,next.success());
            assertEquals(0,old.completions.get());
            assertEquals(0,old.errors.get());
        }finally{
            releaseOld.countDown();
        }
    }
}
