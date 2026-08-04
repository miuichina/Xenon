package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * iOS-style predictive-back animation: the leaving screen slides off to the right
 * while the previous screen slides in from a -dp(96) parallax offset, driven by
 * the back gesture. On commit the leaving screen exits fully; on cancel it returns.
 * <p>
 * Drives {@link ActionBarLayout} through the same stock hooks as
 * {@link Material3PredictiveBack} ({@code onBackStarted}, {@code predictiveInput},
 * {@code onSlideAnimationEnd}), so it must run on API 34+.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class IosPredictiveBack {

    private static final float LAZY_START = 0.015f;
    private static final long COMMIT_DURATION = 350L;
    private static final long CANCEL_DURATION = 200L;
    private static final int PARALLAX_DP = 96;

    private IosPredictiveBack() {
    }

    public static OnBackAnimationCallback createCallback(ActionBarLayout layout, Runnable plainBack) {
        Callback callback = new Callback(layout, plainBack);
        layout.m3PredictiveCallbackCancelRunnable = () -> callback.cancelAndCleanup();
        return callback;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static final class Callback implements OnBackAnimationCallback {

        private final ActionBarLayout layout;
        private final Runnable plainBack;
        private boolean attached = false;
        private boolean invoked = false;
        private AnimatorSet runningAnim = null;

        Callback(ActionBarLayout layout, Runnable plainBack) {
            this.layout = layout;
            this.plainBack = plainBack;
        }

        @Override
        public void onBackStarted(BackEvent backEvent) {
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
            }
            if (attached) {
                finalizeStock(true);
            } else if (layout.predictiveInput) {
                undoStockPrep();
            }
            invoked = false;
            layout.onBackStarted(backEvent.getTouchX(), backEvent.getTouchY());
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            if (invoked) {
                return;
            }
            if (!layout.predictiveInput) {
                return;
            }
            float rawP = backEvent.getProgress();
            if (!attached) {
                if (rawP <= LAZY_START) {
                    return;
                }
                attached = true;
                layout.m3PredictiveActive = true;
                layout.invalidate();
            }
            float p = clamp(rawP, 0f, 1f);
            applyFrame(p);
        }

        @Override
        public void onBackCancelled() {
            invoked = false;
            if (!attached) {
                undoStockPrep();
                return;
            }
            runFinishAnim(true);
        }

        @Override
        public void onBackInvoked() {
            invoked = true;
            if (!attached) {
                undoStockPrep();
                plainBack.run();
                return;
            }
            runFinishAnim(false);
        }

        private void undoStockPrep() {
            if (!layout.predictiveInput) {
                return;
            }
            layout.predictiveInput = false;
            layout.predictiveBackInProgress = false;
            layout.onSlideAnimationEnd(true);
        }

        private void applyFrame(float p) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                return;
            }
            float w = cv.getWidth();
            if (w <= 0f) {
                return;
            }
            cv.setTranslationX(w * p);
            cvb.setTranslationX(-AndroidUtilities.dp(PARALLAX_DP) * (1f - p));
        }

        private void runFinishAnim(boolean cancel) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                finalizeStock(cancel);
                return;
            }
            float cvTarget = cancel ? 0f : cv.getWidth();

            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                    ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTarget),
                    ObjectAnimator.ofFloat(cvb, View.TRANSLATION_X, 0f)
            );
            set.setDuration(cancel ? CANCEL_DURATION : COMMIT_DURATION);
            set.setInterpolator(cancel
                    ? CubicBezierInterpolator.EASE_OUT_QUINT
                    : CubicBezierInterpolator.EASE_OUT_QUINT);
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                    finalizeStock(cancel);
                }
            });
            runningAnim = set;
            set.start();
        }

        private void finalizeStock(boolean cancel) {
            cleanupViews();
            layout.m3PredictiveActive = false;
            layout.invalidate();
            if (layout.predictiveInput) {
                layout.predictiveInput = false;
                layout.predictiveBackInProgress = false;
                layout.onSlideAnimationEnd(cancel);
            } else if (!cancel) {
                layout.onBackPressed();
            }
            attached = false;
        }

        private void cleanupViews() {
            ViewGroup cv = layout.containerView;
            if (cv != null) {
                cv.setTranslationX(0f);
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
                cvb.setTranslationX(0f);
            }
        }

        public void cancelAndCleanup() {
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
            }
            if (attached) {
                finalizeStock(true);
            } else if (layout.predictiveInput) {
                undoStockPrep();
            }
        }
    }
}
