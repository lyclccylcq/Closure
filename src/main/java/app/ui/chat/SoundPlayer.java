package app.ui.chat;

import javafx.application.Platform;
import javafx.scene.media.AudioClip;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public final class SoundPlayer {
    private static final String SEND_SOUND="/audio/send.wav";
    private static final String RECEIVE_SOUND="/audio/receive.wav";
    private static final String CLICK_SOUND="/audio/click.wav";
    private static final String OPEN_SOUND="/audio/open.wav";

    private final Map<String, AudioClip> clipCache=new HashMap<>();
    private MediaPlayer currentVoicePlayer;
    private double effectVolume=0.55;
    private double voiceVolume=0.9;

    public SoundPlayer(){
    }

    void playSend(){
        playEffect(SEND_SOUND);
    }

    void playReceive(){
        playEffect(RECEIVE_SOUND);
    }

    void playClick(){
        playEffect(CLICK_SOUND);
    }

    void playOpen(){
        playEffect(OPEN_SOUND);
    }

    public void playVoice(String resourcePath){
        if(resourcePath==null || resourcePath.isBlank()){
            return;
        }

        runOnFxThread(() -> {
            stopVoiceNow();

            URL resourceUrl=SoundPlayer.class.getResource(resourcePath);
            if(resourceUrl==null){
                return;
            }

            Media media=new Media(resourceUrl.toExternalForm());
            currentVoicePlayer=new MediaPlayer(media);
            currentVoicePlayer.setVolume(voiceVolume);
            currentVoicePlayer.setOnEndOfMedia(() -> currentVoicePlayer=null);
            currentVoicePlayer.play();
        });
    }

    public void stopVoice(){
        runOnFxThread(this::stopVoiceNow);
    }

    private void stopVoiceNow(){
        if(currentVoicePlayer!=null){
            currentVoicePlayer.stop();
            currentVoicePlayer.dispose();
            currentVoicePlayer=null;
        }
    }

    void setEffectVolume(double effectVolume){
        this.effectVolume=clampVolume(effectVolume);
    }

    void setVoiceVolume(double voiceVolume){
        this.voiceVolume=clampVolume(voiceVolume);
    }

    private void playEffect(String resourcePath){
        runOnFxThread(() -> {
            AudioClip clip=getEffectClip(resourcePath);
            if(clip!=null){
                clip.play(effectVolume);
            }
        });
    }

    private AudioClip getEffectClip(String resourcePath){
        if(clipCache.containsKey(resourcePath)){
            return clipCache.get(resourcePath);
        }

        URL resourceUrl=SoundPlayer.class.getResource(resourcePath);
        if(resourceUrl==null){
            clipCache.put(resourcePath,null);
            return null;
        }

        AudioClip clip=new AudioClip(resourceUrl.toExternalForm());
        clipCache.put(resourcePath,clip);
        return clip;
    }

    private void runOnFxThread(Runnable action){
        if(Platform.isFxApplicationThread()){
            action.run();
        }else{
            Platform.runLater(action);
        }
    }

    private double clampVolume(double volume){
        return Math.max(0.0,Math.min(1.0,volume));
    }
}
