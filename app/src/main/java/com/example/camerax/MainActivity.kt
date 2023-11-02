package com.example.camerax

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camerax.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class MainActivity : AppCompatActivity(), View.OnTouchListener,
    ScaleGestureDetector.OnScaleGestureListener {
    private val TAG = MainActivity::class.java.simpleName

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isFromCamera = false
    private var lastScaleFactor = 0f

    private lateinit var binding: ActivityMainBinding
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var backCameraInfo: CameraInfo

    private var cameraProviderFuture = lazy { ProcessCameraProvider.getInstance(this) }
    private var scaleDetector = lazy { ScaleGestureDetector(this, this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            initViews()
        } else {
            ActivityCompat.requestPermissions(
                this, Constants.REQUIRED_PERMISSIONS, Constants.REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initViews() {
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
        backCameraInfo = cameraProviderFuture.value.get().availableCameraInfos[0]
        imageCapture = ImageCapture.Builder().build()
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.value.get()

        startCamera(CameraSelector.DEFAULT_BACK_CAMERA, cameraProvider)
        binding.btnTakePhoto.setOnClickListener {
            takePhoto()
        }

        binding.btnSwitchFlash.setOnClickListener {
            setFlashMode()
        }

        binding.btnReplaceCamera.setOnClickListener {
            cameraProviderFuture.value.cancel(true)

            if (isFromCamera) {
                isFromCamera = false
                startCamera(CameraSelector.DEFAULT_BACK_CAMERA, cameraProvider)
            } else {
                isFromCamera = true
                startCamera(CameraSelector.DEFAULT_FRONT_CAMERA, cameraProvider)
            }
        }
    }

    private fun startCamera(cameraSelector: CameraSelector, cameraProvider: ProcessCameraProvider) {
        val previewView = binding.previewView
        previewView.setOnTouchListener(this)

        cameraProviderFuture.value.addListener(
            {

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                binding.btnSwitchFlash.setBackgroundResource(R.drawable.ic_flash_auto)
                try {
                    cameraProvider.unbindAll()

                    camera =
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                } catch (exc: Exception) {
                    Log.e(TAG, exc.message ?: "Error")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory, SimpleDateFormat(
                Constants.FILE_NAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
            })
    }

    private fun setFlashMode() {
        var icons: Int = 0

        if (imageCapture?.flashMode == ImageCapture.FLASH_MODE_ON) {
            icons = R.drawable.ic_flash_auto
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
        } else if (imageCapture?.flashMode == ImageCapture.FLASH_MODE_AUTO) {
            icons = R.drawable.ic_flash_off
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
        } else {
            icons = R.drawable.ic_flash_on
            imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
        }

        binding.btnSwitchFlash.setBackgroundResource(icons)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View?, event: MotionEvent?): Boolean {
        if (event != null) {
            scaleDetector.value.onTouchEvent(event)
            return true
        }

        return false
    }

    override fun onScaleBegin(p0: ScaleGestureDetector): Boolean {
        return true
    }

    override fun onScaleEnd(p0: ScaleGestureDetector) {
    }

    override fun onScale(p0: ScaleGestureDetector): Boolean {
        if (camera == null || isFromCamera) return false

        val zoomRatio: Float? = backCameraInfo.zoomState.value?.zoomRatio
        val minZoomRatio: Float? = backCameraInfo.zoomState.value?.minZoomRatio
        val maxZoomRatio: Float? = backCameraInfo.zoomState.value?.maxZoomRatio
        val scaleFactor = scaleDetector.value.scaleFactor
        lastScaleFactor =
            if ((lastScaleFactor == 0f || (sign(scaleFactor) == sign(lastScaleFactor)))) {
                camera?.cameraControl?.setZoomRatio(
                    max(
                        minZoomRatio!!, min(zoomRatio!! * scaleFactor, maxZoomRatio!!)
                    )
                )
                scaleFactor
            } else {
                0f
            }
        return true
    }

    override fun onPause() {
        super.onPause()
        cameraProviderFuture.value.cancel(true)
    }

    override fun onResume() {
        super.onResume()
        initViews()
    }

    private fun allPermissionsGranted() = Constants.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            System.currentTimeMillis().toString() + ".jpg"
        ).apply { mkdirs() }

        return file
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProviderFuture.value.cancel(true)
    }
}