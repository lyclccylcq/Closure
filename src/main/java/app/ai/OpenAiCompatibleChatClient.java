package app.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * OpenAI-compatible Chat Completions 流式客户端。
 * 只依赖 JDK 21 自带的 HttpClient，不额外引入第三方依赖。
 */
public final class OpenAiCompatibleChatClient implements AiModelClient {
    private final AiModelConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;

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
        Objects.requireNonNull(request,"request");
        Objects.requireNonNull(listener,"listener");

        executor.submit(() -> {
            try{
                sendStreamingRequest(request,listener);
            }catch(Throwable error){
                listener.onError(error);
            }
        });
    }

    @Override
    public void close(){
        executor.shutdownNow();
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

        try(Stream<String> lines=response.body()){
            lines.forEach(line -> handleSseLine(line,listener));
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
        StringBuilder json=new StringBuilder();
        json.append('{');
        json.append("\"model\":\"").append(AiJson.escape(model)).append("\",");
        json.append("\"stream\":true,");
        if(request.getTemperature()!=null){
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

    private void handleSseLine(String line,AiStreamListener listener){
        if(line==null || line.isBlank() || !line.startsWith("data:")){
            return;
        }

        String data=line.substring("data:".length()).trim();
        if("[DONE]".equals(data)){
            return;
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
