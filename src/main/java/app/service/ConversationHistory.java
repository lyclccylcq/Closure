package app.service;

import app.ai.AiChatMessage;
import app.chat.model.AssistantReply;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Process-local exchanges; display retention and model context have separate limits. */
public final class ConversationHistory {
    public static final int CONTEXT_TURN_LIMIT=12;
    public static final int CONTEXT_CODE_POINT_LIMIT=24_000;
    private static final int DISPLAY_TURN_LIMIT=100;

    public record Turn(long requestId,String userText,String assistantJson,AssistantReply reply) {
        long codePoints(){
            return (long)userText.codePointCount(0,userText.length())
                    + reply.getDisplayText().codePointCount(0,reply.getDisplayText().length());
        }
    }

    private final Deque<Turn> turns=new ArrayDeque<>();

    synchronized void commit(Turn turn){
        turns.addLast(turn);
        while(turns.size()>DISPLAY_TURN_LIMIT) turns.removeFirst();
    }

    public synchronized List<Turn> completedTurns(){
        return List.copyOf(turns);
    }

    synchronized List<AiChatMessage> requestMessages(){
        Deque<Turn> selected=new ArrayDeque<>();
        long codePoints=0;
        var descending=turns.descendingIterator();
        while(descending.hasNext() && selected.size()<CONTEXT_TURN_LIMIT){
            Turn next=descending.next();
            if(codePoints+next.codePoints()>CONTEXT_CODE_POINT_LIMIT) break;
            selected.addFirst(next);
            codePoints+=next.codePoints();
        }
        List<AiChatMessage> messages=new ArrayList<>();
        for(Turn turn:selected){
            messages.add(AiChatMessage.user(turn.userText()));
            // The output envelope is not conversational context. Replay visible meaning once.
            messages.add(AiChatMessage.assistant(turn.reply().getDisplayText()));
        }
        return List.copyOf(messages);
    }

    synchronized void clear(){ turns.clear(); }
}
