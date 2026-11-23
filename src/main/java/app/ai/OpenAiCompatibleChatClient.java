package app.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * OpenAI-compatible Chat Completions 流式客户端。
 * 只依赖 JDK 21 自带的 HttpClient，不额外引入第三方依赖。
 */
public final class OpenAiCompatibleChatClient implements AiModelClient {
    private final AiModelConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Set<Call> calls=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();

    public OpenAiCompatibleChatClient(AiModelConfig config){
        this.config=Objects.requireNonNull(config,"config");
        httpClient=HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
        executor=Executors.newSingleThreadExecutor(command -> {
            Thread thread=new Thread(command,"openai-compatible-chat-client");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void streamChat(AiChatRequest request,AiStreamListener listener){
        streamChatCancellable(request,listener);
    }

    @Override
    public AiStreamCall streamChatCancellable(AiChatRequest request,AiStreamListener listener){
        Objects.requireNonNull(request,"request");
        Objects.requireNonNull(listener,"listener");
        if(closed.get()) throw new IllegalStateException("模型客户端已关闭");
        Call call=new Call();
        calls.add(call);
        try{
            call.attach(executor.submit(() -> {
                try{
                    if(!call.cancelled.get()) sendStreamingRequest(request,new AiStreamListener(){
                        @Override public void onToken(String token){
                            if(!call.cancelled.get()) listener.onToken(token);
                        }
                        @Override public void onComplete(){
                            if(!call.cancelled.get()) listener.onComplete();
                        }
                    });
                }catch(Throwable error){
                    if(!call.cancelled.get()) listener.onError(error);
                }finally{
                    calls.remove(call);
                }
            }));
        }catch(RuntimeException failure){
            calls.remove(call);
            call.cancel();
            throw failure;
        }
        return call;
    }

    @Override
    public void close(){
        closed.set(true);
        calls.forEach(Call::cancel);
        executor.shutdownNow();
    }

    private final class Call implements AiStreamCall {
        private final AtomicBoolean cancelled=new AtomicBoolean();
        private volatile Future<?> task;

        void attach(Future<?> task){
            this.task=task;
            if(cancelled.get()) task.cancel(true);
        }

        @Override public void cancel(){
            cancelled.set(true);
            calls.remove(this);
            Future<?> running=task;
            if(running!=null) running.cancel(true);
        }
    }

    private void sendStreamingRequest(AiChatRequest request,AiStreamListener listener)
            throws IOException,InterruptedException {
        HttpRequest httpRequest=buildHttpRequest(request);
        HttpResponse<Stream<String>> response=httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofLines()
        );

        if(response.statusCode()<200 || response.statusCode()>=300){
            String responseBody=readBody(response.body());
            throw new AiModelException("模型接口返回 HTTP " + response.statusCode() + ": " + responseBody);
        }

        boolean done=false;
        String finishReason=null;
        try(Stream<String> lines=response.body()){
            var iterator=lines.iterator();
            while(iterator.hasNext()){
                String line=iterator.next();
                if(line.startsWith("data:")){
                    String data=line.substring("data:".length()).trim();
                    if("[DONE]".equals(data)){
                        done=true;
                        break;
                    }
                    String reason=AiJson.extractStringField(data,"finish_reason");
                    if(reason!=null) finishReason=reason;
                }
                handleSseLine(line,listener);
            }
        }
        if(!done && !"stop".equals(finishReason)){
            throw new AiModelException("模型流在完成标记之前中断");
        }
        if(finishReason!=null && !"stop".equals(finishReason)){
            throw new AiModelException("模型未完整结束回复：" + finishReason);
        }
        listener.onComplete();
    }

    private HttpRequest buildHttpRequest(AiChatRequest request){
        HttpRequest.Builder builder=HttpRequest.newBuilder()
                .uri(config.getEndpoint())
                .timeout(config.getRequestTimeout())
                .header("Content-Type","application/json")
                .header("Accept","text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toRequestJson(request),StandardCharsets.UTF_8));

        if(config.hasApiKey()){
            builder.header("Authorization","Bearer " + config.getApiKey());
        }

        return builder.build();
    }

    private String toRequestJson(AiChatRequest request){
        String model=request.getModel()==null ? config.getModel() : request.getModel();
        String effort=request.getReasoningEffort()==null ? config.getReasoningEffort() : request.getReasoningEffort();
        boolean reasoningModel=supportsReasoning(model);
        if(model.toLowerCase(Locale.ROOT).startsWith("gpt-6.1-sol")
                && ("none".equals(effort) || "minimal".equals(effort))){
            throw new AiModelException("GPT-6.1 Sol 的最低推理强度是 low");
        }
        StringBuilder json=new StringBuilder();
        json.append('{');
        json.append("\"model\":\"").append(AiJson.escape(model)).append("\",");
        json.append("\"stream\":true,");
        if(reasoningModel && effort!=null){
            json.append("\"reasoning_effort\":\"").append(effort).append("\",");
        }
        // Sol reasons by default. Preserve sampling for the previous mini's default none mode.
        boolean samplingEnabled=!reasoningModel || "none".equals(effort)
                || (effort==null && model.toLowerCase(Locale.ROOT).startsWith("gpt-5.4-mini"));
        if(request.getTemperature()!=null && samplingEnabled){
            json.append("\"temperature\":").append(request.getTemperature()).append(',');
        }
        json.append("\"messages\":[");

        for(int index=0;index<request.getMessages().size();index++){
            AiChatMessage message=request.getMessages().get(index);
            if(index>0){
                json.append(',');
            }
            json.append('{')
                    .append("\"role\":\"").append(message.getRole().wireValue()).append("\",")
                    .append("\"content\":\"").append(AiJson.escape(message.getContent())).append("\"")
                    .append('}');
        }

        json.append(']');
        json.append('}');
        return json.toString();
    }

    private boolean supportsReasoning(String model){
        String name=model.toLowerCase(Locale.ROOT);
        return name.startsWith("gpt-5") || name.startsWith("gpt-6")
                || name.matches("o[134](?:-.*)?");
    }

    private void handleSseLine(String line,AiStreamListener listener){
        if(line==null || line.isBlank() || !line.startsWith("data:")){
            return;
        }

        String data=line.substring("data:".length()).trim();
        if("[DONE]".equals(data)){
            return;
        }

        if(data.startsWith("{\"error\"") || data.matches("(?s)^\\{\\s*\"error\"\\s*:.*")){
            String message=AiJson.extractStringField(data,"message");
            throw new AiModelException("模型流返回错误：" + (message==null ? "unknown error" : message));
        }

        String token=AiJson.extractStringField(data,"content");
        if(token!=null && !token.isEmpty()){
            listener.onToken(token);
        }
    }

    private String readBody(Stream<String> bodyLines){
        try(Stream<String> lines=bodyLines){
            return String.join("\n",lines.toList());
        }
    }
}
