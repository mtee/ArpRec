/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package at.timofeev.arcore.sessionRecorder.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.ImageMetadata;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import at.timofeev.arcore.sessionRecorder.helpers.CameraPermissionHelper;
import at.timofeev.arcore.sessionRecorder.helpers.DisplayRotationHelper;
import at.timofeev.arcore.sessionRecorder.helpers.FullScreenHelper;
import at.timofeev.arcore.sessionRecorder.helpers.SnackbarHelper;
import at.timofeev.arcore.sessionRecorder.helpers.TapHelper;
import at.timofeev.arcore.sessionRecorder.helpers.VideoRecorder;
import at.timofeev.arcore.sessionRecorder.rendering.BackgroundRenderer;
import at.timofeev.arcore.sessionRecorder.rendering.ObjectRenderer;
import at.timofeev.arcore.sessionRecorder.rendering.ObjectRenderer.BlendMode;
import at.timofeev.arcore.sessionRecorder.rendering.PlaneRenderer;
import at.timofeev.arcore.sessionRecorder.rendering.PointCloudRenderer;

import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.opengles.GL10;

import static com.google.ar.core.ImageMetadata.LENS_RADIAL_DISTORTION;


public class ArpActivity extends AppCompatActivity implements GLSurfaceView.Renderer, VideoRecorder.VideoRecorderListener {
    public static final String TAG = ArpActivity.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;

    private boolean installRequested;

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    private TapHelper tapHelper;

    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final ObjectRenderer virtualObject = new ObjectRenderer();
    private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private final float[] anchorMatrix = new float[16];
    private static final float[] DEFAULT_COLOR = new float[]{0f, 0f, 0f, 0f};

    private int frameId = 0;

    // Recording
    private VideoRecorder mRecorder;
    private android.opengl.EGLConfig mAndroidEGLConfig;

    private File poseFile;
    private boolean posesFileCreated = false;
    private BufferedWriter bufWriter;
    private String mWorkingDirectory;


    // Anchors created from taps used for object placing with a given color.
    private static class ColoredAnchor {
        public final Anchor anchor;
        public final float[] color;

        public ColoredAnchor(Anchor a, float[] color4f) {
            this.anchor = a;
            this.color = color4f;
        }
    }

