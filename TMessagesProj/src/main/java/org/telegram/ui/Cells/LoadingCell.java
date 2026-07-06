/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.LayoutHelper;

public class LoadingCell extends FrameLayout {

    private ImageView progressBar;
    private int height;

    public LoadingCell(Context context) {
        this(context, AndroidUtilities.dp(40), AndroidUtilities.dp(54));
    }

    public LoadingCell(Context context, int size, int h) {
        super(context);

        height = h;

        progressBar = new ImageView(context);
        progressBar.setImageDrawable(new CircularProgressDrawable(size, AndroidUtilities.dp(2.25f), 0xffffffff));
        addView(progressBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
