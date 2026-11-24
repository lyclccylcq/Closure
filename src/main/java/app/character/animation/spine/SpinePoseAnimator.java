package app.character.animation.spine;

import app.character.animation.AnimationType;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.Skeleton;

import java.util.Arrays;

/** Single render-worker state. Native timelines mix bones; procedural weights ease gestures in/out. */
final class SpinePoseAnimator {
    static final float LANDING_SECONDS = .38f;
    private static final int PRESS = 0, DRAG = 1, LAND = 2, THINK = 3, TALK = 4;
    private final Skeleton skeleton;
    private final AnimationState state;
    private final float[] weights = new float[5];
    private final float[] from = new float[5];
    private final float[] target = new float[5];
    private String action;
    private float transitionTime;
    private float phase;
    private float landingTime = LANDING_SECONDS;
    private float lean;

    SpinePoseAnimator(SpineModel model, Skeleton skeleton) {
        this.skeleton = skeleton;
        var data = new AnimationStateData(model.data);
        data.setDefaultMix(.20f);
        data.setMix("Relax", "Sit", .30f);
        data.setMix("Move", "Sit", .32f);
        data.setMix("Sleep", "Sit", .34f);
        data.setMix("Sit", "Relax", .28f);
        state = new AnimationState(data);
    }

    void begin(AnimationType type, boolean loop) {
        String next = SpineRenderer.sourceAction(type);
        // A short press can brace the seated character without standing up first.
        if (type == AnimationType.PRESSED && "Sit".equals(action)) next = "Sit";
        if (action == null || !action.equals(next) || !loop) {
            var entry = state.setAnimation(0, next, loop);
            if (action == null) entry.setMixDuration(0);
        } else {
            state.getCurrent(0).setLoop(loop);
        }
        action = next;
        System.arraycopy(weights, 0, from, 0, weights.length);
        fillWeights(target, type);
        transitionTime = 0;
        if (type == AnimationType.LANDING) landingTime = 0;
    }

    void apply(float delta, float horizontalMotion) {
        if (!Float.isFinite(delta) || delta < 0) throw new IllegalArgumentException("Invalid frame delta");
        state.update(delta);
        // Reset modifiers and IK before evaluating this frame, preventing pose accumulation.
        skeleton.setToSetupPose();
        state.apply(skeleton);
        phase += delta;
        landingTime = Math.min(LANDING_SECONDS, landingTime + delta);
        transitionTime += delta;
        float t = Math.min(1, transitionTime / .18f);
        float eased = t * t * (3 - 2 * t);
        for (int i = 0; i < weights.length; i++) weights[i] = from[i] + (target[i] - from[i]) * eased;
        float desired = Float.isFinite(horizontalMotion) ? Math.max(-1, Math.min(1, horizontalMotion)) : 0;
        lean += (desired - lean) * Math.min(1, delta * 12);
        applyModifiers(skeleton, phase, landingTime, weights, lean);
        skeleton.updateWorldTransform();
    }

    static void applyVariant(Skeleton skeleton, AnimationType type, float seconds) {
        if (type == null) return;
        float[] weights = new float[5];
        fillWeights(weights, type);
        applyModifiers(skeleton, seconds, seconds, weights, 0);
    }

    private static void fillWeights(float[] weights, AnimationType type) {
        Arrays.fill(weights, 0);
        if (type == AnimationType.PRESSED) weights[PRESS] = 1;
        if (type == AnimationType.DRAGGING) weights[DRAG] = 1;
        if (type == AnimationType.LANDING) weights[LAND] = 1;
        if (type == AnimationType.THINKING || type == AnimationType.THINKING_TALK) weights[THINK] = 1;
        if (type == AnimationType.TALK || type == AnimationType.THINKING_TALK) weights[TALK] = 1;
    }

    private static void applyModifiers(Skeleton skeleton, float seconds, float landingTime,
                                       float[] weights, float lean) {
        float drag = weights[DRAG];
        float swing = (float) Math.sin(seconds * 3.2) * 4 - lean * 10;
        float landing = (float) Math.sin(Math.PI * Math.min(1, landingTime / LANDING_SECONDS));
        Bone waist = skeleton.findBone("F_Waist_I");
        if (waist != null) {
            waist.setY(waist.getY() - 6 * weights[PRESS] + 46 * drag - 18 * landing * landing * weights[LAND]);
            waist.setRotation(waist.getRotation() + swing * drag);
        }
        if (drag > 0) {
            // The exported foot targets are on the standing floor. Let the suspended legs hang free.
            for (var constraint : skeleton.getIkConstraints()) {
                if (constraint.getData().getName().startsWith("F_IK_")
                        && constraint.getData().getName().contains("Foot")) {
                    constraint.setMix(constraint.getMix() * (1 - drag));
                }
            }
            float legs = (float) Math.sin(seconds * 3.2 + .6) * 4;
            hang(skeleton, "F_R_Leg_I", 5 + legs, drag);
            hang(skeleton, "F_L_Leg_I", -3 - legs, drag);
            hang(skeleton, "F_R_Calf_I", 18, drag);
            hang(skeleton, "F_L_Calf_I", 12, drag);
            hang(skeleton, "F_R_Foot_I", -42, drag);
            hang(skeleton, "F_L_Foot_I", -25, drag);
        }
        Bone mouth = skeleton.findBone("F_R_Mouth_II");
        if (mouth != null) mouth.setScaleY(mouth.getScaleY()
                * (1 + weights[TALK] * .65f * Math.abs((float) Math.sin(seconds * 15))));
        Bone head = skeleton.findBone("F_Head_I");
        if (head != null) head.setRotation(head.getRotation()
                + weights[TALK] * 1.5f * (float) Math.sin(seconds * 7)
                + weights[THINK] * (5 + (float) Math.sin(seconds * 2))
                - 2 * weights[PRESS] - .4f * swing * drag);
    }

    private static void hang(Skeleton skeleton, String name, float rotation, float weight) {
        Bone bone = skeleton.findBone(name);
        if (bone != null) bone.setRotation(bone.getRotation() + (rotation - bone.getRotation()) * weight);
    }
}
