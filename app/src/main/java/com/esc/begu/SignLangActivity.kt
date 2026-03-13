package com.esc.begu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.esc.begu.MainActivity.Companion.REQUEST_CODE_PERMISSIONS
import com.esc.begu.databinding.ActivitySignBinding

// Camera
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

// MediaPipe
import androidx.activity.viewModels
import com.esc.begu.mediapipe.MultiLandmarkerHelper
import com.esc.begu.mediapipe.MultiMainViewModel
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.getValue

// TensorFlow
import com.esc.begu.tflite.TFLiteFunctions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.collections.List



/**
 * Pantalla correspondiente al Reconocimiento de Señas
 */
class SignLangActivity : AppCompatActivity(),
    MultiLandmarkerHelper.LandmarkerListener {

    // MediaPipe LandMarkers Components ////////////////////////////////////////////////////////////
    companion object {
        private const val LM_TAG   = "LandMarker"
    }

    private lateinit var binding: ActivitySignBinding

    private lateinit var multiLandmarkerHelper: MultiLandmarkerHelper
    private val multiViewModel: MultiMainViewModel by viewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService
    ////////////////////////////////////////////////////////////////////////////////////////////////


    // Componentes de la UI
    private lateinit var cameraView: PreviewView            // Muestra la vista de la cámara.
    private lateinit var backButton: ImageButton            // Botón para volver al menú principal
    private lateinit var transcriptionButton: ImageButton   // Botón para cambiar a la vista de reconocimiento de voz
    private lateinit var cameraSwitchButton: ImageButton    // Botón para cambiar la cámara

    private lateinit var landmarksViewButton: ImageButton   // Botón para no/mostrar los puntos de referencia

    // Componentes de la camara
    private var cameraSelector =
        CameraSelector.Builder().requireLensFacing(cameraFacing).build()
    private var isLandmarksView: Boolean = false

    // Componentes del modelo TFLite
    val modelFunctions = TFLiteFunctions(this@SignLangActivity)

    // Listas
    var handList: List<List<NormalizedLandmark>> = emptyList()     //  21 puntos por mano (42 total)
    var faceList: List<List<NormalizedLandmark>> = emptyList()     // 468 puntos
    var poseList: List<NormalizedLandmark> = emptyList()           //  32 puntos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ocultar barra de estado (barra superior)
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Ocultar barra de navegación (barra inferior)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )

        binding = ActivitySignBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar elementos de la UI
        cameraView = findViewById(R.id.cameraView)
        backButton = findViewById(R.id.backButton)
        transcriptionButton = findViewById(R.id.transcriptionImageButton)

        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        landmarksViewButton = findViewById(R.id.landmarksViewButton)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                Permissions.REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS)
        } else {
            modelFunctions.loadModelFile()  // Cargar Modelo TFLite
        }
        handList = emptyList()
        faceList = emptyList()
        poseList = emptyList()


        // From mediapipe LandMarkers  /////////////////////////////////////////////////////////////

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        binding.cameraView.post { setUpCamera() } // Set up the camera and its use cases

        // Create the MultiLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            multiLandmarkerHelper = MultiLandmarkerHelper(
                context = this@SignLangActivity,
                runningMode = RunningMode.LIVE_STREAM,
                minDetectionConfidence = multiViewModel.currentMinDetectionConfidence,
                minTrackingConfidence = multiViewModel.currentMinTrackingConfidence,
                minPresenceConfidence = multiViewModel.currentMinPresenceConfidence,
                maxNumFaces = multiViewModel.currentMaxFaces,
                maxNumHands = multiViewModel.currentMaxHands,
                currentDelegate = multiViewModel.currentDelegate,
                multiLandmarkerHelperListener = this@SignLangActivity
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()

        ////////////////////////////////////////////////////////////////////////////////////////////


        // Cambiar a la vista del menú principal
        backButton.setOnClickListener {
            val intent = Intent(this@SignLangActivity, MainActivity::class.java)
            startActivity(intent)

            // Shut down our background executor
            backgroundExecutor.shutdown()
            backgroundExecutor.awaitTermination(
                Long.MAX_VALUE, TimeUnit.NANOSECONDS
            )

            cameraProvider?.unbindAll()
        }

        // Cambiar a la vista de reconocimiento de voz
        transcriptionButton.setOnClickListener {
            val intent = Intent(this@SignLangActivity, TranscriptionActivity::class.java)
            startActivity(intent)

            // Shut down our background executor
            backgroundExecutor.shutdown()
            backgroundExecutor.awaitTermination(
                Long.MAX_VALUE, TimeUnit.NANOSECONDS
            )

            cameraProvider?.unbindAll()
        }

        // Cambiar la vista de la cámara
        cameraSwitchButton.setOnClickListener {

            // Needs to be cleared instead of reinitialized because the GPU
            // delegate needs to be initialized on the thread using it when applicable
            backgroundExecutor.execute { multiLandmarkerHelper.clearMultiLandmarker() }
            clearBinding()

            cameraFacing = if(CameraSelector.LENS_FACING_BACK == cameraFacing) {
                CameraSelector.LENS_FACING_FRONT
            } else{
                CameraSelector.LENS_FACING_BACK
            }
            cameraSelector =
                CameraSelector.Builder().requireLensFacing(cameraFacing).build()

            backgroundExecutor.execute {
                if (multiLandmarkerHelper.isClose()) multiLandmarkerHelper.setupMultiLandmarker()
            }
            setUpCamera()
        }

        // Activar o desactivar Landmarkers
        landmarksViewButton.setOnClickListener {
            if (isLandmarksView) {
                landmarksViewButton.setImageResource(R.drawable.outline_remove_red_eye_24)
                clearBinding()
                isLandmarksView = false
            } else {
                landmarksViewButton.setImageResource(R.drawable.baseline_remove_red_eye_24)
                isLandmarksView = true
            }
        }

    }

    // PERMISSIONS
    private fun allPermissionsGranted() = Permissions.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext,
            it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(
                    this,
                    "Permisos no concedidos",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    // mediapipe LandMarkers Functions /////////////////////////////////////////////////////////////

    override fun onResume() {
        super.onResume()
        // Start the MultiLandMarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (multiLandmarkerHelper.isClose()) {
                multiLandmarkerHelper.setupMultiLandmarker()
            }
        }

        // Verificar y solicitar permisos antes de iniciar la aplicación
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, Permissions.REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()

        if(this@SignLangActivity::multiLandmarkerHelper.isInitialized) {
            multiViewModel.setMaxFaces(multiLandmarkerHelper.maxNumFaces)
            multiViewModel.setMaxHands(multiLandmarkerHelper.maxNumHands)
            multiViewModel.setMinDetectionConfidence(multiLandmarkerHelper.minDetectionConfidence)
            multiViewModel.setMinTrackingConfidence(multiLandmarkerHelper.minTrackingConfidence)
            multiViewModel.setMinPresenceConfidence(multiLandmarkerHelper.minPresenceConfidence)
            multiViewModel.setDelegate(multiLandmarkerHelper.currentDelegate)

            // Close the MultiLandMarkerHelper and release resources
            if (!backgroundExecutor.isShutdown){
                backgroundExecutor.execute { multiLandmarkerHelper.clearMultiLandmarker() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    private fun clearBinding() {
        binding.handOverlay.clear()
        binding.faceOverlay.clear()
        binding.poseOverlay.clear()
    }
    private fun initBottomSheetControls() {
        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        binding.bottomSheetLayout.spinnerDelegate.setSelection(
            multiViewModel.currentDelegate, false
        )
        binding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        multiLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(_: UninitializedPropertyAccessException) {
                        Log.e(LM_TAG, "MultiLandMarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset MultiLandMarker
    // helper.
    private fun updateControlsUi() {
        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            multiLandmarkerHelper.clearMultiLandmarker()
            multiLandmarkerHelper.setupMultiLandmarker()
        }
        clearBinding()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this@SignLangActivity)
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this@SignLangActivity)
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder()//.setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(binding.cameraView.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()//.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(binding.cameraView.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectMultiLandmaker(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this@SignLangActivity, cameraSelector, preview, imageAnalyzer
            )

            // Attach the cameraView's surface provider to preview use case
            preview?.surfaceProvider = binding.cameraView.surfaceProvider
        } catch (exc: Exception) {
            Log.e(LM_TAG, "Use case binding failed", exc)
        }
    }

    private fun detectMultiLandmaker(imageProxy: ImageProxy) {
        multiLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            binding.cameraView.display.rotation
    }


    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // HandOverlayView
    override fun onHandResults(
        resultBundle: MultiLandmarkerHelper.HandResultBundle
    ) {
        runOnUiThread {
            binding.bottomSheetLayout.handInferenceTimeVal.text =
                String.format(Locale.getDefault(),"%d ms", resultBundle.inferenceTime)

            if (isLandmarksView) {
                // Pass necessary information to OverlayView for drawing on the canvas
                binding.handOverlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                binding.handOverlay.invalidate()
            }

            // para predicción
            handList = resultBundle.results.first().landmarks()
        }
    }

    // Update UI after face have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // FaceOverlayView
    override fun onFaceResults(
        resultBundle: MultiLandmarkerHelper.FaceResultBundle
    ) {
        runOnUiThread {

            binding.bottomSheetLayout.faceInferenceTimeVal.text =
                String.format(Locale.getDefault(), "%d ms", resultBundle.inferenceTime)

            if (isLandmarksView) {
                // Pass necessary information to FaceOverlayView for drawing on the canvas
                binding.faceOverlay.setResults(
                    resultBundle.result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                binding.faceOverlay.invalidate()
            }

            // para predicción
            faceList = resultBundle.result.faceLandmarks()
        }
    }

    // Update UI after pose have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onPoseResults(
        resultBundle: MultiLandmarkerHelper.PoseResultBundle
    ) {
        runOnUiThread {

            binding.bottomSheetLayout.poseInferenceTimeVal.text =
                String.format(Locale.getDefault(), "%d ms", resultBundle.inferenceTime)

            if (isLandmarksView) {
                // Pass necessary information to OverlayView for drawing on the canvas
                binding.poseOverlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                binding.poseOverlay.invalidate()
            }

            // para predicción
            val allLandmarks = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull()
            poseList = allLandmarks ?: listOf()

            // Iniciar prediccion
            modelFunctions.initPredict(handList, faceList, poseList, binding.textView)
        }
    }

    override fun onEmpty() {
        clearBinding()
    }

    override fun onError(error: String, errorCode: Int) {
        this@SignLangActivity.runOnUiThread {
            Toast.makeText(this@SignLangActivity, error, Toast.LENGTH_SHORT).show()
            if (errorCode == MultiLandmarkerHelper.GPU_ERROR) {
                binding.bottomSheetLayout.spinnerDelegate.setSelection(
                    MultiLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////


}