package app.service;

import java.util.regex.Pattern;

/** Select a request budget locally, without an extra model call. */
public final class ChatReasoningPolicy {
    private static final Pattern TECHNICAL_SUBJECT=Pattern.compile(
            "代码|源码|编程|编译|终端|日志|调试|部署|算法|数据库|接口|模型|推理|显存|依赖|服务器|端口"
            + "|线程|进程|死锁|内存|网络|协议|跨域|正则|越界|数据结构|数学|矩阵|微积分|方程|电路"
            + "|(?i:\\b(?:api|java|javascript|typescript|python|rust|golang|sql|git|docker|linux|cuda"
            + "|gpu|cpu|http|https|json|react|node|spring|maven|npm|pip|tts|asr|llm|tcp|cors)\\b)");
    private static final Pattern HELP_INTENT=Pattern.compile(
            "[?？]|帮我|帮忙|怎么|如何|为什么|为啥|能否|能不能|是否|解释|讲一下|讲讲|分析|看一下|看看"
            + "|检查|修复|修一下|解决|优化|改成|改为|切换|实现|对比|设计|排查|给我方案|下一步"
            + "|(?i:\\b(?:how|why|help|explain|debug|fix|implement|analy[sz]e|optimi[sz]e|compare)\\b)");
    private static final Pattern FAILURE=Pattern.compile(
            "报错|编译失败|死锁|连接失败|无法连接|启动失败|无法启动|加载失败|连接超时|内存泄漏|异常堆栈|堆栈跟踪"
            + "|(?:程序|应用|电脑|软件|浏览器|客户端|服务).{0,6}(?:崩溃|卡死)"
            + "|回复变慢|响应变慢|延迟升高|(?i:\\bHTTP\\s*[45]\\d\\d\\b)"
            + "|(?i:\\b(?:traceback|segmentation fault|[a-z_$][a-z0-9_$]*(?:exception|error))\\b)");
    private static final Pattern TECHNICAL_FOLLOWUP=Pattern.compile(
            "^(?:那|嗯|好|好的|所以|但是|不过|谢谢|，|,|\\s)*(?:还是不行|仍然不行|继续|再解释|再详细|然后呢|接下来呢|那该怎么办|为什么会这样|这样改可以吗|这样可以吗).*");
    private boolean lastTechnical;

    /** null means leave the normal/configured technical reasoning effort alone. */
    public synchronized String effortFor(String input){
        String text=input==null ? "" : input.strip();
        String symptoms=text.replace("没有报错", "").replace("没报错", "")
                .replace("不报错", "")
                .replace("没有崩溃", "").replace("没有死锁", "");
        boolean technical=text.contains("```") || FAILURE.matcher(symptoms).find()
                || (TECHNICAL_SUBJECT.matcher(text).find() && HELP_INTENT.matcher(text).find())
                || (lastTechnical && TECHNICAL_FOLLOWUP.matcher(text).matches());
        lastTechnical=technical;
        return technical ? null : "low";
    }
}
