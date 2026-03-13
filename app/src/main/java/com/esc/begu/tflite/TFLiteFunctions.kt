package com.esc.begu.tflite

import android.app.Activity
import android.content.Context
import android.widget.TextView
import android.widget.Toast

import com.esc.begu.mediapipe.LandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.util.Locale

class TFLiteFunctions(private val context: Context) {

    // TFLite Functions ////////////////////////////////////////////////////////////////////////////
    fun loadModelFile() {
        try {
            val options = Interpreter.Options()
            options.addDelegate(FlexDelegate()) // Flex para ops extendidas
            val model = FileUtil.loadMappedFile(context, MODEL_PATH)
            tfLiteInterpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al cargar el modelo", Toast.LENGTH_SHORT).show()
        }
    }

    fun initPredict(
        handList: List<List<NormalizedLandmark>>,
        faceList: List<List<NormalizedLandmark>>,
        poseList: List<NormalizedLandmark>,
        textView: TextView
    ) {
        // Verificar que se muestra rostro y manos
        if (handList.isNotEmpty() && faceList.isNotEmpty()) {
            // Resetear contador de frames vacíos
            consecutiveEmptyFrames = 0

            // Solo procesar frames según el FPS objetivo
            if (shouldProcessFrame()) {
                getInput(handList, faceList, poseList)
                lastFrameTime = System.currentTimeMillis()
                calculateFPS()
            }

            // Lógica de predicción simplificada
            if (sequenceBuffer.size == FRAMES_PER_SEQUENCE) {
                try {
                    val (classIndex, confidence) = getPredict()

                    // Solo procesar si la confianza es suficiente
                    if (confidence >= CONFIDENCE_THRESHOLD) {
                        val label = labelList.getOrElse(classIndex) { "Desconocido" }
                        addPredictionToHistory(label)

                        // Obtener predicción suavizada
                        val (smoothedPrediction, smoothedConfidence) = getSmoothedPrediction()
                        if (smoothedPrediction.isNotEmpty()) {
                            updatePredictionUI(textView, smoothedPrediction, smoothedConfidence)
                        }
                    }
                } catch (e: Exception) {
                    textView.text =
                        String.Companion.format(Locale.getDefault(), "Error en predicción")
                }
            } else {
                textView.text =
                    String.Companion.format(Locale.getDefault(), "Preparando... (${sequenceBuffer.size}/${FRAMES_PER_SEQUENCE})")
            }
        }
        else {
            // Manejo mejorado cuando no hay detección
            handleNoDetection(handList, faceList, textView)
        }
    }

    private fun handleNoDetection(
        handList: List<List<NormalizedLandmark>>,
        faceList: List<List<NormalizedLandmark>>,
        textView: TextView
    ) {
        consecutiveEmptyFrames++

        // Si han pasado muchos frames sin detección, resetear el buffer
        if (consecutiveEmptyFrames >= MAX_EMPTY_FRAMES) {
            sequenceBuffer.clear()
            consecutiveEmptyFrames = 0
            textView.text = ""
        }

        predictionHistory.clear()

        if (context is Activity) {
            context.runOnUiThread {
                when {
                    (handList.isEmpty() && faceList.isEmpty()) -> {
                        textView.text =
                            String.Companion.format(Locale.getDefault(), "Rostro y mano requeridos")
                    }
                    handList.isEmpty() -> {
                        textView.text =
                            String.Companion.format(Locale.getDefault(), "Mano requerida")
                    }
                    faceList.isEmpty() -> {
                        textView.text =
                            String.Companion.format(Locale.getDefault(), "Rostro requerido")
                    }
                }

                textView.setTextColor(0xFFFF0000.toInt()) // Rojo
            }
        }

    }

    private fun getPredict(): Pair<Int, Float> {
        // Convierte el buffer a un array de NumPy con forma adecuada
        val seq = arrayOf(sequenceBuffer.toTypedArray()) // shape: [1, FRAMES_PER_SEQUENCE, 412] (temporal)
        tfLiteInterpreter.run(seq, output)  // Realiza la predicción con el modelo

        val pred = output[0]
        val idx = pred.indices.maxByOrNull { pred[it] } ?: -1
        val conf = pred.getOrElse(idx) { 0f }

        return Pair(idx, conf)
    }

