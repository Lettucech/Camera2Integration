package com.gmail.brianbridge.camera2integration;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.gmail.brianbridge.camera2integration.Camera2BaseFragment.CameraState.*;

public class Camera2BaseFragment extends Fragment implements View.OnClickListener {
	public static final String TAG = Camera2BaseFragment.class.getSimpleName();
	public static final int REQUEST_CAMERA_PERMISSION = 100;

	public enum CameraState {
		STATE_PREVIEW,					// Showing camera preview
		STATE_WAITING_LOCK,				// Waiting for the focus to be locked.
		STATE_WAITING_PRECAPTURE,		// Waiting for the exposure to be precapture state.
		STATE_WAITING_NON_PRECAPTURE,	// Waiting for the exposure state to be something other than precapture.
		STATE_PICTURE_TAKEN				// Picture was taken.
	}

	private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
			openCamera(width, height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
			configureTransform(width, height);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
			return false;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
	};

	private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
		@Override
		public void onOpened(@NonNull CameraDevice cameraDevice) {
			// This method is called when the camera is opened.  We start camera preview here.
			mCameraOpenCloseLock.release();
			mCameraDevice = cameraDevice;
			initCameraPreview();
		}

		@Override
		public void onDisconnected(@NonNull CameraDevice cameraDevice) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
		}

		@Override
		public void onError(@NonNull CameraDevice cameraDevice, int error) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
			Activity activity = getActivity();
			if (null != activity) {
				activity.finish();
			}
		}
	};

	private final ImageReader.OnImageAvailableListener mOnCaptureAvailableListener = new ImageReader.OnImageAvailableListener() {
		@Override
		public void onImageAvailable(ImageReader reader) {
			mBackgroundHandler.post(new CameraUtil.ImageSaver(reader.acquireNextImage(), mFile));
		}
	};

	private final ImageReader.OnImageAvailableListener mOnSnapAvailableListener = new ImageReader.OnImageAvailableListener() {
		@Override
		public void onImageAvailable(ImageReader reader) {
			reader.acquireNextImage().close();
			if (!capturing) {
				capturing = true;
				takePicture();
				mBackgroundHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						capturing = false;
					}
				}, 5000);
			}
		}
	};

	private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
		private void process(CaptureResult result) {
			switch (mState) {
				case STATE_PREVIEW: {
					Log.d(TAG, "STATE_PREVIEW");
					// We have nothing to do when the camera preview is working normally.
					break;
				}
				case STATE_WAITING_LOCK:
					Log.d(TAG, "STATE_WAITING_LOCK");
					Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
					if (afState == null) {
						captureStillPicture();
					} else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
							CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
						// CONTROL_AE_STATE can be null on some devices
						Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
						if (aeState == null ||
								aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
							mState = STATE_PICTURE_TAKEN;
							captureStillPicture();
						} else {
							runPrecaptureSequence();
						}
					}
					break;
				case STATE_WAITING_PRECAPTURE: {
					Log.d(TAG, "STATE_WAITING_PRECAPTURE");
					// CONTROL_AE_STATE can be null on some devices
					Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
					if (aeState == null ||
							aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
							aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
						mState = STATE_WAITING_NON_PRECAPTURE;
					}
					break;
				}
				case STATE_WAITING_NON_PRECAPTURE: {
					Log.d(TAG, "STATE_WAITING_NON_PRECAPTURE");
					// CONTROL_AE_STATE can be null on some devices
					Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
					if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
						mState = STATE_PICTURE_TAKEN;
						captureStillPicture();
					}
					break;
				}
			}
		}

		@Override
		public void onCaptureProgressed(@NonNull CameraCaptureSession session,
										@NonNull CaptureRequest request,
										@NonNull CaptureResult partialResult) {
			process(partialResult);
		}

		@Override
		public void onCaptureCompleted(@NonNull CameraCaptureSession session,
									   @NonNull CaptureRequest request,
									   @NonNull TotalCaptureResult result) {
			process(result);
		}

	};

	// Views
	private AutoFitTextureView mTextureView;
	private Button mCaptureButton;

	// Camera & Preview Controls
	private CameraCaptureSession mCaptureSession;
	private CameraDevice mCameraDevice; // Current opened camera
	private ImageReader mImageReader;
	private ImageReader mSnapImageReader;
	private CaptureRequest.Builder mPreviewRequestBuilder;
	private CaptureRequest mPreviewRequest;
	private HandlerThread mBackgroundThread;
	private Handler mBackgroundHandler;
	private HandlerThread mSnapThread;
	private Handler mSnapHandler;
	private HandlerThread mCaptureThread;
	private Handler mCaptureHandler;

	// Camera & Preview Data
	private String mCameraId; // ID of the current CameraDevice
	private CameraState mState = STATE_PREVIEW;
	private int mCameraSensorOrientation;
	private boolean mFlashSupported;
	private Size mPreviewSize;

	// Others
	private Semaphore mCameraOpenCloseLock = new Semaphore(1); // to prevent the app from exiting before closing the camera.
	private File mFile; // output
	private boolean capturing = false;


	// Config Params
	private List<Integer> mDeniedLens;
	private Size mAspectRatio;

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
							 @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_camera, container, false);
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		mTextureView = (AutoFitTextureView) view.findViewById(R.id.textureView);
		mCaptureButton = (Button) view.findViewById(R.id.btn_capture);

		mCaptureButton.setOnClickListener(this);
	}

	@Override
	public void onActivityCreated(@Nullable Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		mFile = new File(getActivity().getExternalFilesDir(null), "pic.jpg");
	}

	@Override
	public void onResume() {
		super.onResume();
		startBackgroundThread();
//		if (mTextureView.isAvailable()) {
//			openCamera(mTextureView.getWidth(), mTextureView.getHeight());
//		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
//		}
	}

	@Override
	public void onPause() {
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	private void requestCameraPermission() {
		if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
			Toast.makeText(getContext(),
					"Need camera permission, please grant it in app setting.",
					Toast.LENGTH_SHORT)
					.show();
		} else {
			requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
										   @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

		mSnapThread = new HandlerThread("SnapBackground");
		mSnapThread.start();
		mSnapHandler = new Handler(mSnapThread.getLooper());

		mCaptureThread = new HandlerThread("CaptureBackground");
		mCaptureThread.start();
		mCaptureHandler = new Handler(mCaptureThread.getLooper());
	}

	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		mSnapThread.quitSafely();
		mCaptureThread.quitSafely();
		try {
			mBackgroundThread.join(); // join to main thread, wait for background thread die
			mBackgroundThread = null;
			mBackgroundHandler = null;

			mSnapThread.join(); // join to main thread, wait for background thread die
			mSnapThread = null;
			mSnapHandler = null;

			mCaptureThread.join(); // join to main thread, wait for background thread die
			mCaptureThread = null;
			mCaptureHandler = null;
		} catch (InterruptedException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void openCamera(int width, int height) {
		if (ContextCompat.checkSelfPermission(getActivity(),
				Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			requestCameraPermission();
			return;
		}
		initCamera(width, height);
		configureTransform(width, height);
		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}
			manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
		}
	}

	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			if (null != mCaptureSession) {
				mCaptureSession.close();
				mCaptureSession = null;
			}
			if (null != mCameraDevice) {
				mCameraDevice.close();
				mCameraDevice = null;
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
		} finally {
			mCameraOpenCloseLock.release();
		}
	}

	private void initCamera(int width, int height) {
		Activity activity = getActivity();
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			for (String cameraId: manager.getCameraIdList()) {
				CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

				if (mDeniedLens != null) {
					// Filter the denied camera
					Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
					if (facing != null && mDeniedLens.contains(facing)) {
						continue;
					}
				}

				StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				if (map == null) {
					continue;
				}

				// Get display ratio
				float displayRatio;
				Point displaySize = new Point();
				activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
				if (mAspectRatio == null) {
					displayRatio=(float) displaySize.x / displaySize.y;
				} else {
					displayRatio = (float) mAspectRatio.getWidth() / mAspectRatio.getHeight();
				}

				// Get the largest output resolution of camera
				List<Size> outputSizes = Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888));
				Size largest = null;
				for (Size size: outputSizes) {
					if ((float) size.getHeight() / size.getWidth() == displayRatio) {
						largest = size;
						break;
					}
				}
				if (largest == null) {
					largest = Collections.max(
							Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
							new CameraUtil.CompareSizesByLargerArea());
				}

				mSnapImageReader = ImageReader.newInstance(
						largest.getWidth() / 4,
						largest.getHeight() / 4,
						ImageFormat.YUV_420_888,
						/*maxImages*/2);
				mSnapImageReader.setOnImageAvailableListener(mOnSnapAvailableListener, mSnapHandler);

				// Get the largest output resolution of camera
				outputSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
				Collections.sort(outputSizes, new CameraUtil.CompareSizesByLargerArea());
				largest = null;
				for (Size size: outputSizes) {
					if ((float) size.getHeight() / size.getWidth() == displayRatio) {
						largest = size;
						break;
					}
				}
				if (largest == null) {
					largest = Collections.max(
							Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
							new CameraUtil.CompareSizesByLargerArea());
				}

				mImageReader = ImageReader.newInstance(
						largest.getWidth(),
						largest.getHeight(),
						ImageFormat.JPEG,
						/*maxImages*/1);
				mImageReader.setOnImageAvailableListener(mOnCaptureAvailableListener, mCaptureHandler);

				mCameraSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

				int rotatedPreviewWidth = width;
				int rotatedPreviewHeight = height;
				int maxPreviewWidth = displaySize.x;
				int maxPreviewHeight = displaySize.y;

				if (CameraUtil.isScreenNeedRotateForCamera(activity, mCameraSensorOrientation)) {
					rotatedPreviewWidth = height;
					rotatedPreviewHeight = width;
					maxPreviewWidth = displaySize.y;
					maxPreviewHeight = displaySize.x;
				}

				if (maxPreviewWidth > CameraUtil.API2_MAX_PREVIEW_WIDTH) {
					maxPreviewWidth = CameraUtil.API2_MAX_PREVIEW_WIDTH;
				}

				if (maxPreviewHeight > CameraUtil.API2_MAX_PREVIEW_HEIGHT) {
					maxPreviewHeight = CameraUtil.API2_MAX_PREVIEW_HEIGHT;
				}

				mPreviewSize = CameraUtil.chooseOptimalSize(
						map.getOutputSizes(SurfaceTexture.class),
						rotatedPreviewWidth,
						rotatedPreviewHeight,
						maxPreviewWidth,
						maxPreviewHeight,
						largest);

				// We fit the aspect ratio of TextureView to the size of preview we picked.
				int orientation = getResources().getConfiguration().orientation;
				if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
					mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
				} else {
					mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
				}

				Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
				mFlashSupported = available == null ? false : available;

				mCameraId = cameraId;
				return;
			}
		} catch (CameraAccessException | NullPointerException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void initCameraPreview() {
		try {
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			assert texture != null;

			// We configure the size of default buffer to be the size of camera preview we want.
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

			// This is the output Surface we need to start preview.
			Surface surface = new Surface(texture);

			// We set up a CaptureRequest.Builder with the output Surface.
			mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			mPreviewRequestBuilder.addTarget(surface);
			mPreviewRequestBuilder.addTarget(mSnapImageReader.getSurface());

			// Here, we create a CameraCaptureSession for camera preview.
			mCameraDevice.createCaptureSession(Arrays.asList(surface, mSnapImageReader.getSurface(), mImageReader.getSurface()),
					new CameraCaptureSession.StateCallback() {

						@Override
						public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
							// The camera is already closed
							if (null == mCameraDevice) {
								return;
							}

							// When the session is ready, we start displaying the preview.
							mCaptureSession = cameraCaptureSession;
							try {
								// Auto focus should be continuous for camera preview.
								mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
								// Flash is automatically enabled when necessary.
//								setAutoFlash(mPreviewRequestBuilder);

								// Finally, we start displaying the camera preview.
								mPreviewRequest = mPreviewRequestBuilder.build();
								mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
							} catch (CameraAccessException e) {
								e.printStackTrace();
							}
						}

						@Override
						public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
							Log.e(TAG, "onConfigureFailed");
						}
					}, null
			);
		} catch (CameraAccessException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void configureTransform(int viewWidth, int viewHeight) {
		Activity activity = getActivity();
		if (null == mTextureView || null == mPreviewSize || null == activity) {
			return;
		}
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		Matrix matrix = new Matrix();
		RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
		RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
		float centerX = viewRect.centerX();
		float centerY = viewRect.centerY();
		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
			float scale = Math.max(
					(float) viewHeight / mPreviewSize.getHeight(),
					(float) viewWidth / mPreviewSize.getWidth());
			matrix.postScale(scale, scale, centerX, centerY);
			matrix.postRotate(90 * (rotation - 2), centerX, centerY);
		} else if (Surface.ROTATION_180 == rotation) {
			matrix.postRotate(180, centerX, centerY);
		}
		mTextureView.setTransform(matrix);
	}

	private void takePicture() {
		lockFocus();
	}

	/**
	 * Lock the focus as the first step for a still image capture.
	 */
	private void lockFocus() {
		try {
			// This is how to tell the camera to lock focus.
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
			// Tell #mCaptureCallback to wait for the lock.
			mState = STATE_WAITING_LOCK;
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void unlockFocus() {
		try {
			// Reset the auto-focus trigger
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
//			setAutoFlash(mPreviewRequestBuilder);
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
			// After this, the camera will go back to the normal state of preview.
			mState = STATE_PREVIEW;
			mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void runPrecaptureSequence() {
		try {
			// This is how to tell the camera to trigger.
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
					CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
			// Tell #mCaptureCallback to wait for the precapture sequence to be set.
			mState = STATE_WAITING_PRECAPTURE;
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
					mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void captureStillPicture() {
		try {
			final Activity activity = getActivity();
			if (null == activity || null == mCameraDevice) {
				return;
			}
			// This is the CaptureRequest.Builder that we use to take a picture.
			final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
			captureBuilder.addTarget(mImageReader.getSurface());

			// Use the same AE and AF modes as the preview.
			captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//			setAutoFlash(captureBuilder);

			// Orientation
			int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
			captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, CameraUtil.getOrientation(rotation, mCameraSensorOrientation));

			CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {

				@Override
				public void onCaptureCompleted(@NonNull CameraCaptureSession session,
											   @NonNull CaptureRequest request,
											   @NonNull TotalCaptureResult result) {
//					showToast("Saved: " + mFile);
					CameraUtil.addImageToGallery(getContext(), mFile);
					Log.d(TAG, mFile.toString());
					unlockFocus();
				}
			};

			mCaptureSession.stopRepeating();
			mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onClick(View v) {
		switch (v.getId()) {
			case R.id.btn_capture:
				takePicture();
				break;
			default:
				Log.e(TAG, "no handler");
		}
	}

//	/**
//	 * The below methods were designed for overriding
//	 * **/
//	protected void
//
//	/**
//	 * The below methods were basically used by configuration
//	 * **/
//	public void setDeniedLens(Integer... deniedLens) {
//		mDeniedLens = Arrays.asList(deniedLens);
//	}
//
//	public void setAspectRatio(Size aspectRatio) {
//		mAspectRatio = aspectRatio;
//	}


}
