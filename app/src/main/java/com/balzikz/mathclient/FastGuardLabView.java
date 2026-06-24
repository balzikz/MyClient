package com.balzikz.mathclient;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class FastGuardLabView extends GLSurfaceView
        implements GLSurfaceView.Renderer {

    public interface StatusListener {
        void onStatus(String status);
    }

    private final StatusListener statusListener;
    private int frameCounter;

    public FastGuardLabView(Context context, StatusListener statusListener) {
        super(context);
        this.statusListener = statusListener;
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        NativeBridge.fastGuardLabCreate();
        publishStatus();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        NativeBridge.fastGuardLabResize(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        NativeBridge.fastGuardLabRender();
        frameCounter++;
        if (frameCounter % 45 == 0) {
            publishStatus();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        boolean pressed = event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        queueEvent(() -> NativeBridge.fastGuardLabTouch(x, y, pressed));
        return true;
    }

    private void publishStatus() {
        if (statusListener == null) {
            return;
        }
        String status = NativeBridge.fastGuardLabStatus();
        post(() -> statusListener.onStatus(status));
    }
}
