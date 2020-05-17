package com.buildreams.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Size
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val REQUEST_CODE_PERMISSIONS = 10

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class MainActivity : AppCompatActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var outputDirectory: File
    private var img = MutableLiveData<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        img.observe(this, androidx.lifecycle.Observer {
            image_view.setImageBitmap(it)
            preview_view.visibility = INVISIBLE
            image_view.visibility = VISIBLE
        })
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Request camera permissions
        if (allPermissionsGranted()) {
            preview_view.post {
                startCamera()
            }
        } else {
            requestPermissions(
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
            )
        }

        // Setup the listener for take photo button
        outputDirectory = getOutputDirectory(this)
        take_camera_btn.setOnClickListener {
            takePicture()
        }
    }

    private fun takePicture() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("UnsafeExperimentalUsageError")
            override fun onCaptureSuccess(image: ImageProxy) {
                val imgProxy = image.image?: return
                var bitmap = imgProxy.toBitmap()
                image.close()
                // Rotate the bitmap
                val rotationDegrees = image.imageInfo.rotationDegrees.toFloat()
                if (rotationDegrees != 0f) {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees)
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
                img.postValue(bitmap)
            }
        })
    }

    fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun savePicture() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        val file = createFile(
                outputDirectory,
                FILENAME,
                PHOTO_EXTENSION
        )
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(outputFileOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val msg = "Photo capture succeeded: ${file.absolutePath}"
            }

            override fun onError(exception: ImageCaptureException) {
                val msg = "Photo capture failed: ${exception.message}"
            }
        })
    }

    private fun startCamera() {
        val metrics = DisplayMetrics().also { preview_view.display.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        //analyzer = FreezeAnalyzer(img)
        cameraProviderFuture.addListener(Runnable {
            // Preview
            val imagePreview = Preview.Builder().apply {
                setTargetAspectRatio(screenAspectRatio)
                setTargetRotation(preview_view.display.rotation)
            }.build()

            // ImageAnalysis
            // other way to get bitmap
            val analyzer = FreezeAnalyzer(img)
            val imageAnalysis = ImageAnalysis.Builder().apply {
                setImageQueueDepth(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
                setTargetRotation(preview_view.display.rotation)
            }.build()
            imageAnalysis.setAnalyzer(executor, analyzer)

            // ImageCapture
            imageCapture = ImageCapture.Builder().apply {
                setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                setTargetRotation(preview_view.display.rotation)
                setTargetAspectRatio(screenAspectRatio)
            }.build()

            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider = cameraProviderFuture.get()
            // Select back camera
            val cameraSelector = CameraSelector.Builder().requireLensFacing(
                    CameraSelector.LENS_FACING_BACK).build()
            // Bind the use cases to a lifecycle
            val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imagePreview,
                    imageAnalysis,
                    imageCapture)
            // Set the preview surface provider
            imagePreview.setSurfaceProvider(preview_view.createSurfaceProvider(camera.cameraInfo))

        }, ContextCompat.getMainExecutor(this))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (kotlin.math.abs(previewRatio - RATIO_4_3_VALUE) <= kotlin.math.abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                preview_view.post {
                    startCamera()
                }
            } else {
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        fun getOutputDirectory(context: Context): File {
            val appContext = context.applicationContext
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, appContext.resources.getString(R.string.app_name)).apply {
                    mkdirs()
                }
            }
            return if (mediaDir != null && mediaDir.exists()) mediaDir else appContext.filesDir
        }

        fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}
