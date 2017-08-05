package com.gmail.brianbridge.camera2integration;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraFragment extends Fragment {
	public static final String TAG = CameraFragment.class.getSimpleName();
	public static final int REQUEST_CAMERA_PERMISSION = 100;

	private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
			openCamera(width, height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
			return false;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surface) {

		}
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

	// Views
	private AutoFitTextureView mTextureView;
	private Button mCaptureButton;

	// Camera & Preview Controls
	private CameraCaptureSession mCaptureSession;
	private CameraDevice mCameraDevice; // Current opened camera
	private ImageReader mImageReader;
	private CaptureRequest.Builder mPreviewRequestBuilder;
	private CaptureRequest mPreviewRequest;
	private HandlerThread mBackgroundThread;
	private Handler mBackgroundHandler;

	// Camera & Preview Data
	private String mCameraId; // ID of the current CameraDevice
	private int mSensorOrientation;
	private boolean mFlashSupported;
	private Size mPreviewSize;

	// Others
	private Semaphore mCameraOpenCloseLock = new Semaphore(1); // to prevent the app from exiting before closing the camera.


	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_camera, container, false);
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		mTextureView = (AutoFitTextureView) view.findViewById(R.id.textureView);
		mCaptureButton = (Button) view.findViewById(R.id.btn_capture);
	}

	@Override
	public void onResume() {
		super.onResume();
		startBackgroundThread();
		if (mTextureView.isAvailable()) {
			openCamera(mTextureView.getWidth(), mTextureView.getHeight());
		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
	}

	@Override
	public void onPause() {
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	private void requestCameraPermission() {
		if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
			Toast.makeText(getContext(), "Need camera permission, please grant it in app setting.", Toast.LENGTH_SHORT).show();
		} else {
			requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
	}

	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join(); // join to main thread, wait for background thread die
			mBackgroundThread = null;
			mBackgroundHandler = null;
		} catch (InterruptedException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void openCamera(int width, int height) {
		if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			requestCameraPermission();
			return;
		}
//		setUpCameraOutputs(width, height);
//		configureTransform(width, height);
		initCamera(width, height);
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

				// Filter the front camera
				Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
				if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
					continue;
				}

				StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				if (map == null) {
					continue;
				}

				// Get display ratio
				Point displaySize = new Point();
				activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
				Log.d(TAG, "displaySize.x = " + displaySize.x + ", displaySize.y" + displaySize.y);
				float displayRatio = (float) displaySize.x / displaySize.y;
				Log.d(TAG, "displayRatio" + displayRatio);

				// Get the largest output resolution of camera
				List<Size> outputSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
				Collections.sort(outputSizes, new CameraUtil.CompareSizesByArea());
				Size largest = null;
				for (Size size: outputSizes) {
					Log.d(TAG, (float) size.getHeight() / size.getWidth() + " == " + displayRatio);
					if ((float) size.getHeight() / size.getWidth() == displayRatio) {
						largest = size;
						break;
					}
				}
				if (largest == null) {
					largest = Collections.max(
							Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
							new CameraUtil.CompareSizesByArea());
				}

				mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
//				mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

				mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

				int rotatedPreviewWidth = width;
				int rotatedPreviewHeight = height;
				int maxPreviewWidth = displaySize.x;
				int maxPreviewHeight = displaySize.y;

				if (CameraUtil.isScreenNeedRotateForCamera(activity, mSensorOrientation)) {
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

			// Here, we create a CameraCaptureSession for camera preview.
			mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
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
}
