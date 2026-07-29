package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.CubicBezierInterpolator;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class BottomSheetPredictiveBack {

    private static final float LAZY_START = 0.02f;

    public static OnBackAnimationCallback createCallback(BottomSheet sheet) {
        return new Callback(sheet);
    }

    private static final class Callback implements OnBackAnimationCallback {
        private final BottomSheet sheet;
        private boolean attached = false;
        private boolean isButton = false;
        private AnimatorSet runningAnim = null;
        private float maxTranslateY = 0f;
        private float lastP = 0f;
        private float currentEased = 0f;
        private boolean allowDismiss = true;

        Callback(BottomSheet sheet) {
            this.sheet = sheet;
        }

        @Override
        public void onBackStarted(BackEvent backEvent) {
            if (runningAnim != null) {
                runningAnim.cancel();
                runningAnim = null;
            }
            attached = false;
            isButton = false;
            lastP = 0f;
            currentEased = 0f;
            try {
                java.lang.reflect.Method m = BottomSheet.class.getDeclaredMethod("canDismissWithSwipe");
                m.setAccessible(true);
                allowDismiss = (boolean) m.invoke(sheet);
            } catch (Exception e) {
                allowDismiss = true;
            }
            if (!allowDismiss) return;
            View cv = sheet.getSheetContainer();
            if (cv == null) return;
            maxTranslateY = cv.getHeight()
                    + (sheet.getKeyboardHeight() > 0 ? sheet.getKeyboardHeight() : 0)
                    + AndroidUtilities.dp(10)
                    + Math.max(0, Math.min(AndroidUtilities.navigationBarHeight, sheet.getBottomInset()));
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            if (!allowDismiss) return;
            if (!attached) {
                if (backEvent.getProgress() <= LAZY_START) return;
                attached = true;
                if (backEvent.getProgress() >= 0.99f) {
                    isButton = true;
                }
            }
            if (isButton) return;
            View cv = sheet.getSheetContainer();
            if (cv == null) return;
            float p = Math.min((backEvent.getProgress() - LAZY_START) / (1f - LAZY_START), 1f);
            if (p <= lastP && p >= 0.99f) return;
            lastP = p;
            float eased = 1f - (1f - p) * (1f - p);
            currentEased = eased;
            cv.setTranslationY(maxTranslateY * eased);
            sheet.setPredictiveBackProgress(eased);
            sheet.getContainer().invalidate();
        }

        @Override
        public void onBackCancelled() {
            isButton = false;
            if (!allowDismiss || !attached) {
                return;
            }
            runFinishAnim(true);
        }

        @Override
        public void onBackInvoked() {
            if (!allowDismiss) {
                return;
            }
            if (!attached || isButton) {
                sheet.dismiss();
                return;
            }
            runFinishAnim(false);
        }

        private void runFinishAnim(boolean cancel) {
            View cv = sheet.getSheetContainer();
            if (cv == null) {
                if (!cancel) sheet.dismiss();
                return;
            }
            float startTranslation = cv.getTranslationY();
            float endTranslation = cancel ? 0f : (maxTranslateY > 0 ? maxTranslateY : cv.getHeight() + AndroidUtilities.dp(10));
            float startBlur = currentEased;
            float endBlur = cancel ? 0f : 1f;

            runningAnim = new AnimatorSet();
            runningAnim.playTogether(
                    ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, startTranslation, endTranslation)
            );
            ValueAnimator blurAnim = ValueAnimator.ofFloat(startBlur, endBlur);
            blurAnim.addUpdateListener(a -> sheet.setPredictiveBackProgress((float) a.getAnimatedValue()));
            runningAnim.playTogether(blurAnim);
            runningAnim.setDuration(cancel ? 200 : 250);
            runningAnim.setInterpolator(cancel ? new DecelerateInterpolator() : CubicBezierInterpolator.EASE_OUT);
            runningAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                    if (!cancel) {
                        sheet.predictiveBackFinish();
                    }
                }
            });
            runningAnim.start();
        }
    }
}
