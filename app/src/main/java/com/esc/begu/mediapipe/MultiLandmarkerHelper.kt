package com.esc.begu.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class MultiLandmarkerHelper (
    var minDetectionConfidence: Float = DEFAULT_DETECTION_CONFIDENCE,
    var minTrackingConfidence: Float = DEFAULT_TRACKING_CONFIDENCE,
    var minPresenceConfidence: Float = DEFAULT_PRESENCE_CONFIDENCE,
    var maxNumHands: Int = DEFAULT_NUM_HANDS,
    var maxNumFaces: Int = DEFAULT_NUM_FACES,
    var currentDelegate: Int = DELEGATE_GPU,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    // this listener is only used when running in RunningMode.LIVE_STREAM
    val multiLandmarkerHelperListener: LandmarkerListener? = null
){

    // For this example this needs to be a var so it can be reset on changes.
    // If the Hand Landmarker will not change, a lazy val would be preferable.
    private var handLandmarker: HandLandmarker? = null
    // If the Face Landmarker will not change, a lazy val would be preferable.
    private var faceLandmarker: FaceLandmarker? = null
    // If the Pose Landmarker will not change, a lazy val would be preferable.
    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupMultiLandmarker()
    }

    fun clearMultiLandmarker() {
        handLandmarker?.close()
        handLandmarker = null

        faceLandmarker?.close()
        faceLandmarker = null

        poseLandmarker?.close()
        poseLandmarker = null
    }

    // Return running status of MultiLandmarkerHelper
    fun isClose(): Boolean {
        return handLandmarker == null && faceLandmarker == null && poseLandmarker == null
    }

    // Initialize the Hand landmarker and Face landmarker and Pose landmarker using current settings on the
    // thread that is using it. CPU can be used with Landmarker
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the
    // Landmarker
    fun setupMultiLandmarker() {
        // Set general hand landmarker options
        val baseHandOptionBuilder = BaseOptions.builder()
        val baseFaceOptionBuilder = BaseOptions.builder()
        val basePoseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseHandOptionBuilder.setDelegate(Delegate.CPU)
                baseFaceOptionBuilder.setDelegate(Delegate.CPU)
                basePoseOptionBuilder.setDelegate(Delegate.CPU)
            }
            DELEGATE_GPU -> {
                baseHandOptionBuilder.setDelegate(Delegate.GPU)
                baseFaceOptionBuilder.setDelegate(Delegate.GPU)
                basePoseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseHandOptionBuilder.setModelAssetPath(MP_HAND_LANDMARKER_TASK)
        baseFaceOptionBuilder.setModelAssetPath(MP_FACE_LANDMARKER_TASK)
        basePoseOptionBuilder.setModelAssetPath(MP_POSE_LANDMARKER_TASK)

        // Check if runningMode is consistent with multiLandmarkerHelperListener
        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                if (multiLandmarkerHelperListener == null) {
                    throw IllegalStateException(
                        "multiLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
                    )
                }
            }
            else -> {
                // no-op
            }
        }

        try {
            val baseHandOptions = baseHandOptionBuilder.build()
            val baseFaceOptions = baseFaceOptionBuilder.build()
            val basePoseOptions = basePoseOptionBuilder.build()
            // Create an option builder with base options and specific
            // options only use for Hand Landmarker.
            val handOptionsBuilder =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseHandOptions)
                    .setMinHandDetectionConfidence(minDetectionConfidence)
                    .setMinTrackingConfidence(minTrackingConfidence)
                    .setMinHandPresenceConfidence(minPresenceConfidence)
                    .setNumHands(maxNumHands)
                    .setRunningMode(runningMode)

            // options only use for Face Landmarker.
            val faceOptionsBuilder =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseFaceOptions)
                    .setMinFaceDetectionConfidence(minDetectionConfidence)
                    .setMinTrackingConfidence(minTrackingConfidence)
                    .setMinFacePresenceConfidence(minPresenceConfidence)
                    .setNumFaces(maxNumFaces)
                    .setOutputFaceBlendshapes(true)
                    .setRunningMode(runningMode)

            // options only use for Pose Landmarker.
            val poseOptionsBuilder =
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(basePoseOptions)
                    .setMinPoseDetectionConfidence(minDetectionConfidence)
                    .setMinTrackingConfidence(minTrackingConfidence)
                    .setMinPosePresenceConfidence(minPresenceConfidence)
                    .setRunningMode(runningMode)

            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
            if (runningMode == RunningMode.LIVE_STREAM) {
                handOptionsBuilder
                    .setResultListener(this::returnHandLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)

                faceOptionsBuilder
                    .setResultListener(this::returnFaceLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)

                poseOptionsBuilder
                    .setResultListener(this::returnPoseLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            val handOptions = handOptionsBuilder.build()
            handLandmarker =
                HandLandmarker.createFromOptions(context, handOptions)

            val faceOptions = faceOptionsBuilder.build()
            faceLandmarker =
                FaceLandmarker.createFromOptions(context, faceOptions)

            val poseOptions = poseOptionsBuilder.build()
            poseLandmarker =
                PoseLandmarker.createFromOptions(context, poseOptions)

        } catch (e: IllegalStateException) {
            multiLandmarkerHelperListener?.onError(
                "Hand Landmarker and Face Landmarker failed to initialize. See error logs for " +
                        "details"
            )
            Log.e(
                LM_TAG, "mediapipe failed to load the task with error: " + e
                    .message
            )
        } catch (e: RuntimeException) {
            // This occurs if the model being used does not support GPU
            multiLandmarkerHelperListener?.onError(
                "Landmarker failed to initialize. See error logs for " +
                        "details", GPU_ERROR
            )
            Log.e(
                LM_TAG,
                "Image classifier failed to load model with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to MultiLandMakerHelper.
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {
        if (runningMode != RunningMode.LIVE_STREAM) {
            throw IllegalArgumentException(
                "Attempting to call detectLiveStream" +
                        " while not using RunningMode.LIVE_STREAM"
            )
        }
        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

        val matrix = Matrix().apply {
            // Rotate the frame received from the camera to be in the same direction as it'll be shown
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            // flip image if user use front camera
            if (isFrontCamera) {
                postScale(
                    -1f,
                    1f,
                    imageProxy.width.toFloat(),
                    imageProxy.height.toFloat()
                )
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            matrix, true
        )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        detectAsync(mpImage, frameTime)

        imageProxy.close()
    }

    // Run hand hand landmark using mediapipe Hand Landmarker API
    // Run face face landmark using mediapipe Face Landmarker API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        handLandmarker?.detectAsync(mpImage, frameTime)
        faceLandmarker?.detectAsync(mpImage, frameTime)
        poseLandmarker?.detectAsync(mpImage, frameTime)
        // As we're using running mode LIVE_STREAM, the landmark result will
        // be returned in returnLivestreamResult function
    }

    // Return the hand landmark result to this MultiLandmarkerHelper's caller
    private fun returnHandLivestreamResult(
        result: HandLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        multiLandmarkerHelperListener?.onHandResults(
            HandResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return the face landmark result to this MultiLandmarkerHelper's caller
    private fun returnFaceLivestreamResult(
        result: FaceLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        multiLandmarkerHelperListener?.onFaceResults(
            FaceResultBundle(
                result,
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return the pose landmark result to this MultiLandmarkerHelper's caller
    private fun returnPoseLivestreamResult(
        result: PoseLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        multiLandmarkerHelperListener?.onPoseResults(
            PoseResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return errors thrown during detection to this MultiLandmarkerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        multiLandmarkerHelperListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    companion object {
        const val LM_TAG = "LandmarkerHelper"
        private const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"
        private const val MP_FACE_LANDMARKER_TASK = "face_landmarker.task"
        private const val MP_POSE_LANDMARKER_TASK = "pose_landmarker.task"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_PRESENCE_CONFIDENCE = 0.5F
        const val DEFAULT_NUM_HANDS = 2
        const val DEFAULT_NUM_FACES = 1
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }

    data class HandResultBundle(
        val results: List<HandLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    data class FaceResultBundle(
        val result: FaceLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    data class PoseResultBundle(
        val results: List<PoseLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onHandResults(resultBundle: HandResultBundle)
        fun onFaceResults(resultBundle: FaceResultBundle)
        fun onPoseResults(resultBundle: PoseResultBundle)

        fun onEmpty() {}
    }

}