package com.tanuj.applock

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * Captures one still from the front camera with no preview, after too many
 * wrong attempts, and saves it privately inside the app. Used only as an
 * anti-theft feature on the owner's own device; photos never leave the phone.
 */
object Intruder {

    fun enabled(ctx: Context) = Prefs(ctx).isOn("f_intruder", false)

    fun dir(ctx: Context) = File(ctx.filesDir, "intruders").apply { mkdirs() }

    fun capture(ctx: Context) {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        val thread = HandlerThread("intruder").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)

        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                File(dir(ctx), "intruder_${System.currentTimeMillis()}.jpg").writeBytes(bytes)
            }
        }, handler)

        try {
            mgr.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    }
                    device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                                    try { device.close() } catch (_: Exception) {}
                                    thread.quitSafely()
                                }
                            }, handler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { device.close(); thread.quitSafely() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); thread.quitSafely() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); thread.quitSafely() }
            }, handler)
        } catch (e: Exception) { thread.quitSafely() }
    }
}
