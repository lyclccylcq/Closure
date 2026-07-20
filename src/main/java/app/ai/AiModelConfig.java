package app.ai;

import java.net.URI;
import java.time.Duration;

/**
 * AI 模型连接配置。
 * 这里优先从环境变量读取，避免把 API Key 写进代码仓库。
 */
public final class AiModelConfig {
    public static final String ENV_ENDPOINT="CLOSURE_AI_ENDPOINT";
    public static final String ENV_API_KEY="CLOSURE_AI_API_KEY";
    public static final String ENV_MODEL="CLOSURE_AI_MODEL";

    private static final String DEFAULT_ENDPOINT="https://api.openai.com/v1/chat/completions";
    private static final Duration DEFAULT_CONNECT_TIMEOUT=Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT=Duration.ofSeconds(90);

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public AiModelConfig(
            URI endpoint,
            String apiKey,
            String model,
            Duration connectTimeout,
            Duration requestTimeout
    ){
        if(endpoint==null){
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        if(model==null || model.isBlank()){
            throw new IllegalArgumentException("model 不能为空，请设置环境变量 " + ENV_MODEL);
        }

        this.endpoint=endpoint;
        this.apiKey=blankToNull(apiKey);
        this.model=model.trim();
        this.connectTimeout=connectTimeout==null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        this.requestTimeout=requestTimeout==null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    /**
     * 从环境变量创建配置。
     *
     * CLOSURE_AI_ENDPOINT 可选，默认使用 OpenAI-compatible chat completions 地址。
     * CLOSURE_AI_MODEL 必填。
     * CLOSURE_AI_API_KEY 对本地模型可选；如果连接官方 OpenAI 地址则应填写。
     */
    public static AiModelConfig fromEnvironment(){
        String endpointText=envOrDefault(ENV_ENDPOINT,DEFAULT_ENDPOINT);
        String apiKey=System.getenv(ENV_API_KEY);
        String model=System.getenv(ENV_MODEL);
        URI endpoint=URI.create(endpointText);

        if(isOpenAiEndpoint(endpoint) && (apiKey==null || apiKey.isBlank())){
            throw new AiModelException("连接 OpenAI endpoint 时需要设置环境变量 " + ENV_API_KEY);
        }

        return new AiModelConfig(
                endpoint,
                apiKey,
                model,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    public URI getEndpoint(){
        return endpoint;
    }

    public String getApiKey(){
        return apiKey;
    }

    public String getModel(){
        return model;
    }

    public Duration getConnectTimeout(){
        return connectTimeout;
    }

    public Duration getRequestTimeout(){
        return requestTimeout;
    }

    public boolean hasApiKey(){
        return apiKey!=null && !apiKey.isBlank();
    }

    private static String envOrDefault(String name,String defaultValue){
        String value=System.getenv(name);
        if(value==null || value.isBlank()){
            return defaultValue;
        }
        return value.trim();
    }

    private static String blankToNull(String value){
        if(value==null || value.isBlank()){
            return null;
        }
        return value.trim();
    }

    private static boolean isOpenAiEndpoint(URI endpoint){
        String host=endpoint.getHost();
        return host!=null && host.endsWith("openai.com");
    }
}
