package app.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatReasoningPolicyTest {
    @Test
    void everydayConversationAndWorkSharingUseLow(){
        var policy=new ChatReasoningPolicy();
        for(String input:new String[]{"早上好，可露", "今天吃什么？", "我今天写了一天代码，好累",
                "Python 项目终于写完了", "今天代码没有报错，挺开心的", "帮我推荐一份晚饭",
                "帮我调整一下健身计划", "为什么今天有点困", "谢谢，辛苦啦", "今天心情有点崩溃",
                "今天写代码写得快崩溃了"}){
            assertEquals("low",policy.effortFor(input),input);
        }
    }

    @Test
    void technicalQuestionsAndActualFailuresKeepNormalReasoning(){
        var policy=new ChatReasoningPolicy();
        for(String input:new String[]{"帮我分析这个 Java 线程死锁", "API 为什么返回 HTTP 500？",
                "如何优化数据库查询", "给我讲一下 Python 的生成器", "帮我检查模型 API 的配置",
                "Traceback (most recent call last): TypeError: invalid argument",
                "编译失败", "程序突然崩溃了", "为什么数组会越界？", "帮我解释这个矩阵方程",
                "```java\nthrow new RuntimeException();\n```"}){
            assertNull(policy.effortFor(input),input);
        }
    }

    @Test
    void technicalFollowupStaysNormalAndAChangeToDailyChatResetsIt(){
        var policy=new ChatReasoningPolicy();
        assertNull(policy.effortFor("帮我排查 API 连接失败"));
        assertNull(policy.effortFor("还是不行，再解释一下"));
        assertEquals("low",policy.effortFor("晚饭吃什么？"));
        assertEquals("low",policy.effortFor("继续说"));
    }
}
