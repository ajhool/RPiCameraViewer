/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
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

//TODO: How to properly manage these licenses?
// Copyright © 2016 Shawn Baker using the MIT License.
// Copyright © 2017 Aidan Hoolachan using the MIT License.

package ca.frozen.rpicameraviewer.activities;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import org.tensorflow.demo.env.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/*
 * Imports below this line are from app
 */
import ca.frozen.rpicameraviewer.App;
import ca.frozen.rpicameraviewer.classes.NetworkCameraSource;
import ca.frozen.rpicameraviewer.runnables.DecoderThread;
import ca.frozen.rpicameraviewer.views.ZoomPanTextureView;

import android.support.v4.app.Fragment;

import ca.frozen.rpicameraviewer.R;
import ca.frozen.rpicameraviewer.classes.Utils;

public class PiCamConnectionFragment extends Fragment implements TextureView.SurfaceTextureListener {
  private static final Logger LOGGER = new Logger();

  /**
   * The networkCameraSource preview size will be chosen to be the smallest frame by pixel size capable of
   * containing a DESIRED_SIZE x DESIRED_SIZE square.
   */
  private static final int MINIMUM_PREVIEW_SIZE = 320;

  /**
   * Conversion from screen rotation to JPEG orientation.
   */
  private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
  private static final String FRAGMENT_DIALOG = "dialog";

  /*
   * Bundle Argument constants
   */
  public final static String CAMERA = "networkCameraSource";
  public final static String FULL_SCREEN = "full_screen";
  public final static String LAYOUT = "layout";
  public final static String INPUT_SIZE = "input_size";

  private final static int FINISH_TIMEOUT = 5000;

  /*
   * Zoom Texture View constants
   */
  private final static float MIN_ZOOM = 0.1f;
  private final static float MAX_ZOOM = 10;

