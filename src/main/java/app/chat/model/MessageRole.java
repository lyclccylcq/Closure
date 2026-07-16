package app.chat.model;

public enum MessageRole {
    USER("我","/images/npc_001_doctor.png",true),
    CLOSURE("可露希尔", "/images/char_4228_closure_2.png",false);

    private final String displayName;
    private final String avatarPath;
    private final boolean user;

    MessageRole(String displayName,String avatarPath,boolean user){
        this.displayName=displayName;
        this.avatarPath=avatarPath;
        this.user=user;
    }

    public String getDisplayName(){
        return displayName;
    }

    public String getAvatarPath(){
        return avatarPath;
    }

    public boolean isUser(){
        return user;
    }
}
