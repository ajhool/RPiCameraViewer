package com.mobilecv.rpi.networkcamera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

import ca.frozen.rpicameraviewer.classes.HttpReader;
import ca.frozen.rpicameraviewer.classes.MulticastReader;
import ca.frozen.rpicameraviewer.classes.NetworkCameraSource;
import ca.frozen.rpicameraviewer.classes.RawH264Reader;
import ca.frozen.rpicameraviewer.classes.Source;
import ca.frozen.rpicameraviewer.classes.SpsParser;
import ca.frozen.rpicameraviewer.classes.TcpIpReader;

/*
 * DecoderThread
 * Responsible for reading in a Video over IP data (specifically h.264 encoded video from the raspberry pi)
 */
public class DecoderThread extends Thread {
  private final static String TAG = "DecoderThread";
  private final static int BUFFER_TIMEOUT = 10000;
  private final static int MULTICAST_BUFFER_SIZE = 16384;
  private final static int TCPIP_BUFFER_SIZE = 16384;
  private final static int HTTP_BUFFER_SIZE = 4096;
  private final static int NAL_SIZE_INC = 4096;
  private final static int MAX_READ_ERRORS = 10000;

  private MediaCodec mDecoder = null;
  private MediaFormat mMediaFormat;
  private boolean mIsDecoding = false;
  private Surface mOutputSurface;
  private Source mSource = null;
  private byte[] mInputBuffer = null;
  private RawH264Reader mH264Reader = null;
  private WifiManager.MulticastLock multicastLock = null;
  private Handler startVideoHandler;
  private Runnable startVideoRunner;

  private NetworkCameraSource mNetworkCameraSource;
  private WifiManager mWifiManager;

  public void setCamera(NetworkCameraSource networkCameraSource) {
    mNetworkCameraSource = networkCameraSource;
  }

  public void setWifiManager(WifiManager wifi){
        mWifiManager = wifi;
    }

  /*
   * A {@Link DecoderListener} listens for a dropped connection to take action.
   *  Most likely action is a standoff to check if there is a reconnection in a certain period of time
   *  before aborting connection attempt.
   */

  public interface DecoderListener {
    void onConnectionFailure();
    void onConnection();
  }

  private DecoderListener mListener;

  public void setListener(DecoderListener listener){
    mListener = listener;
  }

  //******************************************************************************
  // setSurface
  //******************************************************************************
  public void setSurface(Surface surface, Handler handler, Runnable runner) {
    this.mOutputSurface = surface;
    this.startVideoHandler = handler;
    this.startVideoRunner = runner;

    if (mDecoder != null) {
      if (surface != null) {
        boolean newDecoding = mIsDecoding;

        if (mIsDecoding) {
          setDecodingState(false);
        }

        if (mMediaFormat != null) {
          try {
            mDecoder.configure(mMediaFormat, surface, null, 0);
          } catch (Exception ex) {

          }

          if (!newDecoding) {
            newDecoding = true;
          }
        }

        if (newDecoding) {
          setDecodingState(newDecoding);
        }
      } else if (mIsDecoding) {
        setDecodingState(false);
      }
    }
  }

  /*
   * isInitialized
   * Check if the DecoderThread has been properly initialized.
   * TODO://
   */
  private boolean isInitialized(){
    boolean isInitialized = true;

    if(null == this.mOutputSurface || null == this.startVideoHandler || null == this.startVideoRunner){
      isInitialized = false;
    }

    return isInitialized;
  }

  /******************************************************************************
   * getMediaFormat
  ******************************************************************************/
    public MediaFormat getMediaFormat()
    {
        return mMediaFormat;
    }

  /******************************************************************************
   * setDecodingState
   ******************************************************************************/
  private synchronized void setDecodingState(boolean newDecoding) {
    try {

      if (newDecoding != mIsDecoding && mDecoder != null) {

        if (newDecoding) {
          mDecoder.start();
        } else {
          mDecoder.stop();
        }

        mIsDecoding = newDecoding;
      }

    } catch (Exception ex) {
    }
  }

