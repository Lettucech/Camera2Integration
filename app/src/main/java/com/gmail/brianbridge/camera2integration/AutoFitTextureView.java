/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gmail.brianbridge.camera2integration;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;

/**
 * A {@link TextureView} that can be adjusted to a specified aspect ratio.
 */
public class AutoFitTextureView extends TextureView {
	public static final String TAG = AutoFitTextureView.class.getSimpleName();

	private int mRatioWidth = 0;
	private int mRatioHeight = 0;

	public AutoFitTextureView(Context context) {
		this(context, null);
	}

	public AutoFitTextureView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
	 * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
	 * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
	 *
	 * @param width  Relative horizontal size
	 * @param height Relative vertical size
	 */
	public void setAspectRatio(int width, int height) {
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException("Size cannot be negative.");
		}
		mRatioWidth = width;
		mRatioHeight = height;
		requestLayout();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int width = MeasureSpec.getSize(widthMeasureSpec);
		int height = MeasureSpec.getSize(heightMeasureSpec);

		if (0 == mRatioWidth || 0 == mRatioHeight) {
			setMeasuredDimension(width, height);
		} else {
			float sourceRatio = (float) mRatioWidth / mRatioHeight;
			float targetRatio = (float) width / height;
			boolean baseOnLargerDiff = sourceRatio < targetRatio;

			Log.d(TAG, "sourceRatio: " + sourceRatio);
			Log.d(TAG, "targetRatio: " + targetRatio);
			Log.d(TAG, "baseOnLargerDiff: " + baseOnLargerDiff);

			int widthDiff = mRatioWidth - width;
			int heightDiff = mRatioHeight - height;
			Log.d(TAG, "diff: w = " + widthDiff + ", h = " + heightDiff);

			if (baseOnLargerDiff && widthDiff > heightDiff || !baseOnLargerDiff && widthDiff < heightDiff) {
				Log.d(TAG, "width based: " + width + "x" + (int) Math.ceil((float) width / mRatioWidth * mRatioHeight));
				setMeasuredDimension(
						width,
						(int) Math.ceil((float) width / mRatioWidth * mRatioHeight));
			} else {
				Log.d(TAG, "height based: " + (int) Math.ceil((float) height / mRatioHeight * mRatioWidth) + "x" + height);
				setMeasuredDimension(
						(int) Math.ceil((float) height / mRatioHeight * mRatioWidth),
						height);
			}
		}
	}

}