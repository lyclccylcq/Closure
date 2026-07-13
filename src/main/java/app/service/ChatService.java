package app.service;

import app.model.ChatReply;

public class ChatService {

    public ChatReply getReply(String input){
        if(input.contains("你好")){
            // 每条假回复把文字和语音资源路径放在一起，后续新增语音只需要补这里。
            return new ChatReply("无人机整备完毕。离出发还有多长时间？我看看能不能补个觉......","/audio/hello.wav");
        }
        if (input.contains("累")){
            return new ChatReply("博士，你这次体检的指标怎么回事？什么，觉得最近很虚弱，怕自己时日无多？！我这就把这些年多收你的钱给医疗部当特别经费，放心，一块钱都没动过——报告是假的？逗我玩？华法琳！","/audio/tired.mp3");
        }
        if (input.contains("饿")){
            return new ChatReply("烧烤味饼干，来一片？","/audio/hungry.wav");
        }
        if (input.contains("生日")){
            return new ChatReply("罗德岛干员过生日均有机会获得采购部免单券，你也不例外！来这里摇奖，免单券就在特等奖等着你！什么？谁中过特等奖？这个——别担心，第一位特等奖得主没准就是你！","/audio/voice/birthday.mp3");
        }
        if (input.contains("飞起来")){
            return new ChatReply("全体无人机，挂钩释放，功率解锁，钩住博士的兜帽——起飞!","/audio/voice/fly.mp3");
        }

        return new ChatReply("别急，我还在算呢。",null);
    }
}
