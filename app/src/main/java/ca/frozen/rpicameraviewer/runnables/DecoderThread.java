package ca.frozen.rpicameraviewer.runnables;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

import ca.frozen.rpicameraviewer.activities.PiCamConnectionFragment;
import ca.frozen.rpicameraviewer.classes.Camera;
import ca.frozen.rpicameraviewer.classes.HttpReader;
import ca.frozen.rpicameraviewer.classes.MulticastReader;
import ca.frozen.rpicameraviewer.classes.RawH264Reader;
import ca.frozen.rpicameraviewer.classes.Source;
import ca.frozen.rpicameraviewer.classes.SpsParser;
import ca.frozen.rpicameraviewer.classes.TcpIpReader;

/*
 * DecoderThread
 * Responsible for reading in a Video over IP datastream (specifically h.264 encoded video the raspberry pi)
 * Must call
 */
public class DecoderThread extends Thread {
    // local constants
    private final static String TAG = "DecoderThread";
    private final static int BUFFER_TIMEOUT = 10000;
    private final static int MULTICAST_BUFFER_SIZE = 16384;
    private final static int TCPIP_BUFFER_SIZE = 16384;
    private final static int HTTP_BUFFER_SIZE = 4096;
    private final static int NAL_SIZE_INC = 4096;
    private final static int MAX_READ_ERRORS = 10000;

    // instance variables
    private MediaCodec decoder = null;
    private MediaFormat format;
    private boolean decoding = false;
    private Surface surface;
    private Source source = null;
    private byte[] buffer = null;
    private RawH264Reader reader = null;
    private WifiManager.MulticastLock multicastLock = null;
    private Handler startVideoHandler;
    private Runnable startVideoRunner;

    private PiCamConnectionFragment mFragment;

    private Camera mCamera;
    private WifiManager mWifiManager;

    public void setCamera(Camera camera) {
        mCamera = camera;
    }

    public void setWifiManager(WifiManager wifi){
        mWifiManager = wifi;
    }

    public void setParentFragment(PiCamConnectionFragment fragment){
        mFragment = fragment;
    }

