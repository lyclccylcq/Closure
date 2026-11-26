package app.service;

import app.ai.AiChatMessage;
import app.ai.AiChatRequest;
import app.ai.AiChatRole;
import app.ai.AiModelClient;
import app.ai.AiModelConfig;
import app.ai.AiStreamCall;
import app.ai.AiStreamListener;
import app.ai.OpenAiCompatibleChatClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one conversation independently of chat windows and provider-side session IDs. */
public class ChatService implements AutoCloseable {
    public static final String LOCAL_DIALOGUE_MARKER="CLOSURE_DIALOGUE_V2";
    private static final int MAX_RESPONSE_CHARS=256_000;
    private static final String RUNTIME_CAPABILITIES="""

            当前会话能力：
            - 可以使用本次提供的近期完整对话；较早内容可能已省略，不要编造缺失的前文。
            - 对话只在本次应用运行期间保留，重启后清空；当前没有长期记忆、保存偏好或删除磁盘数据的工具。
            - 当前聊天没有文件操作、执行命令、联网查询或其他行动工具；不要声称已完成这些操作。
            """;

    private final AiModelClient aiClient;
    private final String systemPrompt;
    private final PromptEnvironmentContext environmentContext=new PromptEnvironmentContext();
    private final AssistantReplyParser replyParser=new AssistantReplyParser();
    private final ConversationHistory history=new ConversationHistory();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong nextId=new AtomicLong();
    private final Deque<Request> queue=new ArrayDeque<>();
    private final ExecutorService events=Executors.newSingleThreadExecutor(command -> {
        Thread thread=new Thread(command,"chat-conversation");
        thread.setDaemon(true);
        return thread;
    });
    // Only the events thread mutates the queue, active request and reply buffers.
    private Request active;

    public ChatService(){
        this(createAiClient());
    }

    public ChatService(AiModelClient aiClient){
        this.aiClient=aiClient;
        systemPrompt=loadPrompt("/prompts/closure-role-card.zh-CN.txt") + "\n\n"
                + loadPrompt("/prompts/closure-system-prompt.txt");
        System.out.println(LOCAL_DIALOGUE_MARKER + " role=reviewed context=12-pairs memory=process-local");
    }

    private enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public final class Request implements AutoCloseable {
        private final long id=nextId.incrementAndGet();
        private final String input;
        private final ChatStreamListener listener;
        private final AtomicReference<State> state=new AtomicReference<>(State.QUEUED);
        private final StringBuilder raw=new StringBuilder();
        private AiStreamCall transport=AiStreamCall.NONE;
        private boolean notified;

        private Request(String input,ChatStreamListener listener){
            this.input=input;
            this.listener=listener;
        }

        public boolean isDone(){
            State current=state.get();
            return current!=State.QUEUED && current!=State.RUNNING;
        }

        public void cancel(){
            if(markCancelled()) post(() -> cancelInLoop(List.of(this)));
        }

        private boolean markCancelled(){
            State previous;
            do{
                previous=state.get();
                if(previous!=State.QUEUED && previous!=State.RUNNING) return false;
            }while(!state.compareAndSet(previous,State.CANCELLED));
            return true;
        }

        @Override public void close(){ cancel(); }
    }

    public Request streamReply(String input,ChatStreamListener listener){
        Objects.requireNonNull(input,"input");
        Objects.requireNonNull(listener,"listener");
        if(input.isBlank()) throw new IllegalArgumentException("输入不能为空");
        Request request=new Request(input,listener);
        if(closed.get()){
            request.state.set(State.FAILED);
            notifyError(request,new IllegalStateException("通讯会话已关闭"));
            return request;
        }
        if(!post(() -> {
            if(closed.get()){
                request.state.set(State.CANCELLED);
                notifyError(request,new CancellationException("通讯会话已关闭"));
                return;
            }
            queue.addLast(request);
            startNext();
        })){
            request.state.set(State.FAILED);
            notifyError(request,new IllegalStateException("通讯会话已关闭"));
        }
        return request;
    }

    public List<ConversationHistory.Turn> completedTurns(){
        return history.completedTurns();
    }

    public void cancelReplies(Collection<Request> requests){
        List<Request> pending=List.copyOf(requests);
        if(!pending.isEmpty()){
            post(() -> cancelInLoop(pending.stream().filter(Request::markCancelled).toList()));
        }
    }

    public CompletableFuture<Void> newConversation(){
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(closed.get() || !post(() -> {
            cancelAllInLoop();
            history.clear();
            result.complete(null);
        })){
            result.completeExceptionally(new IllegalStateException("通讯会话已关闭"));
        }
        return result;
    }

