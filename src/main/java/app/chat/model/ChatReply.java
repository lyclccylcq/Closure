package app.chat.model;

public class ChatReply {
    private final String text;
    private final String voicePath;

    public ChatReply(String text,String voicePath){
        this.text=text;
        this.voicePath=voicePath;
    }

    public String getText(){
        return text;
    }

    public String getVoicePath(){
        return voicePath;
    }

    public boolean hasVoice(){
        return voicePath!=null && !voicePath.isBlank();
    }
}
