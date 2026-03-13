package com.esc.begu

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.view.View
import android.view.WindowManager
import com.esc.begu.MainActivity.Companion.REQUEST_CODE_PERMISSIONS

// Camera
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

// Vosk Speech
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import org.json.JSONObject

/**
 * Pantalla correspondiente a la Transcripcción de Voz a Texto
 */
class TranscriptionActivity : AppCompatActivity(),
    RecognitionListener {

    // Estados de la UI
    companion object {
        const val STATE_CAMERA_ON: Int = 0
        const val STATE_CAMERA_OFF: Int = 1
        const val STATE_MIC_ON: Int = 2
        const val STATE_MIC_OFF: Int = 3
    }

    // Componentes de la UI
    private lateinit var cameraView: PreviewView            // Muestra la vista de la cámara.
    private lateinit var backButton: ImageButton            // Botón para volver al menú principal
    private lateinit var signButton: ImageButton            // Botón para cambiar a la vista de reconocimiento de LS
    private lateinit var micButton: ImageButton             // Botón para encender el micrófono
    private lateinit var cameraSwitchButton: ImageButton    // Botón para cambiar la cámara
    private lateinit var cameraButton: ImageButton          // Botón abrir el menú de opciones

    // Componentes para la transcripción de voz a texto
    private lateinit var model: Model
    private var speechService: SpeechService? = null
    private var isListening: Boolean = false
    private lateinit var textView: TextView
    private lateinit var editTextView: EditText
    private var textSave: String = ""

    // Componentes de la cámara
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA  // Seleccionar cámara trasera por defecto
    private var isFrontCamera: Boolean = false
    private var isCameraOn: Boolean = true
    private lateinit var blackOverlay: View

    @SuppressLint("MissingInflatedId")
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

        setContentView(R.layout.activity_transcription)

        // Inicializar elementos de la UI
        cameraView = findViewById(R.id.cameraView)
        backButton = findViewById(R.id.backButton)
        signButton = findViewById(R.id.signImageButton)

        micButton = findViewById(R.id.micButton)

        cameraButton = findViewById(R.id.cameraButton)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)

        textView = findViewById(R.id.textView)
        editTextView = findViewById(R.id.editTextView)
        blackOverlay = findViewById(R.id.blackOverlay)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                Permissions.REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS)
        } else {
            startCamera()   // Activar cámara
            initModel()     // Cargar modelo de reconocimiento de voz
        }

        // Cambiar a la vista del menú principal
        backButton.setOnClickListener {
            if (speechService != null) {
                speechService!!.stop()
                speechService!!.shutdown()
            }
            val intent = Intent(this@TranscriptionActivity,
                MainActivity::class.java)
            startActivity(intent)
        }

        // Cambiar a la vista de reconocimiento de LS
        signButton.setOnClickListener {
            if (speechService != null) {
                speechService!!.stop()
                speechService!!.shutdown()
            }
            val intent = Intent(this@TranscriptionActivity,
                SignLangActivity::class.java)
            startActivity(intent)
        }

        // Activar o desactivar micrófono
        micButton.setOnClickListener {
            if (isListening) {
                setUI(STATE_MIC_OFF)
            } else {
                setUI(STATE_MIC_ON)
            }
        }

        // Cambiar la vista de la cámara
        cameraSwitchButton.setOnClickListener {
            if (isListening) setUI(STATE_MIC_OFF)

            if (isFrontCamera) {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                isFrontCamera = false
            } else {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                isFrontCamera = true
            }
            startCamera()
        }

        // Activar o desactivar cámara
        cameraButton.setOnClickListener {
            if (isCameraOn) {
                setUI(STATE_CAMERA_OFF)
            } else {
                setUI(STATE_CAMERA_ON)
            }
        }

    }

    private fun setUI(state: Int) {
        when (state) {
            STATE_CAMERA_ON -> {
                cameraSwitchButton.visibility = View.VISIBLE
                textView.visibility = View.INVISIBLE
                editTextView.visibility = View.INVISIBLE
                micButton.setImageResource(R.drawable.baseline_mic_off_24)
                cameraButton.setImageResource(R.drawable.outline_videocam_24)

                isListening = false
                if (speechService != null) speechService!!.stop()
                textView.text = " "

                isCameraOn = true
                startCamera()
                blackOverlay.visibility = View.INVISIBLE
            }

            STATE_CAMERA_OFF -> {
                cameraSwitchButton.visibility = View.INVISIBLE
                textView.visibility = View.INVISIBLE
                editTextView.visibility = View.VISIBLE
                micButton.setImageResource(R.drawable.baseline_mic_off_24)
                cameraButton.setImageResource(R.drawable.outline_videocam_off_24)

                isListening = false

                if (speechService != null) speechService!!.stop()
                textView.text = " "

                cameraProvider?.unbindAll()
                blackOverlay.visibility = View.VISIBLE
                isCameraOn = false
            }

            STATE_MIC_ON -> {
                if (isCameraOn) textView.visibility = View.VISIBLE
                micButton.setImageResource(R.drawable.baseline_mic_24)

                isListening = true
                textView.text = " "
                textSave = (editTextView.text.toString() + " ")
                val rec = Recognizer(model, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService!!.startListening(this)
            }

            STATE_MIC_OFF -> {
                if (isCameraOn) textView.visibility = View.INVISIBLE
                micButton.setImageResource(R.drawable.baseline_mic_off_24)

                isListening = false
                textView.text = " "
                if (speechService != null) speechService!!.stop()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this@TranscriptionActivity)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()

            // Vista previa de CameraX
            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = cameraView.surfaceProvider }

            try {
                // Actualizar vista previa
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this@TranscriptionActivity, cameraSelector, preview, imageCapture)
            } catch (_: Exception) {
                Toast.makeText(this@TranscriptionActivity, "No se pudo iniciar la cámara", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this@TranscriptionActivity))
    }

    private fun initModel() {
        StorageService.unpack(
            this, "model-es", "model",
            { model: Model? ->
                this.model = model!!
            },
            { Toast.makeText(this,
                "Fallo al cargar el modelo",
                Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (isListening) {
            setUI(STATE_MIC_OFF)
        }
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                Permissions.REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isListening) {
            setUI(STATE_MIC_OFF)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (speechService != null) {
            speechService!!.stop()
            speechService!!.shutdown()
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
        grantResults: IntArray) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, "Permisos no concedidos", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ///// Vosk Functions ////////////////////////////////////////////////////////////////////////
    private fun showText(name: String, hypothesis: String){
        val json = JSONObject(hypothesis)
        val result: String  = json.optString(name, "")

        if (!result.isEmpty()) {
            textView.text = result
            editTextView.setText(textSave + result)
            editTextView.setSelection(editTextView.text.length)
        }
    }

    override fun onPartialResult(hypothesis: String) { showText("partial", hypothesis) }

    override fun onResult(hypothesis: String) { showText("text", hypothesis) }

    override fun onFinalResult(hypothesis: String) { showText("text", hypothesis) }

    override fun onError(e: Exception) {
        textView.text = " "
        if (isListening) speechService!!.startListening(this) // Continuar reconocimiento de voz
    }

    override fun onTimeout() {
        TODO("Not yet implemented")
    }
    ////////////////////////////////////////////////////////////////////////////////////////////////
}