    private void startNext(){
        if(active!=null || closed.get()) return;
        while(!queue.isEmpty()){
            Request next=queue.removeFirst();
            if(!next.state.compareAndSet(State.QUEUED,State.RUNNING)){
                notifyError(next,new CancellationException("已停止回复"));
                continue;
            }
            active=next;
            notifySafely(next.listener::onStarted);
            if(aiClient==null){
                fail(next,new IllegalStateException("通讯模块还没接上，请检查本地 AI 配置。"));
                return;
            }
            List<AiChatMessage> previous=history.requestMessages();
            List<AiChatMessage> messages=new ArrayList<>();
            messages.add(AiChatMessage.system(systemPrompt + RUNTIME_CAPABILITIES
                    + environmentContext.currentContextPrompt()));
            messages.addAll(previous);
            messages.add(AiChatMessage.user(next.input));
            // Rebuild from committed context, so cancelled turns cannot alter the policy.
            ChatReasoningPolicy policy=new ChatReasoningPolicy();
            for(AiChatMessage message:previous){
                if(message.getRole()==AiChatRole.USER) policy.effortFor(message.getContent());
            }
            AiChatRequest request=new AiChatRequest(messages,null,0.8)
                    .withReasoningEffort(policy.effortFor(next.input));
            try{
                if(next.state.get()!=State.RUNNING){
                    cancelInLoop(List.of(next));
                    return;
                }
                next.transport=Objects.requireNonNull(aiClient.streamChatCancellable(request,new AiStreamListener(){
                    @Override public void onToken(String token){ post(() -> token(next,token)); }
                    @Override public void onComplete(){ post(() -> complete(next)); }
                    @Override public void onError(Throwable error){ post(() -> fail(next,error)); }
                }),"transport");
                if(next.state.get()==State.CANCELLED) cancelTransport(next);
            }catch(RuntimeException error){
                fail(next,error);
            }
            return;
        }
    }

    private void token(Request request,String token){
        if(active!=request || request.state.get()!=State.RUNNING || closed.get()) return;
        if(token==null || token.isEmpty()) return;
        if((long)request.raw.length()+token.length()>MAX_RESPONSE_CHARS){
            fail(request,new IllegalArgumentException("模型回复过长，已停止接收"));
            return;
        }
        request.raw.append(token);
        notifySafely(() -> request.listener.onToken(token));
    }

    private void complete(Request request){
        if(active!=request || request.state.get()!=State.RUNNING || closed.get()) return;
        AssistantReplyParser.StructuredReply parsed;
        try{
            parsed=replyParser.parseStructured(request.raw.toString());
        }catch(RuntimeException invalid){
            fail(request,invalid);
            return;
        }
        if(!request.state.compareAndSet(State.RUNNING,State.COMPLETED)) return;
        history.commit(new ConversationHistory.Turn(request.id,request.input,parsed.json(),parsed.reply()));
        request.notified=true;
        notifySafely(() -> request.listener.onComplete(parsed.reply()));
        active=null;
        post(this::startNext);
    }

    private void fail(Request request,Throwable error){
        if(active!=request || !request.state.compareAndSet(State.RUNNING,State.FAILED)) return;
        cancelTransport(request);
        notifyError(request,error==null ? new IllegalStateException("模型连接失败") : error);
        active=null;
        post(this::startNext);
    }

    private void cancelInLoop(List<Request> requests){
        for(Request request:requests){
            cancelTransport(request);
            queue.remove(request);
            if(active==request) active=null;
            notifyError(request,new CancellationException("已停止回复"));
        }
        post(this::startNext);
    }

    private void cancelAllInLoop(){
        List<Request> pending=new ArrayList<>(queue);
        if(active!=null) pending.addFirst(active);
        queue.clear();
        active=null;
        for(Request request:pending){
            request.state.set(State.CANCELLED);
            cancelTransport(request);
            notifyError(request,new CancellationException("已停止回复"));
        }
    }

    private void cancelTransport(Request request){
        notifySafely(request.transport::cancel);
    }

    private void notifyError(Request request,Throwable error){
        if(request.notified) return;
        request.notified=true;
        notifySafely(() -> request.listener.onError(error));
    }

    private static void notifySafely(Runnable callback){
        try{
            callback.run();
        }catch(RuntimeException failure){
            System.out.println("聊天回调失败：" + failure.getClass().getSimpleName());
        }
    }

    private boolean post(Runnable event){
        try{
            events.execute(event);
            return true;
        }catch(RejectedExecutionException ignored){
            // Closing has cancelled owned requests; discard stale provider callbacks.
            return false;
        }
    }

    @Override public void close(){
        if(!closed.compareAndSet(false,true)) return;
        post(() -> {
            cancelAllInLoop();
            try{
                if(aiClient!=null) aiClient.close();
            }finally{
                events.shutdown();
            }
        });
    }

    private static AiModelClient createAiClient(){
        try{
            return new OpenAiCompatibleChatClient(AiModelConfig.fromEnvironment());
        }catch(RuntimeException invalid){
            System.out.println("AI 配置不可用；当前不调用模型。");
            return null;
        }
    }

    private static String loadPrompt(String path){
        try(InputStream stream=ChatService.class.getResourceAsStream(path)){
            if(stream==null) throw new IllegalStateException("缺少对话资源：" + path);
            String text=new String(stream.readAllBytes(),StandardCharsets.UTF_8).strip();
            if(text.isBlank()) throw new IllegalStateException("对话资源为空：" + path);
            return text;
        }catch(IOException error){
            throw new IllegalStateException("读取对话资源失败：" + path,error);
        }
    }
}
