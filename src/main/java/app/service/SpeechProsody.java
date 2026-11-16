package app.service;

import app.voice.model.SpeechEmotion;
import java.util.regex.Pattern;

/** Preserve clearly interrogative speech without treating every question mark as emotion. */
public final class SpeechProsody {
    private static final Pattern QUESTION_WORDS=Pattern.compile(
            "吗|呢|么|是否|能否|是不是|能不能|要不要|有没有|可不可以|需不需要|愿不愿意"
            + "|为什么|为何|怎么|如何|什么|谁|多少|哪[个里一位儿]|几[点天个次]"
            + "|(?i:\\b(?:why|how|what|who|where|when|which|can|could|would|should|do|does|did|is|are)\\b)");

    private SpeechProsody(){}

    public static boolean isQuestion(String text){
        if(text==null) return false;
        String spoken=text.strip().replaceFirst("[”’」』\\\"]+$","");
        return (spoken.endsWith("？") || spoken.endsWith("?"))
                && !spoken.matches("(?s).*[。！!].*")
                && QUESTION_WORDS.matcher(spoken).find();
    }

    public static SpeechEmotion emotionFor(String text,SpeechEmotion declared){
        SpeechEmotion emotion=declared==null ? SpeechEmotion.NEUTRAL : declared;
        return emotion==SpeechEmotion.NEUTRAL && isQuestion(text) ? SpeechEmotion.QUESTION : emotion;
    }

    public static String preserveQuestion(String display,String speech){
        if(speech==null || speech.isBlank()) return display;
        if(display!=null && display.codePointCount(0,display.length())<=80
                && isQuestion(display) && !isQuestion(speech)){
            return display;
        }
        return speech;
    }
}