    //******************************************************************************
    // setSurface
    //******************************************************************************
    public void setSurface(Surface surface, Handler handler, Runnable runner) {
        this.surface = surface;
        this.startVideoHandler = handler;
        this.startVideoRunner = runner;
        if (decoder != null) {
            if (surface != null) {
                boolean newDecoding = decoding;

                if (decoding) {
                    setDecodingState(false);
                }

                if (format != null) {
                    try {
                        decoder.configure(format, surface, null, 0);
                    } catch (Exception ex) {

                    }

                    if (!newDecoding) {
                        newDecoding = true;
                    }
                }

                if (newDecoding) {
                    setDecodingState(newDecoding);
                }
            } else if (decoding) {
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

        if(null == this.surface  || null == this.startVideoHandler || null == this.startVideoRunner){
            isInitialized = false;
        }

        return isInitialized;
    }

    //******************************************************************************
    // getMediaFormat
    //******************************************************************************
    public MediaFormat getMediaFormat()
    {
        return format;
    }

    //******************************************************************************
    // setDecodingState
    //******************************************************************************
    private synchronized void setDecodingState(boolean newDecoding)
    {
        try {
            if (newDecoding != decoding && decoder != null) {

                if (newDecoding) {
                    decoder.start();
                } else {
                    decoder.stop();
                }

                decoding = newDecoding;
            }
        } catch (Exception ex) {

        }
    }

    /******************************************************************************
     * TODO(ajhool) add checks inside run that ensure the camera has been properly initialized.
    ******************************************************************************/
    @Override
    public void run()
    {
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
            if (mCamera.source.connectionType == Source.ConnectionType.RawMulticast) {
                if (mWifiManager != null) {
                    multicastLock = mWifiManager.createMulticastLock("rpicamlock");
                    multicastLock.acquire();
                } else {
                    setMessage("Need to set WifiManager for DecoderThread");
                }
            }

            // create the decoder
            decoder = MediaCodec.createDecoderByType("video/avc");

            // create the reader
            source = mCamera.getCombinedSource();

            if (source.connectionType == Source.ConnectionType.RawMulticast) {
                buffer = new byte[MULTICAST_BUFFER_SIZE];
                reader = new MulticastReader(source);
            } else if (source.connectionType == Source.ConnectionType.RawHttp) {
                buffer = new byte[HTTP_BUFFER_SIZE];
                reader = new HttpReader(source);
            } else {
                buffer = new byte[TCPIP_BUFFER_SIZE];
                reader = new TcpIpReader(source);
            }

            if (!reader.isConnected()) {
                throw new Exception();
            }

            // read from the source
            while (!Thread.interrupted()) {
                // read from the stream
                int len = reader.read(buffer);
                //Log.d(TAG, String.format("len = %d", len));

                // process the input buffer
                if (len > 0) {
                    numReadErrors = 0;
                    for (int i = 0; i < len; i++) {
                        if (buffer[i] == 0) {
                            numZeroes++;
                        } else {
                            if (buffer[i] == 1) {
                                if (numZeroes == 3) {
                                    if (gotHeader) {
                                        nalLen -= numZeroes;
                                        if (!gotSPS && (nal[numZeroes + 1] & 0x1F) == 7) {
                                            //Log.d(TAG, String.format("SPS: %d = %02X %02X %02X %02X %02X", nalLen, nal[0], nal[1], nal[2], nal[3], nal[4]));
                                            SpsParser parser = new SpsParser(nal, nalLen);
                                            int width = (source.width != 0) ? source.width : parser.width;
                                            int height = (source.height != 0) ? source.height : parser.height;
                                            //Log.d(TAG, String.format("SPS: size = %d x %d", width, height));
                                            format = MediaFormat.createVideoFormat("video/avc", width, height);

                                            if (source.fps != 0) {
                                                format.setInteger(MediaFormat.KEY_FRAME_RATE, source.fps);
                                            }

                                            if (source.bps != 0) {
                                                format.setInteger(MediaFormat.KEY_BIT_RATE, source.bps);
                                            }
                                            decoder.configure(format, surface, null, 0);
                                            setDecodingState(true);
                                            inputBuffers = decoder.getInputBuffers();
                                            startVideoHandler.post(startVideoRunner);
                                            gotSPS = true;
                                        } if (gotSPS && decoding) {
                                            int index = decoder.dequeueInputBuffer(BUFFER_TIMEOUT);
                                            if (index >= 0) {
                                                ByteBuffer inputBuffer = inputBuffers[index];
                                                //ByteBuffer inputBuffer = decoder.getInputBuffer(index);
                                                inputBuffer.put(nal, 0, nalLen);
                                                decoder.queueInputBuffer(index, 0, nalLen, presentationTime, 0);
                                                presentationTime += 66666;
                                            }
                                            //Log.d(TAG, String.format("NAL: %d  %d", nalLen, index));
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
                                //Log.d(TAG, String.format("NAL size: %d", nal.length));
                            }
                            nal[nalLen++] = buffer[i];
                        }
                    }
                } else {
                    numReadErrors++;
                    if (numReadErrors >= MAX_READ_ERRORS) {
                        setMessage("Too many read errors, possibly lost connection to camera.");
                        break;
                    }
                    //Log.d(TAG, "len == 0");
                }

                // send an output buffer to the surface
                if (format != null && decoding) {
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int index = decoder.dequeueOutputBuffer(info, BUFFER_TIMEOUT);
                    if (index >= 0) {
                        decoder.releaseOutputBuffer(index, true);
                    }
                }
            }
        } catch (Exception ex) {
            if (reader == null || !reader.isConnected()) {
                setMessage("Couldn't connect to reader. PiCamConnectionFragment should finish.");

                //TODO: Gracefully handle this error
                mFragment.noConnection();
            } else {
                setMessage("Lost connection.");
            }
            //Log.d(TAG, ex.toString());
            ex.printStackTrace();
        }

        // close the reader
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ex) {

            }

            reader = null;
        }

        // stop the decoder
        if (decoder != null) {
            try {
                setDecodingState(false);
                decoder.release();
            } catch (Exception ex) {

            }

            decoder = null;
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

    private void setMessage(String message){
        Log.e(TAG, message);
        //mFragment.setMessage(R.string.);
    }
}