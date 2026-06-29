package app.service;

public class ChatService {

    public String getReply(String input){
        if(input.contains("你好")){
            return "无人机整备完毕。离出发还有多长时间？我看看能不能补个觉......";
        }
        if (input.contains("累")){
            return "博士，你这次体检的指标怎么回事？什么，觉得最近很虚弱，怕自己时日无多？！我这就把这些年多收你的钱给医疗部当特别经费，放心，一块钱都没动过——报告是假的？逗我玩？华法琳！";
        }
        if (input.contains("饿")){
            return"烧烤味饼干，来一片？";
        }
        if (input.contains("生日")){
            return "罗德岛干员过生日均有机会获得采购部免单券，你也不例外！来这里摇奖，免单券就在特等奖等着你！什么？谁中过特等奖？这个——别担心，第一位特等奖得主没准就是你！";
        }
        if (input.contains("飞起来")){
            return "全体无人机，挂钩释放，功率解锁，钩住博士的兜帽——起飞!";
        }

        return "别急，我还在算呢。";
    }
}
