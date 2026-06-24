package com.balzikz.mathclient;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class EglLabView extends GLSurfaceView implements GLSurfaceView.Renderer {

    public interface StatusListener {
        void onStatus(String status);
    }

    private final StatusListener statusListener;

    public EglLabView(Context context, StatusListener statusListener) {
        super(context);
        this.statusListener = statusListener;
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        NativeBridge.eglLabCreate();
        if (statusListener != null) {
            String status = NativeBridge.eglLabStatus();
            post(() -> statusListener.onStatus(status));
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        NativeBridge.eglLabResize(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        NativeBridge.eglLabRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        boolean pressed = event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        queueEvent(() -> NativeBridge.eglLabTouch(x, y, pressed));
        return true;
    }
}
