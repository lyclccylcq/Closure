package app.service;

import app.ai.AiChatRole;
import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryTest {
    @Test
    void budgetCountsTheVisibleContextRatherThanUnusedOutputEnvelope(){
        var history=new ConversationHistory();
        String user="甲".repeat(23_900),display="乙".repeat(40),speech="丙".repeat(120);
        String raw="{\"displayText\":\""+display+"\",\"speechText\":\""+speech+"\",\"dominantEmotion\":\"NEUTRAL\"}";
        assertTrue(user.length()+raw.length()>ConversationHistory.CONTEXT_CODE_POINT_LIMIT);
        assertTrue(user.length()+display.length()<ConversationHistory.CONTEXT_CODE_POINT_LIMIT);
        history.commit(new ConversationHistory.Turn(1,user,raw,new AssistantReply(display,speech,SpeechEmotion.NEUTRAL)));
        assertEquals(2,history.requestMessages().size());
        assertEquals(display,history.requestMessages().getLast().getContent());
    }

    @Test
    void historyProjectsVisibleMeaningWithoutRepeatingSpeechOrEmotionMetadata(){
        var history=new ConversationHistory();
        String json="{\"displayText\":\"完成了。\",\"speechText\":\"语音专用摘要。\",\"dominantEmotion\":\"TEASING\"}";
        history.commit(new ConversationHistory.Turn(1,"合成输入。",json,
                new AssistantReply("完成了。","语音专用摘要。",SpeechEmotion.TEASING)));
        var context=history.requestMessages();
        assertEquals(2,context.size());
        assertEquals("完成了。",context.get(1).getContent());
        assertFalse(context.get(1).getContent().contains("语音专用"));
        assertFalse(context.get(1).getContent().contains("TEASING"));
        assertEquals(json,history.completedTurns().getFirst().assistantJson());
    }

    private ConversationHistory.Turn turn(long id,String user){
        String json="{\"displayText\":\"回答\",\"speechText\":\"朗读\",\"dominantEmotion\":\"NEUTRAL\"}";
        return new ConversationHistory.Turn(id,user,json,new AssistantReply("回答","朗读",SpeechEmotion.NEUTRAL));
    }

    @Test
    void contextKeepsTwelveWholePairsAndDisplayHistoryRemainsIndependent(){
        var history=new ConversationHistory();
        for(int i=0;i<15;i++) history.commit(turn(i,"消息"+i));
        var messages=history.requestMessages();
        assertEquals(24,messages.size());
        assertEquals("消息3",messages.getFirst().getContent());
        assertEquals("消息14",messages.get(22).getContent());
        for(int i=0;i<messages.size();i++){
            assertEquals(i%2==0 ? AiChatRole.USER : AiChatRole.ASSISTANT,messages.get(i).getRole());
        }
        assertEquals(15,history.completedTurns().size());
        assertThrows(UnsupportedOperationException.class,()->history.completedTurns().clear());
    }

    @Test
    void budgetDropsWholeOldPairsInsteadOfSplittingOrResurrectingOlderContext(){
        var history=new ConversationHistory();
        history.commit(turn(1,"早前的小消息"));
        history.commit(turn(2,"字".repeat(13_000)));
        history.commit(turn(3,"新".repeat(13_000)));
        assertEquals(2,history.requestMessages().size());
        assertEquals("新".repeat(13_000),history.requestMessages().getFirst().getContent());
        history.commit(turn(4,"😀".repeat(24_001)));
        assertTrue(history.requestMessages().isEmpty());
        assertEquals(4,history.completedTurns().size());
    }

    @Test
    void displayRetentionIsBoundedAndExplicitClearRemovesAllContext(){
        var history=new ConversationHistory();
        for(int i=0;i<105;i++) history.commit(turn(i,"消息"+i));
        assertEquals(100,history.completedTurns().size());
        assertEquals(5,history.completedTurns().getFirst().requestId());
        history.clear();
        assertTrue(history.completedTurns().isEmpty());
        assertTrue(history.requestMessages().isEmpty());
    }
}
