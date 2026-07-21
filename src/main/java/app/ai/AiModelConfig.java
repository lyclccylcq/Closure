package app.ai;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * AI 模型连接配置。
 * 读取顺序：
 * 1. 优先读取环境变量，适合部署或临时调试；
 * 2. 环境变量没有设置时，再读取 config/local-ai.properties；
 * 3. endpoint 仍然没有设置时，使用 OpenAI-compatible 默认地址。
 *
 * 真正包含 API Key 的 local-ai.properties 已加入 .gitignore，不要提交到仓库。
 */
public final class AiModelConfig {
    public static final String ENV_ENDPOINT="CLOSURE_AI_ENDPOINT";
    public static final String ENV_API_KEY="CLOSURE_AI_API_KEY";
    public static final String ENV_MODEL="CLOSURE_AI_MODEL";

    private static final Path LOCAL_CONFIG_PATH=Path.of("config","local-ai.properties");
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
     * 从环境变量和本地私有配置文件创建配置。
     *
     * 配置文件路径：config/local-ai.properties
     * 文件格式示例：
     * CLOSURE_AI_API_KEY=你的 key
     * CLOSURE_AI_MODEL=gpt-4.1-mini
     * CLOSURE_AI_ENDPOINT=https://api.openai.com/v1/chat/completions
     */
    public static AiModelConfig fromEnvironment(){
        Properties localProperties=loadLocalProperties();

        String endpointText=configValueOrDefault(ENV_ENDPOINT,localProperties,DEFAULT_ENDPOINT);
        String apiKey=configValue(ENV_API_KEY,localProperties);
        String model=configValue(ENV_MODEL,localProperties);
        URI endpoint=URI.create(endpointText);

        if(isOpenAiEndpoint(endpoint) && (apiKey==null || apiKey.isBlank())){
            throw new AiModelException(
                    "连接 OpenAI endpoint 时需要配置 "
                            + ENV_API_KEY
                            + "，可以写在环境变量或 "
                            + LOCAL_CONFIG_PATH
            );
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

    private static Properties loadLocalProperties(){
        Properties properties=new Properties();
        if(!Files.exists(LOCAL_CONFIG_PATH)){
            return properties;
        }

        try(var reader=Files.newBufferedReader(LOCAL_CONFIG_PATH, StandardCharsets.UTF_8)){
            properties.load(reader);
            return properties;
        }catch(IOException exception){
            throw new AiModelException("读取本地 AI 配置失败：" + LOCAL_CONFIG_PATH,exception);
        }
    }

    private static String configValue(String name,Properties localProperties){
        String environmentValue=System.getenv(name);
        if(environmentValue!=null && !environmentValue.isBlank()){
            return environmentValue.trim();
        }

        String localValue=localProperties.getProperty(name);
        if(localValue!=null && !localValue.isBlank()){
            return localValue.trim();
        }

        return null;
    }

    private static String configValueOrDefault(String name,Properties localProperties,String defaultValue){
        String value=configValue(name,localProperties);
        if(value==null || value.isBlank()){
            return defaultValue;
        }
        return value;
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
