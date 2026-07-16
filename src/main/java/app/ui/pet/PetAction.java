package app.ui.pet;

public enum PetAction {
    OPEN("/animations/closure/open", 83);

    private final String frameDirectory;
    private final int frameDelayMillis;

    PetAction(String frameDirectory, int frameDelayMillis) {
        this.frameDirectory = frameDirectory;
        this.frameDelayMillis = frameDelayMillis;
    }

    public String frameDirectory() {
        return frameDirectory;
    }

    int frameDelayMillis() {
        return frameDelayMillis;
    }

    public boolean hasFrames() {
        return PetAction.class.getResource(framePath(1)) != null;
    }

    String framePath(int frameNumber) {
        return "%s/frame_%03d.png".formatted(frameDirectory, frameNumber);
    }
}
