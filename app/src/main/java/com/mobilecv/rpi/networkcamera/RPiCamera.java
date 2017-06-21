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

package com.mobilecv.rpi.networkcamera;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import org.tensorflow.demo.env.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ca.frozen.rpicameraviewer.App;
import ca.frozen.rpicameraviewer.R;
import ca.frozen.rpicameraviewer.classes.NetworkCameraSource;
import ca.frozen.rpicameraviewer.classes.Utils;
import ca.frozen.rpicameraviewer.views.ZoomPanTextureView;

/*
 * Imports below this line are from app
 */

public class RPiCamera extends Fragment {
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

  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 90);
    ORIENTATIONS.append(Surface.ROTATION_90, 0);
    ORIENTATIONS.append(Surface.ROTATION_180, 270);
    ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

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
   * @networkCameraSource holds characteristics of a network camera source
   *
   */
  private NetworkCameraSource networkCameraSource;

  /*
   * @fullScreen flag for activating fullscreen mode.
   */
  private boolean fullScreen;

  /*
   * @decoder Background thread for decoding h264 data from the network camera.
   *  Requires a thread for
   */
  private DecoderThread decoder;

  /*
   * @zoomTexureView displays the Network camera video with the ability to zoom in and pan.
   * TODO: Untested
   */
  private ZoomPanTextureView zoomTextureView;

  /*
   * @startVideoRunner : Ensures that the network camera is properly started
   * @finishRunner : Ensures that the Network camera is properly shutdown
   */
  //private Runnable //startVideoRunner, finishRunner;
  //private Handler startVideoHandler;//, finishHandler;

  /**
   * Instances of static inner classes do not hold an implicit
   * reference to their outer class.
   */
  private static class FinishDecodeHandler extends Handler {
    private final WeakReference<RPiCamera> mFragment;

    public FinishDecodeHandler(RPiCamera fragment) {
      mFragment = new WeakReference<RPiCamera>(fragment);
    }
  }

  private final FinishDecodeHandler mFinishDecodeHandler = new FinishDecodeHandler(this);

  /**
   * Instances of static inner classes do not hold an implicit
   * reference to their outer class.
   */
  private static class StartDecodeHandler extends Handler {
    private final WeakReference<RPiCamera> mFragment;

    public StartDecodeHandler(RPiCamera fragment) {
      mFragment = new WeakReference<RPiCamera>(fragment);
    }
  }

  private final StartDecodeHandler mStartDecodeHandler = new StartDecodeHandler(this);

  /**
   * Instances of anonymous classes do not hold an implicit
   * reference to their outer class when they are "static".
   */
  private static final Runnable mStartVideoRunnable = new Runnable() {
    @Override
    public void run() {
      //MediaFormat format = decoder.getMediaFormat();
      //int videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
      //int videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
      //zoomTextureView.setVideoSize(videoWidth, videoHeight);
    }
  };

  /**
   * Callback for Activities to use to initialize their data once the
   * selected preview size is known.
   */
  public interface ConnectionCallback {
    void onPreviewSizeChosen(Size size, int cameraRotation);
  }

  /*
   * @cameraConnectionCallback informs listeners when the characteristics of the selected networkCameraSource have been determined
   */
  private ConnectionCallback cameraConnectionCallback;

  /*
   * Required default constructor used by the Android OS. Use newInstance to instantiate from application.
   */
  public RPiCamera() {
  }

  /*
   * @newInstance Instantiate the Fragment that connects the PiCam to the TensorFlow processing.
   * @param layout The layout for the fragment to use
   *  //TODO: Change architecture so that layout is a property of container activity
   * @param networkCameraSource Information for connecting to the network camera source, like IP, port, etc.
   * @param fullScreen Flag for the fragment to take fullscreen
   *
   * @return The created {@code PiCamConnectionFragment}
   */
  public static RPiCamera newInstance(
      final int layout,
      final Size inputSize,
      final NetworkCameraSource networkCameraSource,
      final boolean fullScreen) {
    RPiCamera piCamConnectionFragment = new RPiCamera();

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
  }

  @Override
  public View onCreateView( final LayoutInflater inflater,
                            final ViewGroup container,
                            final Bundle savedInstanceState) {

    View view = inflater.inflate(layout, container, false);
    return view;
  }

  @Override
  public void onViewCreated(final View view, final Bundle savedInstanceState) {
    zoomTextureView = (ZoomPanTextureView) view.findViewById(R.id.texture);
    //Note: This is the proper place to setSurfaceTextureListener, but is not currently used.
    //zoomTextureView.setSurfaceTextureListener(this);
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
  }

  @Override
  public void onActivityCreated(final Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
  }

  @Override
  public void onResume() {
    super.onResume();
    startBackgroundThread();

    //TODO: original TF implementation chooses an optimal size to feed into tensorflow. Each model
    //    desires a different sized input. Should have a way to decimate surfaces down to their proper
    //    sizes before feeding into models. Currently Hardcoding values from sensor @1280x720
    //
    // Original implementation (sort of -- originally, the camera sensor is queried for available sizes):
    // Size[] sizes = new Size[]{new Size(1280, 720)};
    // previewSize = chooseOptimalSize(sizes, inputSize.getWidth(), inputSize.getHeight());

    //TODO(ajhool): Remove hardware of sensorOrientation to al;
    sensorOrientation = Configuration.ORIENTATION_LANDSCAPE;

    Size sensorSize = new Size(1280, 720);
    previewSize = sensorSize;
    cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);

    // Create the reader for the preview frames.
    previewReader =
            ImageReader.newInstance(
                    sensorSize.getWidth(), sensorSize.getHeight(), ImageFormat.YUV_420_888, 2);

    previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
    
    decoder.setSurface(previewReader.getSurface(), mStartDecodeHandler, mStartVideoRunnable);
    decoder.start();

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a networkCameraSource and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (zoomTextureView.isAvailable()) {
      //openCamera(zoomTextureView.getWidth(), zoomTextureView.getHeight());
    } else {
      //TODO: if this becomes a SurfaceTextureListener, add it here, again.
      //zoomTextureView.setSurfaceTextureListener(this);
    }
  }

  @Override
  public void onPause() {
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

    destroyCallbacks();
  }

  private void destroyCallbacks(){
    cameraConnectionCallback = null;
    imageListener = null;
  }

  @Override
  public void onStart(){
    super.onStart();

    // create the decoder thread
    decoder = new DecoderThread();

    final WifiManager wifi = (WifiManager) getActivity().getApplicationContext().getSystemService(App.getContext().WIFI_SERVICE);

    initializeDecoderThread(wifi);
  }

  private DecoderThread.DecoderListener d = new DecoderThread.DecoderListener() {
    @Override
    public void onConnectionFailure() {
      getActivity().finish();
    }

    @Override
    public void onConnection() {

    }
  };

  private void initializeDecoderThread(WifiManager wifi){
    decoder = new DecoderThread();

    decoder.setWifiManager(wifi);
    decoder.setCamera(networkCameraSource);
    decoder.setListener(this);
  }


  @Override
  public void onStop(){
    super.onStop();

    destroyDecoderThread();
  }

  private void destroyDecoderThread(){
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
    //mFinishDecodeHandler.removeCallbacks(finishRunner);
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
}