    private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);


        File extStore = Environment.getExternalStorageDirectory();
        mWorkingDirectory = extStore.getAbsolutePath() + "/" + "ARCorePoseRecorder" + "/";
        extStore = new File(mWorkingDirectory);
        extStore.mkdirs();

        // Set up tap listener.
        tapHelper = new TapHelper(/*context=*/ this);
        surfaceView.setOnTouchListener(tapHelper);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        CameraManager manager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                Log.d(TAG, "------------------------------ "+ cameraId +" ----------------------------------");
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // Examine the LENS_FACING characteristic
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(facing == null){
                    Log.d(TAG, "Facing: NULL");
                }
                else if(facing == CameraCharacteristics.LENS_FACING_BACK){
                    Log.d(TAG, "Facing: BACK");
                } else if(facing == CameraCharacteristics.LENS_FACING_FRONT){
                    Log.d(TAG, "Facing: FRONT");
                } else if(facing == CameraCharacteristics.LENS_FACING_EXTERNAL){
                    Log.d(TAG, "Facing: EXTERNAL");
                } else {
                    Log.d(TAG, "Facing: UNKNOWN");
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if(available == null){
                    Log.d(TAG, "Flash unknown");
                }
                else if(available){
                    Log.d(TAG, "Flash supported");
                } else {
                    Log.d(TAG, "Flash unsupported");
                }

                // Check how much the zoom is supported
                Float zoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                Log.d(TAG, "Max supported digital zoom: " + zoom);

                // Write all the available focal lengths
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                Log.d(TAG, "Available focal lengths: " + Arrays.toString(focalLengths));

                // Check the distortion
                if (Build.VERSION.SDK_INT >= 23) {
                    float[] lensDistortionCoefficients = characteristics.get(CameraCharacteristics.LENS_RADIAL_DISTORTION);
                    Log.d(TAG, "Lens distortion coefficients : " + Arrays.toString(lensDistortionCoefficients));
                }

                Log.d(TAG, "----------------------------------------------------------------");
            }
        } catch (CameraAccessException e) {
            Log.d(TAG, "CameraAccessException: " + e.getMessage());
        } catch (NullPointerException e) {
            Log.d(TAG, "NullPointerException: " + e.getMessage());
        }
        installRequested = false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                // Create the session.
                session = new Session(/* context= */ this);

            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.");
            session = null;
            return;
        }

        surfaceView.onResume();
        displayRotationHelper.onResume();

        messageSnackbarHelper.showMessage(this, "Searching for surfaces...");
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {


            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(/*context=*/ this);
            planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png");
            pointCloudRenderer.createOnGlThread(/*context=*/ this);

            virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png");
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

            virtualObjectShadow.createOnGlThread(
                    /*context=*/ this, "models/andy_shadow.obj", "models/andy_shadow.png");
            virtualObjectShadow.setBlendMode(BlendMode.Shadow);
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

            // Recording:
            EGL10 egl10 =  (EGL10) EGLContext.getEGL();
            javax.microedition.khronos.egl.EGLDisplay display = egl10.eglGetCurrentDisplay();
            int v[] = new int[2];
            egl10.eglGetConfigAttrib(display,config, EGL10.EGL_CONFIG_ID, v);

            EGLDisplay androidDisplay = EGL14.eglGetCurrentDisplay();
            int attribs[] = {EGL14.EGL_CONFIG_ID, v[0], EGL14.EGL_NONE};
            android.opengl.EGLConfig myConfig[] = new android.opengl.EGLConfig[1];
            EGL14.eglChooseConfig(androidDisplay, attribs, 0, myConfig, 0, 1, v, 1);
            this.mAndroidEGLConfig = myConfig[0];

        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
            // Obtain the current frame from ARSession. When the
            //configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will
            // throttle the rendering to the camera framerate.
            Frame frame = session.update();
            Camera camera = frame.getCamera();

            // Handle one tap per frame.
            handleTap(frame, camera);


            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);
            backgroundRenderer.draw(frame);  // draw camera see-through

      //      Log.d(TAG, "Available focal lengths: " + Arrays.toString(distortion));
            PointCloud pointCloud = frame.acquirePointCloud();
            pointCloudRenderer.update(pointCloud);
            pointCloudRenderer.draw(viewmtx, projmtx);

            if (mRecorder!= null && mRecorder.isRecording() && posesFileCreated) {
                bufWriter.append("" + frameId  + " " + getPoseAsString(camera.getPose()) + " " + getIntrinsicsAsString(camera.getTextureIntrinsics())
                   //     + " " + distortion[0] + " " + distortion[1] + " " + distortion[2] + " " + distortion[3] + " " + distortion[4]
                );
                bufWriter.newLine();
                frameId++;
                VideoRecorder.CaptureContext ctx = mRecorder.startCapture();
                if (ctx != null) {
                    // draw again to capture the texture content
                    backgroundRenderer.draw(frame);
                    //Log.d(TAG, "pose: " + getPoseAsString(camera.getPose()));
                    // restore the context
                    mRecorder.stopCapture(ctx, frame.getTimestamp());
                }
            }

            // Check if we detected at least one plane. If so, hide the loading message.
            if (messageSnackbarHelper.isShowing()) {
                for (Plane plane : session.getAllTrackables(Plane.class)) {
                    if (plane.getTrackingState() == TrackingState.TRACKING) {
                        messageSnackbarHelper.hide(this);
                        break;
                    }
                }
            }

            planeRenderer.drawPlanes(
                    session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);
            // Visualize anchors created by touch.
            float scaleFactor = 1.0f;
            for (ColoredAnchor coloredAnchor : anchors) {
                if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue;
                }
                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to session.update() as ARCore refines its estimate of the world.
                coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
                virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
            }

        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private String getPoseAsString(Pose pose) {
        return pose.tx() + " " + pose.ty() + " " + pose.tz() + " " + pose.qx() + " " + pose.qy() + " " + pose.qz() + " " + pose.qw();
    }
    private String getIntrinsicsAsString(CameraIntrinsics intrinsics) {
        return intrinsics.getFocalLength()[0] + " " + intrinsics.getFocalLength()[1] + " " +
                intrinsics.getImageDimensions()[0] + " " + intrinsics.getImageDimensions()[1] + " " +
                intrinsics.getPrincipalPoint()[0] + " " + intrinsics.getPrincipalPoint()[1];
    }

    public void clickToggleRecording(View view) {
        Log.d(TAG, "clickToggleRecording");
        if (mRecorder == null) {
            File outputFile = new File(mWorkingDirectory,
                    "video-" + Long.toHexString(System.currentTimeMillis()) + ".mp4");
            File dir = outputFile.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }

            try {
                mRecorder = new VideoRecorder(surfaceView.getWidth(),
                        surfaceView.getHeight(),
                        VideoRecorder.DEFAULT_BITRATE, outputFile, this);
                mRecorder.setEglConfig(mAndroidEGLConfig);

            } catch (IOException e) {
                Log.e(TAG,"Exception starting recording", e);
            }
        }
        Log.d(TAG, "clickToggleRecording");
        mRecorder.toggleRecording();
        updateControls();
    }

    private void updateControls() {
        Button toggleRelease = findViewById(R.id.fboRecord_button);
        String recordButtonStr = (mRecorder != null && mRecorder.isRecording()) ?
                "Stop" : "Record";
        toggleRelease.setText(recordButtonStr);
        TextView tv =  findViewById(R.id.nowRecording_text);
        if (recordButtonStr == "Stop") {
            tv.setText("recording");
        } else {
            tv.setText("");
        }
    }


    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private void handleTap(Frame frame, Camera camera) {
        MotionEvent tap = tapHelper.poll();
        if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
            for (HitResult hit : frame.hitTest(tap)) {
                // Check if any plane was hit, and if it was hit inside the plane polygon
                Trackable trackable = hit.getTrackable();
                // Creates an anchor if a plane or an oriented point was hit.
                if ((trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                        && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                        || (trackable instanceof Point
                        && ((Point) trackable).getOrientationMode()
                        == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                    // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size() >= 20) {
                        anchors.get(0).anchor.detach();
                        anchors.remove(0);
                    }

                    // Assign a color to the object for rendering based on the trackable type
                    // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
                    // for AR_TRACKABLE_PLANE, it's green color.
                    float[] objColor;
                    if (trackable instanceof Point) {
                        objColor = new float[]{66.0f, 133.0f, 244.0f, 255.0f};
                    } else if (trackable instanceof Plane) {
                        objColor = new float[]{139.0f, 195.0f, 74.0f, 255.0f};
                    } else {
                        objColor = DEFAULT_COLOR;
                    }

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    anchors.add(new ColoredAnchor(hit.createAnchor(), objColor));
                    break;
                }
            }
        }
    }

    @Override
    public void onVideoRecorderEvent(VideoRecorder.VideoEvent videoEvent) {
        Log.d(TAG, "VideoEvent: " + videoEvent);
        updateControls();
        try {
            if (!posesFileCreated) {
                frameId = 0;
                poseFile = new File(mWorkingDirectory, "poses-" + Long.toHexString(System.currentTimeMillis()) + ".txt");
                bufWriter = new BufferedWriter(new FileWriter(poseFile));
                posesFileCreated = true;
            }
        }
        catch (IOException e)             {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (videoEvent == VideoRecorder.VideoEvent.RecordingStopped) {
            mRecorder = null;
            if (posesFileCreated)
                try {
                    bufWriter.close();
                    Log.d(TAG, "pose writer buffer closed");
                    posesFileCreated = false;
                    frameId = 0;
                }
                catch (IOException e)             {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
        }
    }
}