  /*
   * Permission to save files to permanent disk.
   */
  private final static int REQUEST_WRITE_EXTERNAL_STORAGE = 1;

  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 90);
    ORIENTATIONS.append(Surface.ROTATION_90, 0);
    ORIENTATIONS.append(Surface.ROTATION_180, 270);
    ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  /**
   * ID of the current {@link CameraDevice}.
   */
  private String cameraId;

  /**
   //* An {@link AutoFitTextureView} for networkCameraSource preview.
   */
  //private AutoFitTextureView textureView;

  /**
   * A {@link CameraCaptureSession } for networkCameraSource preview.
   */
  private CameraCaptureSession captureSession;

  /**
   * A reference to the opened {@link CameraDevice}.
   */
  private CameraDevice cameraDevice;

  /**
   * The rotation in degrees of the networkCameraSource sensor from the display.
   */
  private Integer sensorOrientation;

  /**
   * The {@link Size} of networkCameraSource preview.
   */
  private Size previewSize;

  /**
   * An additional thread for running tasks that shouldn't block the UI.
   */
  private HandlerThread backgroundThread;

  /**
   * A {@link Handler} for running tasks in the background.
   */
  private Handler backgroundHandler;

  /**
   * An {@link ImageReader} that handles preview frame capture.
   */
  private ImageReader previewReader;

  /**
   * {@link CaptureRequest.Builder} for the networkCameraSource preview
   */
  private CaptureRequest.Builder previewRequestBuilder;

  /**
   * {@link CaptureRequest} generated by {@link #previewRequestBuilder}
   */
  private CaptureRequest previewRequest;

  /**
   * A {@link Semaphore} to prevent the app from exiting before closing the networkCameraSource.
   */
  private final Semaphore cameraOpenCloseLock = new Semaphore(1);

  /**
   * A {@link OnImageAvailableListener} to receive frames as they are available.
   */
  private OnImageAvailableListener imageListener;

  /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
  private Size inputSize;

  /**
   * The layout identifier to inflate for this Fragment.
   * Each detector activity has a slightly different layout
   * @TODO(ajhool): This is stupid -- the activity should handle this. Get rid of it.
   */
  private int layout;


  /*
   * From RPi VideoFragment
   */
  /*
   * @networkCameraSource represents the picamera object
   */
  private NetworkCameraSource networkCameraSource;
  private boolean fullScreen;
  private DecoderThread decoder;
  private ZoomPanTextureView zoomTextureView;
  private Runnable finishRunner, startVideoRunner;
  private Handler finishHandler, startVideoHandler;

  /**
   * Callback for Activities to use to initialize their data once the
   * selected preview size is known.
   */
  public interface ConnectionCallback {
    void onPreviewSizeChosen(Size size, int cameraRotation);
  }

  /**
   * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
   * {@link TextureView}.
   */
  //TODO: Currently not used. Either remove, move, or integrate.
  private final TextureView.SurfaceTextureListener surfaceTextureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable( final SurfaceTexture texture, final int width, final int height) {
          //openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture texture, final int width, final int height) {
          configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
          return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture texture) {}
      };


  /*
   * @cameraConnectionCallback informs listeners when the characteristics of the selected networkCameraSource have been determined
   */
  private ConnectionCallback cameraConnectionCallback;

  /*
   * Required default constructor used by the Android OS. Use newInstance to instantiate from application.
   */
  public PiCamConnectionFragment() {
  }

  /*
   * Instantiate the Fragment that connects the PiCam to the TensorFlow processing.
   */
  public static PiCamConnectionFragment newInstance(
      final int layout,
      final Size inputSize,
      final NetworkCameraSource networkCameraSource,
      final boolean fullScreen) {
    PiCamConnectionFragment piCamConnectionFragment = new PiCamConnectionFragment();

    Bundle args = new Bundle();
    args.putParcelable(CAMERA, networkCameraSource);
    args.putBoolean(FULL_SCREEN, fullScreen);
    args.putInt(LAYOUT, layout);
    args.putSize(INPUT_SIZE, inputSize);

    piCamConnectionFragment.setArguments(args);

    return piCamConnectionFragment;
  }

  /**
   * Given {@code choices} of {@code Size}s supported by a networkCameraSource, chooses the smallest one whose
   * width and height are at least as large as the minimum of both, or an exact match if possible.
   *
   * @param choices The list of sizes that the networkCameraSource supports for the intended output class
   * @param width The minimum desired width
   * @param height The minimum desired height
   * @return The optimal {@code Size}, or an arbitrary one if none were big enough
   */
  private static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
    final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
    final Size desiredSize = new Size(width, height);

    // Collect the supported resolutions that are at least as big as the preview Surface
    boolean exactSizeFound = false;
    final List<Size> bigEnough = new ArrayList<Size>();
    final List<Size> tooSmall = new ArrayList<Size>();
    for (final Size option : choices) {
      if (option.equals(desiredSize)) {
        // Set the size but don't return yet so that remaining sizes will still be logged.
        exactSizeFound = true;
      }

      if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
        bigEnough.add(option);
      } else {
        tooSmall.add(option);
      }
    }

    LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
    LOGGER.i("Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
    LOGGER.i("Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

    if (exactSizeFound) {
      LOGGER.i("Exact size match found.");
      return desiredSize;
    }

    // Pick the smallest of those, assuming we found any
    if (bigEnough.size() > 0) {
      final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
      LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
      return chosenSize;
    } else {
      LOGGER.e("Couldn't find any suitable preview size");
      return choices[0];
    }
  }

  //******************************************************************************
  // onCreate
  //******************************************************************************
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    // configure the activity
    super.onCreate(savedInstanceState);

    // load the settings and cameras
    Utils.loadData();

    // get the parameters
    networkCameraSource = getArguments().getParcelable(CAMERA);
    fullScreen = getArguments().getBoolean(FULL_SCREEN);
    layout = getArguments().getInt(LAYOUT);
      inputSize = getArguments().getSize(INPUT_SIZE);


      // create the finish handler and runnable
    finishHandler = new Handler();
    finishRunner = new Runnable()
    {
      @Override
      public void run()
      {
        getActivity().finish();
      }
    };

    // create the start video handler and runnable
    startVideoHandler = new Handler();
    startVideoRunner = new Runnable() {
      @Override
      public void run()
      {
        //MediaFormat format = decoder.getMediaFormat();
        //int videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        //int videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        //zoomTextureView.setVideoSize(videoWidth, videoHeight);
      }
    };
  }

  @Override
  public View onCreateView(
      final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

    //TODO: Choose a view
    //View view = inflater.inflate(ca.frozen.picamviewer.R.layout.fragment_video, container, false);
    View view = inflater.inflate(layout, container, false);

      /*
       * TODO: initialization of zoomTextureView might need to move to onViewCreated
       */

    // set the texture listener
    zoomTextureView = (ZoomPanTextureView) view.findViewById(R.id.texture);
    zoomTextureView.setSurfaceTextureListener(this);
    zoomTextureView.setZoomRange(MIN_ZOOM, MAX_ZOOM);
    zoomTextureView.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent e)
      {
        switch (e.getAction())
        {
          case MotionEvent.ACTION_DOWN:
            break;
          case MotionEvent.ACTION_UP:
            if (e.getPointerCount() == 1)
            {
            }
            break;
        }
        return false;
      }
    });

    // move the snapshot button over to account for the navigation bar
    if (fullScreen)
    {
      float scale = getContext().getResources().getDisplayMetrics().density;
      int margin = (int)(5 * scale + 0.5f);
      int extra = Utils.getNavigationBarHeight(getContext(), Configuration.ORIENTATION_LANDSCAPE);

        //ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) snapshotButton.getLayoutParams();
      //lp.setMargins(margin, margin, margin + extra, margin);
    }

    return view;
  }

  @Override
  public void onViewCreated(final View view, final Bundle savedInstanceState) {
    //textureView = (AutoFitTextureView) view.findViewById(org.tensorflow.demo.R.id.texture);
  }

  @Override
  public void onActivityCreated(final Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
  }

  //******************************************************************************
  // onSurfaceTextureAvailable
  //******************************************************************************
  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height)
  {
    if (decoder != null)
    {
      //decoder.setSurface(new Surface(surfaceTexture), startVideoHandler, startVideoRunner);
    }
  }

  //******************************************************************************
  // onSurfaceTextureSizeChanged
  //******************************************************************************
  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height)
  {
  }

  //******************************************************************************
  // onSurfaceTextureDestroyed
  //******************************************************************************
  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
  {
    if (decoder != null)
    {
      //decoder.setSurface(null, null, null);
    }
    return true;
  }

  //******************************************************************************
  // onSurfaceTextureUpdated
  //******************************************************************************
  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
  {
  }

  public void noConnection(){
    finishHandler.postDelayed(finishRunner, FINISH_TIMEOUT);
  }

  @Override
  public void onResume() {
    super.onResume();
    startBackgroundThread();

    Size[] sizes = new Size[]{new Size(1280, 720)};

    previewSize = chooseOptimalSize(sizes, inputSize.getWidth(), inputSize.getHeight());
    //TODO(ajhool): Remove hardware of sensorOrientation to al;
    sensorOrientation = Configuration.ORIENTATION_LANDSCAPE;
    cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);

    // Create the reader for the preview frames.
    previewReader =
            ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

    previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);

    decoder.setSurface(previewReader.getSurface(), startVideoHandler, startVideoRunner);
    decoder.start();

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a networkCameraSource and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (zoomTextureView.isAvailable()) {
      //openCamera(zoomTextureView.getWidth(), zoomTextureView.getHeight());
    } else {
      zoomTextureView.setSurfaceTextureListener(this);
    }
  }

  @Override
  public void onPause() {
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  @Override
  public void onAttach(Context context){
      super.onAttach(context);
      if(context instanceof OnImageAvailableListener) {
          this.imageListener = (OnImageAvailableListener) context;
      } else {
          throw new RuntimeException(context.toString()
                  + " must implement OnImageAvailableListener");
      }

      if (context instanceof ConnectionCallback) {
          this.cameraConnectionCallback = (ConnectionCallback) context;
      } else {
          throw new RuntimeException(context.toString()
                  + " must implement ConnectionCallback");
      }
  }

  @Override
  public void onDetach(){
      super.onDetach();

      cameraConnectionCallback = null;
      imageListener = null;
  }

  @Override
  public void onStart(){
    super.onStart();

    // create the decoder thread
    decoder = new DecoderThread();

    final WifiManager wifi = (WifiManager) getActivity().getApplicationContext().getSystemService(App.getContext().WIFI_SERVICE);
    decoder.setWifiManager(wifi);
    decoder.setCamera(networkCameraSource);
    decoder.setParentFragment(this);
  }

  @Override
  public void onStop(){
    super.onStop();

    if (decoder != null)
    {
      decoder.interrupt();
      decoder = null;
    }
  }

  //******************************************************************************
  // onDestroy
  //******************************************************************************
  @Override
  public void onDestroy()
  {
    super.onDestroy();
    finishHandler.removeCallbacks(finishRunner);
  }

  /**
   * Sets up member variables related to networkCameraSource.
   *
   * @param width  The width of available size for networkCameraSource preview
   * @param height The height of available size for networkCameraSource preview
   */
  private void setUpCameraOutputs(final int width, final int height) {
    final Activity activity = getActivity();
    //final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      //for (final String cameraId : manager.getCameraIdList()) {
        //final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing networkCameraSource in this sample.
       // final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        //if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
        //  continue;
       // }

        //final StreamConfigurationMap map =
        //    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

       // if (map == null) {
       //   continue;
       // }

        // For still image captures, we use the largest available size.
       // final Size largest =
         //   Collections.max(
          //      Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
           //     new CompareSizesByArea());

        //sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the networkCameraSource
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.
        //previewSize =
        //    chooseOptimalSize(
        //        map.getOutputSizes(SurfaceTexture.class),
        //        inputSize.getWidth(),
        //        inputSize.getHeight());

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        final int orientation = getResources().getConfiguration().orientation;

        //TODO(ajhool): Remove hardware of sensorOrientation to equal orientation;
        sensorOrientation = orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          zoomTextureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
          zoomTextureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }

        //PiCamConnectionFragment.this.cameraId = cameraId;
      //}
    //} catch (final CameraAccessException e) {
    //  LOGGER.e(e, "Exception!");
    } catch (final NullPointerException e) {
      // Currently an NPE is thrown when the Camera2API is used but not supported on the
      // device this code runs.
      // TODO(andrewharp): abstract ErrorDialog/RuntimeException handling out into new method and
      // reuse throughout app.

      //TODO(ajhool)
      //ErrorDialog.newInstance(getString(org.tensorflow.demo.R.string.camera_error))
      //    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
      throw new RuntimeException(getString(org.tensorflow.demo.R.string.camera_error));
    }

    cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
  }

  /**
   * Opens the networkCameraSource specified by {@link PiCamConnectionFragment#cameraId}.
   */
  private void openCamera(final int width, final int height) {
    setUpCameraOutputs(width, height);
    configureTransform(width, height);
    final Activity activity = getActivity();
    final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Time out waiting to lock networkCameraSource opening.");
      }
      //manager.openCamera(cameraId, stateCallback, backgroundHandler);
    } catch (final InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock networkCameraSource opening.", e);
    }
  }

  /**
   * Closes the current {@link CameraDevice}.
   */
  private void closeCamera() {
    try {
      cameraOpenCloseLock.acquire();
      if (null != captureSession) {
        captureSession.close();
        captureSession = null;
      }
      if (null != cameraDevice) {
        cameraDevice.close();
        cameraDevice = null;
      }
      if (null != previewReader) {
        previewReader.close();
        previewReader = null;
      }
    } catch (final InterruptedException e) {
      throw new RuntimeException("Interrupted while trying to lock networkCameraSource closing.", e);
    } finally {
      cameraOpenCloseLock.release();
    }
  }

  /**
   * Starts a background thread and its {@link Handler}.
   */
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("ImageListener");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  /**
   * Stops the background thread and its {@link Handler}.
   */
  private void stopBackgroundThread() {
    backgroundThread.quitSafely();
    try {
      backgroundThread.join();
      backgroundThread = null;
      backgroundHandler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  private final CameraCaptureSession.CaptureCallback captureCallback =
      new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(
            final CameraCaptureSession session,
            final CaptureRequest request,
            final CaptureResult partialResult) {}

        @Override
        public void onCaptureCompleted(
            final CameraCaptureSession session,
            final CaptureRequest request,
            final TotalCaptureResult result) {}
      };

  /**
   * Creates a new {@link CameraCaptureSession} for networkCameraSource preview.
   */
  private void createCameraPreviewSession() {
    try {
      final SurfaceTexture texture = zoomTextureView.getSurfaceTexture();
      assert texture != null;

      // We configure the size of default buffer to be the size of networkCameraSource preview we want.
      texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

      // This is the output Surface we need to start preview.
      final Surface surface = new Surface(texture);

      // We set up a CaptureRequest.Builder with the output Surface.
      previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      previewRequestBuilder.addTarget(surface);

      LOGGER.i("Opening networkCameraSource preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

      // Create the reader for the preview frames.
      previewReader =
          ImageReader.newInstance(
              previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

      previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
      previewRequestBuilder.addTarget(previewReader.getSurface());

      // Here, we create a CameraCaptureSession for networkCameraSource preview.
      cameraDevice.createCaptureSession(
          Arrays.asList(surface, previewReader.getSurface()),
          new CameraCaptureSession.StateCallback() {

            @Override
            public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
              // The networkCameraSource is already closed
              if (null == cameraDevice) {
                return;
              }

              // When the session is ready, we start displaying the preview.
              captureSession = cameraCaptureSession;
              try {
                // Auto focus should be continuous for networkCameraSource preview.
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // Flash is automatically enabled when necessary.
                previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                // Finally, we start displaying the networkCameraSource preview.
                previewRequest = previewRequestBuilder.build();
                captureSession.setRepeatingRequest(
                    previewRequest, captureCallback, backgroundHandler);
              } catch (final CameraAccessException e) {
                LOGGER.e(e, "Exception!");
              }
            }

            @Override
            public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
              showToast("Failed");
            }
          },
          null);
    } catch (final CameraAccessException e) {
      LOGGER.e(e, "Exception!");
    }
  }

  /**
   * Configures the necessary {@link Matrix} transformation to `mTextureView`.
   * This method should be called after the networkCameraSource preview size is determined in
   * setUpCameraOutputs and also the size of `mTextureView` is fixed.
   *
   * @param viewWidth  The width of `mTextureView`
   * @param viewHeight The height of `mTextureView`
   */
  private void configureTransform(final int viewWidth, final int viewHeight) {
    final Activity activity = getActivity();
    if (null == zoomTextureView || null == previewSize || null == activity) {
      return;
    }
    final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    final Matrix matrix = new Matrix();
    final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
    final float centerX = viewRect.centerX();
    final float centerY = viewRect.centerY();
    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
      bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
      matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
      final float scale =
          Math.max(
              (float) viewHeight / previewSize.getHeight(),
              (float) viewWidth / previewSize.getWidth());
      matrix.postScale(scale, scale, centerX, centerY);
      matrix.postRotate(90 * (rotation - 2), centerX, centerY);
    } else if (Surface.ROTATION_180 == rotation) {
      matrix.postRotate(180, centerX, centerY);
    }
    zoomTextureView.setTransform(matrix);
  }

  /**
   * Compares two {@code Size}s based on their areas.
   */
  static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(final Size lhs, final Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  /****
   * Non-core functions have been placed below this line while merging Fragments.
   ****/
//TODO: Possibly wrap these functions into another class to reduce size. Otherwise, these need to be placed back inside the main class

  /**
   * Shows a {@link Toast} on the UI thread.
   *
   * @param text The message to show
   */
  private void showToast(final String text) {
    final Activity activity = getActivity();
    if (activity != null) {
      activity.runOnUiThread(
              new Runnable() {
                @Override
                public void run() {
                  Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
              });
    }
  }
}