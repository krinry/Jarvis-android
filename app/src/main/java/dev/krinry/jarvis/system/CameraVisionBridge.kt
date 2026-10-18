package dev.krinry.jarvis.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CameraVisionBridge — Silent real-world visual perception for Jarvis AI.
 *
 * Captures instantaneous camera snapshots in background via Camera2 API
 * without showing fullscreen preview activities or interrupting the user.
 * Streams JPEG image frames directly into Gemini Live's visual reasoning pipeline.
 */
object CameraVisionBridge {

    private const val TAG = "CameraVisionBridge"
    private const val CAPTURE_TIMEOUT_MS = 4000L

    suspend fun captureSilentSnapshot(
        context: Context,
        lensFacing: Int = CameraMetadata.LENS_FACING_BACK
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            return@withContext null
        }

        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return@withTimeoutOrNull null

            val cameraId = findCameraId(cameraManager, lensFacing) ?: run {
                Log.e(TAG, "No matching camera found for lensFacing=$lensFacing")
                return@withTimeoutOrNull null
            }

            val thread = HandlerThread("JarvisOpticSensors").apply { start() }
            val handler = Handler(thread.looper)
            val resultDeferred = CompletableDeferred<ByteArray?>()

            var cameraDevice: CameraDevice? = null
            var imageReader: ImageReader? = null
            var captureSession: CameraCaptureSession? = null

            fun cleanup() {
                try { captureSession?.close() } catch (_: Exception) {}
                try { cameraDevice?.close() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                thread.quitSafely()
            }

            try {
                // High-efficiency 1280x720 capture (~100-200KB JPEG)
                val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
                imageReader = reader

                reader.setOnImageAvailableListener({ ir ->
                    val image = ir.acquireLatestImage()
                    if (image != null) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            Log.d(TAG, "Captured real-world optic frame (${bytes.size} bytes)")
                            resultDeferred.complete(bytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading image buffer", e)
                            resultDeferred.complete(null)
                        } finally {
                            image.close()
                            cleanup()
                        }
                    }
                }, handler)

                @SuppressLint("MissingPermission")
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            val surface = reader.surface
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        try {
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(surface)
                                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                            session.capture(captureBuilder.build(), null, handler)
                                            Log.d(TAG, "Dispatched still capture request to camera $cameraId")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to issue capture request", e)
                                            resultDeferred.complete(null)
                                            cleanup()
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        Log.e(TAG, "Camera capture session configuration failed")
                                        resultDeferred.complete(null)
                                        cleanup()
                                    }
                                },
                                handler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create capture session", e)
                            resultDeferred.complete(null)
                            cleanup()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera disconnected")
                        resultDeferred.complete(null)
                        cleanup()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera open error: $error")
                        resultDeferred.complete(null)
                        cleanup()
                    }
                }, handler)

                resultDeferred.await()
            } catch (e: Exception) {
                Log.e(TAG, "Exception during silent snapshot capture", e)
                cleanup()
                null
            }
        }
    }

    private fun findCameraId(cameraManager: CameraManager, desiredFacing: Int): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == desiredFacing
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating camera IDs", e)
            null
        }
    }
}
