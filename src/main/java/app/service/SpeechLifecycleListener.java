package app.service;

public interface SpeechLifecycleListener {
    SpeechLifecycleListener NO_OP = new SpeechLifecycleListener() {
        @Override
        public void onSpeechStarted() {
        }

        @Override
        public void onSpeechFinished() {
        }
    };

    void onSpeechStarted();

    void onSpeechFinished();
}
