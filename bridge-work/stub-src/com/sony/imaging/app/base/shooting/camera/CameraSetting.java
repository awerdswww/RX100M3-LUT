package com.sony.imaging.app.base.shooting.camera;

import android.hardware.Camera;
import android.util.Pair;

import com.sony.scalar.hardware.CameraEx;

/** 编译期占位：真机上是官方应用的 CameraSetting（公共方法签名一致）。 */
public class CameraSetting {
    public CameraEx getCamera() { return null; }
    public Pair<Camera.Parameters, CameraEx.ParametersModifier> getEmptyParameters() { return null; }
    public void setParameters(Pair<Camera.Parameters, CameraEx.ParametersModifier> p) {}
}