    private fun getInput(
        handList: List<List<NormalizedLandmark>>,
        faceList: List<List<NormalizedLandmark>>,
        poseList: List<NormalizedLandmark>,
    ) {
        // Puntos
        val handFeats = extractHandPoints(handList)
        val faceFeats = extractFacePoints(faceList[0])
        val poseFeats = extractPosePoints(poseList)

        val fullFeats = (handFeats + faceFeats + poseFeats).toFloatArray()

        // Debería ser: 84 (manos) + 316 (rostro) + 12 (pose) = 412 (temporal)
        if (fullFeats.size != LandmarkIndices.TOTAL_FEATURES) {
            return
        }

        // Agregar el frame al buffer
        if (sequenceBuffer.size == FRAMES_PER_SEQUENCE) {
            sequenceBuffer.removeFirst()
            sequenceBuffer.addLast(fullFeats)
        }
        else sequenceBuffer.addLast(fullFeats)
    }

    // Funciones para extraer puntos
    private fun extractHandPoints(hands: List<List<NormalizedLandmark>>): List<Float> {
        val result = mutableListOf<Float>()

        for (i in 0 until 2) {
            if (hands.size > i && hands[i].isNotEmpty())  {
                val handLm = hands[i]
                val origin = handLm[0]  // Usa la muñeca como origen

                val yPoint24 = point24?.y()
                val yPoint12 = handLm[12].y()

                if (yPoint24 != null && yPoint24 > yPoint12) {
                    for (lm in handLm) {
                        // Solo (X,Y)
                        result += (lm.x() - origin.x())
                        result += (lm.y() - origin.y())
                        //result += (p.z() - origin.z())
                    }
                    // Si la mano esta debajo de la cadera se rellena con ceros
                } else repeat(21) { result += listOf(0.0f, 0.0f) }
                // Si no se detecta una mano, usa ceros para 21 landmarks
            } else repeat(21) { result += listOf(0.0f, 0.0f) }

        }
        return result
    }

    private fun extractFacePoints(face: List<NormalizedLandmark>): List<Float> {
        // Todos los puntos
//        val origin = face[1]
//        return face.flatMap { listOf(it.x() - origin.x(), it.y() - origin.y(), it.z() - origin.z()) }

        // Para filtrar puntos
        val faceLm = face
        val origin = faceLm[1]    // Usa la punta de la nariz como origen

        val selectedFace = LandmarkIndices.FACE_SELECTED_INDICES.map { faceLm[it] }

        return selectedFace.flatMap { lm ->
            listOf(
                // Solo (X,Y)
                lm.x() - origin.x(),
                lm.y() - origin.y())//,
            //lm.y() - origin.y())
        }
    }