  /******************************************************************************
   * TODO(ajhool) add checks inside run that ensure the camera has been properly initialized.
   ******************************************************************************/
  @Override
  public void run() {
    byte[] nal = new byte[NAL_SIZE_INC];
    int nalLen = 0;
    int numZeroes = 0;
    int numReadErrors = 0;
    long presentationTime = System.nanoTime() / 1000;
    boolean gotSPS = false;
    boolean gotHeader = false;
    ByteBuffer[] inputBuffers = null;

    try {
      // get the multicast lock if necessary
      if (mNetworkCameraSource.source.connectionType == Source.ConnectionType.RawMulticast) {
        if (mWifiManager != null) {
          multicastLock = mWifiManager.createMulticastLock("rpicamlock");
          multicastLock.acquire();
        } else {
          setErrorMessage("Need to set WifiManager for DecoderThread");
        }
      }

      // create the mDecoder
      mDecoder = MediaCodec.createDecoderByType("video/avc");

      // create the mH264Reader
      mSource = mNetworkCameraSource.getCombinedSource();

      if (mSource.connectionType == Source.ConnectionType.RawMulticast) {
        mInputBuffer = new byte[MULTICAST_BUFFER_SIZE];
        mH264Reader = new MulticastReader(mSource);
      } else if (mSource.connectionType == Source.ConnectionType.RawHttp) {
        mInputBuffer = new byte[HTTP_BUFFER_SIZE];
        mH264Reader = new HttpReader(mSource);
      } else {
        mInputBuffer = new byte[TCPIP_BUFFER_SIZE];
        mH264Reader = new TcpIpReader(mSource);
      }

      if (!mH264Reader.isConnected()) {
        throw new Exception();
      }

      // read from the mSource
      while (!Thread.interrupted()) {
        // read from the stream
        int len = mH264Reader.read(mInputBuffer);
        //Log.d(TAG, String.mMediaFormat("len = %d", len));

        // process the input mInputBuffer
        if (len > 0) {
          numReadErrors = 0;
          for (int i = 0; i < len; i++) {
            if (mInputBuffer[i] == 0) {
              numZeroes++;
            } else {
              if (mInputBuffer[i] == 1) {
                if (numZeroes == 3) {
                  if (gotHeader) {
                    nalLen -= numZeroes;
                    if (!gotSPS && (nal[numZeroes + 1] & 0x1F) == 7) {
                      //Log.d(TAG, String.mMediaFormat("SPS: %d = %02X %02X %02X %02X %02X", nalLen, nal[0], nal[1], nal[2], nal[3], nal[4]));
                      SpsParser parser = new SpsParser(nal, nalLen);
                      int width = (mSource.width != 0) ? mSource.width : parser.width;
                      int height = (mSource.height != 0) ? mSource.height : parser.height;
                      //Log.d(TAG, String.mMediaFormat("SPS: size = %d x %d", width, height));
                      mMediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);

                      if (mSource.fps != 0) {
                        mMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mSource.fps);
                      }

                      if (mSource.bps != 0) {
                        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mSource.bps);
                      }

                      mDecoder.configure(mMediaFormat, mOutputSurface, null, 0);
                      setDecodingState(true);
                      inputBuffers = mDecoder.getInputBuffers();
                      startVideoHandler.post(startVideoRunner);
                      gotSPS = true;
                    }

                    if (gotSPS && mIsDecoding) {
                      int index = mDecoder.dequeueInputBuffer(BUFFER_TIMEOUT);

                      if (index >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[index];
                        //ByteBuffer inputBuffer = mDecoder.getInputBuffer(index);
                        inputBuffer.put(nal, 0, nalLen);
                        mDecoder.queueInputBuffer(index, 0, nalLen, presentationTime, 0);
                        presentationTime += 66666;
                      }

                      //Log.d(TAG, String.mMediaFormat("NAL: %d  %d", nalLen, index));
                    }
                  }

                  for (int j = 0; j < numZeroes; j++) {
                    nal[j] = 0;
                  }

                  nalLen = numZeroes;
                  gotHeader = true;
                }
              }

              numZeroes = 0;
            }

            // add the byte to the NAL
            if (gotHeader) {

              if (nalLen == nal.length) {
                nal = Arrays.copyOf(nal, nal.length + NAL_SIZE_INC);
                //Log.d(TAG, String.mMediaFormat("NAL size: %d", nal.length));
              }

              nal[nalLen++] = mInputBuffer[i];
            }
          }
        } else {
          numReadErrors++;

          if (numReadErrors >= MAX_READ_ERRORS) {
            setErrorMessage("Too many read errors, possibly lost connection to camera.");
            break;
          }

          //Log.d(TAG, "len == 0");
        }

        // send an output mInputBuffer to the mOutputSurface
        if (mMediaFormat != null && mIsDecoding) {
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
          int index = mDecoder.dequeueOutputBuffer(info, BUFFER_TIMEOUT);
          if (index >= 0) {
            mDecoder.releaseOutputBuffer(index, true);
          }
        }
      }
    } catch (Exception ex) {
      if (mH264Reader == null || !mH264Reader.isConnected()) {
        setErrorMessage("Couldn't connect to mH264Reader. PiCamConnectionFragment should finish.");

        //TODO: Gracefully handle this error
        if(null != mListener) {
          mListener.onConnectionFailure();
        }
      } else {
        setErrorMessage("Lost connection.");
      }

      //Log.d(TAG, ex.toString());
      ex.printStackTrace();
    }

    // close the mH264Reader
    if (mH264Reader != null) {
      try {
        mH264Reader.close();
      } catch (Exception ex) {
      }

      mH264Reader = null;
    }

    // stop the mDecoder
    if (mDecoder != null) {
      try {
        setDecodingState(false);
        mDecoder.release();
      } catch (Exception ex) {

      }

      mDecoder = null;
    }

    // release the multicast lock
    if (multicastLock != null) {
      try {
        if (multicastLock.isHeld()) {
          multicastLock.release();
        }
      } catch (Exception ex) {

      }

      multicastLock = null;
    }
  }

  private void setErrorMessage(String message){
    Log.e(TAG, message);
  }
}