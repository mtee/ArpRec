package at.timofeev.arcore.sessionRecorder.helpers;

import android.graphics.Rect;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import java.io.File;
import java.io.IOException;


public class VideoRecorder {

    private VideoRecorderListener listener;
    private VideoEncoderCore mEncoderCore;
    private Rect mVideoRect;
    public static int DEFAULT_BITRATE = 20000000;

    private CaptureContext mEncoderContext;

    private boolean mRecording = false;
    private TextureMovieEncoder2 mVideoEncoder;
    private EGLConfig mEGLConfig;

    public VideoRecorder(int width, int height, int bitrate, File outputFile,
                         VideoRecorderListener _listener) throws IOException {
        this.listener = _listener;
        mEncoderCore = new VideoEncoderCore(width, height, bitrate, outputFile);
        mVideoRect = new Rect(0,0,width,height);
    }



    public CaptureContext startCapture() {

        if (mVideoEncoder == null) {
            return null;
        }

        if (mEncoderContext == null) {
            mEncoderContext = new CaptureContext();
            mEncoderContext.windowDisplay = EGL14.eglGetCurrentDisplay();

            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };

            mEncoderContext.windowDrawSurface = EGL14.eglCreateWindowSurface(
                    mEncoderContext.windowDisplay,
                    mEGLConfig,mEncoderCore.getInputSurface(),
                    surfaceAttribs, 0);
            mEncoderContext.windowReadSurface = mEncoderContext.windowDrawSurface;
        }

        CaptureContext displayContext = new CaptureContext();
        displayContext.initialize();

        // Draw for recording, swap.
        mVideoEncoder.frameAvailableSoon();


        // Make the input surface current
        // mInputWindowSurface.makeCurrent();
        EGL14.eglMakeCurrent(mEncoderContext.windowDisplay,
                mEncoderContext.windowDrawSurface, mEncoderContext.windowReadSurface,
                EGL14.eglGetCurrentContext());

        // If we don't set the scissor rect, the glClear() we use to draw the
        // light-grey background will draw outside the viewport and muck up our
        // letterboxing.  Might be better if we disabled the test immediately after
        // the glClear().  Of course, if we were clearing the frame background to
        // black it wouldn't matter.
        //
        // We do still need to clear the pixels outside the scissor rect, of course,
        // or we'll get garbage at the edges of the recording.  We can either clear
        // the whole thing and accept that there will be a lot of overdraw, or we
        // can issue multiple scissor/clear calls.  Some GPUs may have a special
        // optimization for zeroing out the color buffer.
        //
        // For now, be lazy and zero the whole thing.  At some point we need to
        // examine the performance here.
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                mVideoRect.width(), mVideoRect.height());
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(mVideoRect.left, mVideoRect.top,
                mVideoRect.width(), mVideoRect.height());

        return displayContext;
    }

    public void stopCapture(CaptureContext oldContext, long timeStampNanos) {

        if (oldContext == null) {
            return;
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        EGLExt.eglPresentationTimeANDROID(mEncoderContext.windowDisplay,
                mEncoderContext.windowDrawSurface, timeStampNanos);

        EGL14.eglSwapBuffers(mEncoderContext.windowDisplay,
                mEncoderContext.windowDrawSurface);


        // Restore.
        GLES20.glViewport(0, 0, oldContext.getWidth(), oldContext.getHeight());
        EGL14.eglMakeCurrent(oldContext.windowDisplay,
                oldContext.windowDrawSurface, oldContext.windowReadSurface,
                EGL14.eglGetCurrentContext());
    }



    public boolean isRecording() {
        return mRecording;
    }

    public void toggleRecording() {
        if (isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    protected void startRecording() {
        mRecording = true;
        if (mVideoEncoder == null) {
            mVideoEncoder = new TextureMovieEncoder2(mEncoderCore);
        }
        if (listener != null) {
            listener.onVideoRecorderEvent(VideoEvent.RecordingStarted);
        }
    }

    protected void stopRecording() {
        mRecording = false;
        if (mVideoEncoder != null) {
            mVideoEncoder.stopRecording();
        }
        if (listener != null) {
            listener.onVideoRecorderEvent(VideoEvent.RecordingStopped);
        }
    }

    public void setEglConfig(EGLConfig eglConfig) {
        this.mEGLConfig = eglConfig;
    }

    public enum VideoEvent {
        RecordingStarted,
        RecordingStopped
    }

    public interface VideoRecorderListener {

        void onVideoRecorderEvent(VideoEvent videoEvent);
    }


    public static class CaptureContext {
        EGLDisplay windowDisplay;
        EGLSurface windowReadSurface;
        EGLSurface windowDrawSurface;
        private int mWidth;
        private int mHeight;

        public void initialize() {
            windowDisplay = EGL14.eglGetCurrentDisplay();
            windowReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
            windowDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
            int v[] = new int[1];
            EGL14.eglQuerySurface(windowDisplay, windowDrawSurface, EGL14.EGL_WIDTH,
                    v, 0);
            mWidth = v[0];
            v[0] = -1;
            EGL14.eglQuerySurface(windowDisplay, windowDrawSurface, EGL14.EGL_HEIGHT,
                    v, 0);
            mHeight = v[0];
        }

        /**
         * Returns the surface's width, in pixels.
         * <p>
         * If this is called on a window surface, and the underlying
         * surface is in the process
         * of changing size, we may not see the new size right away
         * (e.g. in the "surfaceChanged"
         * callback).  The size should match after the next buffer swap.
         */
        public int getWidth() {
            if (mWidth < 0) {
                int v[] = new int[1];
                EGL14.eglQuerySurface(windowDisplay,
                        windowDrawSurface, EGL14.EGL_WIDTH, v, 0);
                mWidth = v[0];
            }
            return mWidth;
        }

        /**
         * Returns the surface's height, in pixels.
         */
        public int getHeight() {
            if (mHeight < 0) {
                int v[] = new int[1];
                EGL14.eglQuerySurface(windowDisplay, windowDrawSurface,
                        EGL14.EGL_HEIGHT, v, 0);
                mHeight = v[0];
            }
            return mHeight;
        }

    }
}
