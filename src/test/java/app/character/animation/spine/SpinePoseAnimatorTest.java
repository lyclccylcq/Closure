package app.character.animation.spine;

import app.character.animation.AnimationType;
import com.esotericsoftware.spine.Skeleton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpinePoseAnimatorTest {
    private static final class Pose {
        final SpineModel model=SpineModel.bundled();
        final Skeleton skeleton;
        final SpinePoseAnimator animator;
        Pose(){
            synchronized(model){
                skeleton=new Skeleton(model.data);
                animator=new SpinePoseAnimator(model,skeleton);
                animator.begin(AnimationType.IDLE,true);
                animator.apply(.4f,0);
            }
        }
        void begin(AnimationType type){ synchronized(model){animator.begin(type,type!=AnimationType.LANDING);} }
        void step(float delta,float motion){ synchronized(model){animator.apply(delta,motion);} }
        void advance(float seconds){ for(int i=0;i<Math.round(seconds/.02f);i++) step(.02f,0); }
        float y(String bone){ return skeleton.findBone(bone).getWorldY(); }
    }

    @Test
    void waistLiftReleasesFootIkAndLeavesBothFeetSuspendedWithoutPoseAccumulation(){
        var pose=new Pose();
        float waist=pose.y("F_Waist_I"), left=pose.y("F_L_Foot_I2"),right=pose.y("F_R_Foot_I2");
        pose.begin(AnimationType.DRAGGING); pose.advance(.4f);
        assertTrue(pose.y("F_Waist_I")>waist+60);
        assertTrue(pose.y("F_L_Foot_I2")>left+20);
        assertTrue(pose.y("F_R_Foot_I2")>right+20);
        for(var ik:pose.skeleton.getIkConstraints()) if(ik.getData().getName().startsWith("F_IK_")){
            assertEquals(0,ik.getMix(),.001);
        }
        pose.advance(2);
        assertTrue(pose.y("F_Waist_I")>waist+60 && pose.y("F_Waist_I")<waist+100);
        pose.begin(AnimationType.LANDING); pose.advance(.18f);
        assertTrue(pose.y("F_Waist_I")<waist);
        pose.advance(.4f);
        pose.begin(AnimationType.IDLE); pose.advance(.4f);
        for(var ik:pose.skeleton.getIkConstraints()) if(ik.getData().getName().startsWith("F_IK_")){
            assertEquals(1,ik.getMix(),.001);
        }
        assertEquals(waist,pose.y("F_Waist_I"),5);
    }

    @Test
    void upperTorsoAndHeadDroopBelowTheWaistWhileBothArmsHangDown(){
        var pose=new Pose();
        pose.begin(AnimationType.DRAGGING);pose.advance(.5f);
        assertTrue(pose.y("F_Chest_I2")<pose.y("F_Waist_I"));
        assertTrue(pose.y("F_Head_I")<pose.y("F_Waist_I")-3);
        assertTrue(pose.y("F_L_Hand_I")<pose.y("F_L_Arm_II")-30);
        assertTrue(pose.y("F_R_Hand_I")<pose.y("F_R_Arm_II")-30);
        assertTrue(Math.abs(pose.skeleton.findBone("F_Head_I").getWorldRotationX())>75);
    }

    @Test
    void conversationTransitionPassesThroughIntermediateBonePosesAndSeatedPressStaysSeated(){
        var pose=new Pose();
        float standing=pose.y("F_Head_I");
        pose.begin(AnimationType.THINKING); pose.step(0,0);
        assertEquals(standing,pose.y("F_Head_I"),.1);
        pose.advance(.14f);
        float middle=pose.y("F_Head_I");
        pose.advance(.4f);
        float seated=pose.y("F_Head_I");
        assertTrue(middle<Math.max(standing,seated)-1 && middle>Math.min(standing,seated)+1);
        assertTrue(Math.abs(standing-seated)>20);
        pose.begin(AnimationType.PRESSED); pose.advance(.3f);
        assertEquals(seated,pose.y("F_Head_I"),12);
        pose.begin(AnimationType.THINKING); pose.advance(.4f);
        assertEquals(seated,pose.y("F_Head_I"),5);
    }

    @Test
    void interruptedProceduralMixStartsAtTheCurrentlyDisplayedPoseAndMotionIsBounded(){
        var pose=new Pose();
        pose.begin(AnimationType.DRAGGING); pose.step(.08f,1);
        float before=pose.y("F_Waist_I");
        pose.begin(AnimationType.THINKING); pose.step(0,0);
        assertEquals(before,pose.y("F_Waist_I"),.1);
        pose.advance(.5f);
        pose.begin(AnimationType.DRAGGING); pose.advance(.4f);
        pose.step(.1f,Float.POSITIVE_INFINITY);
        assertTrue(Float.isFinite(pose.y("F_Head_I")));
        assertThrows(IllegalArgumentException.class,()->pose.step(-1,0));
    }

    @Test
    void boundedPointerLeanSettlesBackToAGentleHangingSwayWhenMotionStops(){
        var pose=new Pose();
        pose.begin(AnimationType.DRAGGING); pose.advance(.4f);
        pose.step(.1f,1000);
        assertTrue(Math.abs(pose.skeleton.findBone("F_Waist_I").getRotation())<16);
        pose.advance(.6f);
        assertTrue(Math.abs(pose.skeleton.findBone("F_Waist_I").getRotation())<5);
    }
}