    private fun extractPosePoints(pose: List<NormalizedLandmark>?): List<Float> {
        // Para filtrar puntos
        return if (pose != null && pose.isNotEmpty() && pose.size > 11) {
            val poseLm = pose
            val origin = poseLm[11] // Usa hombro izquierdo como origen

            point24 = poseLm[24]

            val selectedPose = LandmarkIndices.POSE_SELECTED_INDICES.map { poseLm[it] }

            selectedPose.flatMap { lm ->
                listOf(
                    // Solo (X,Y)
                    lm.x() - origin.x(),
                    lm.y() - origin.y())//,
                //lm.z() - origin.z())
            }
        } else List(6) { listOf(0.0f, 0.0f) }.flatten()
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////





    // Filtro de Predicciones //////////////////////////////////////////////////////////////////////

    /**
     * Agrega una predicción al historial
     */
    private fun addPredictionToHistory(prediction: String) {
        // Si la confianza es suficiente, decodifica la etiqueta y la agrega al historial
        predictionHistory.addLast(prediction)
        if (predictionHistory.size > 10) {
            predictionHistory.removeFirst()
        }
    }

    /**
     * Obtiene la predicción más común del historial con su nivel de confianza
     */
    private fun getSmoothedPrediction(): Pair<String, Float> {
        if (predictionHistory.isEmpty()) {
            return Pair("", 0f)
        }

        // Contar frecuencias
        val freq = mutableMapOf<String, Int>()
        for (pred in predictionHistory) {
            freq[pred] = freq.getOrDefault(pred, 0) + 1
        }

        // Encontrar la más común
        val mostCommon = freq.maxByOrNull { it.value }
        if (mostCommon != null) {
            val count = mostCommon.value
            // Calcula el nivel de confianza como la proporción de la clase más común
            val confLevel = count.toFloat() / predictionHistory.size
            return Pair(mostCommon.key, confLevel)
        }

        return Pair("", 0f)
    }

    /**
     * Verifica si es tiempo de procesar un nuevo frame
     */
    private fun shouldProcessFrame(): Boolean {
        val currentTime = System.currentTimeMillis()
        // Si el buffer está lleno y ha pasado el intervalo de tiempo, realiza una predicción
        return (currentTime - lastFrameTime) >= FRAME_INTERVAL
    }

    /**
     * Calcula el FPS actual basado en el historial de frames
     */
    private fun calculateFPS() {
        val currentTime = System.currentTimeMillis()
        fpsHistory.addLast(currentTime)

        if (fpsHistory.size > 10) {
            fpsHistory.removeFirst()
        }

        if (fpsHistory.size >= 2) {
            val timeSpan = fpsHistory.last() - fpsHistory.first()
            val frameCount = fpsHistory.size - 1
            currentFPS = (frameCount * 1000.0) / timeSpan
        }
    }

    /**
     * Actualiza la UI con la predicción suavizada
     */
    private fun updatePredictionUI(textView: TextView, prediction: String, confidence: Float) {
        if (context is Activity) {
            context.runOnUiThread {
                // Definir color según el nivel de confianza
                val color = if (confidence >= 0.6f) {
                    0xFF00FF00.toInt() // Verde para alta confianza
                } else {
                    0xFFFFA500.toInt() // Naranja para confianza media
                }

                textView.setTextColor(color)

                // Mostrar predicción en mayúsculas con confianza
                val displayText = "${prediction.uppercase()} (${String.Companion.format(Locale.getDefault(), "%.2f", confidence)})"
                textView.text = displayText
            }
        }

    }

    ////////////////////////////////////////////////////////////////////////////////////////////////



    // Objetos constantes /////////////////////////////////////////////////////////////////////////
    companion object {
        // Componentes del modelo TFLite
        val labelList: List<String> = listOf(
            "a", "b", "c", "d", "e", "estudiar", "f", "g", "h", "i",
            "j", "k", "l", "m", "mañana", "n", "o", "p", "prueba", "q",
            "r", "s", "t", "tu", "u", "v", "w", "x", "y", "yo",
            "z", "ñ"
        )
        var output: Array<FloatArray> = Array(1) { FloatArray(labelList.size) } // labelList.size: Int
        const val FRAMES_PER_SEQUENCE = 15
        var sequenceBuffer: ArrayDeque<FloatArray> = ArrayDeque(FRAMES_PER_SEQUENCE)

        const val MODEL_PATH: String = "modelo.tflite"
        lateinit var tfLiteInterpreter: Interpreter
        var point24: NormalizedLandmark? = null

        // Filtro de predicciones
        val predictionHistory = ArrayDeque<String>(10) // Historial de predicciones (PREDICTION_HISTORY_SIZE = 10)
        const val CONFIDENCE_THRESHOLD = 0.5f // Umbral de confianza mínimo

        // Control de FPS
        const val TARGET_FPS = 10 // FPS objetivo para predicciones (FPS = 10)
        const val FRAME_INTERVAL = (1000L / TARGET_FPS) // Intervalo entre frames (100ms)
        var lastFrameTime = 0L // Último tiempo de frame procesado
        val fpsHistory = ArrayDeque<Long>(TARGET_FPS) // Historial de tiempos de frame

        var consecutiveEmptyFrames = 0 // Contador de frames vacíos consecutivos
        const val MAX_EMPTY_FRAMES = 30 // Máximo frames vacíos antes de resetear (3 segundos a 10 FPS)
        var currentFPS = 0.0 // FPS actual calculado
    }

}