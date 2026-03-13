package com.esc.begu.mediapipe

import androidx.lifecycle.ViewModel

/**
 *  This ViewModel is used to store hand, face and pose landmarker helper settings
 */

class MultiMainViewModel : ViewModel() {

    private var _delegate: Int = MultiLandmarkerHelper.DELEGATE_GPU
    private var _minDetectionConfidence: Float =
        MultiLandmarkerHelper.DEFAULT_DETECTION_CONFIDENCE
    private var _minTrackingConfidence: Float = MultiLandmarkerHelper
        .DEFAULT_TRACKING_CONFIDENCE
    private var _minPresenceConfidence: Float = MultiLandmarkerHelper
        .DEFAULT_PRESENCE_CONFIDENCE
    private var _maxHands: Int = MultiLandmarkerHelper.DEFAULT_NUM_HANDS
    private var _maxFaces: Int = MultiLandmarkerHelper.DEFAULT_NUM_FACES

    val currentDelegate: Int get() = _delegate
    val currentMinDetectionConfidence: Float
        get() =
            _minDetectionConfidence
    val currentMinTrackingConfidence: Float
        get() =
            _minTrackingConfidence
    val currentMinPresenceConfidence: Float
        get() =
            _minPresenceConfidence
    val currentMaxHands: Int get() = _maxHands
    val currentMaxFaces: Int get() = _maxFaces

    fun setDelegate(delegate: Int) {
        _delegate = delegate
    }

    fun setMinDetectionConfidence(confidence: Float) {
        _minDetectionConfidence = confidence
    }
    fun setMinTrackingConfidence(confidence: Float) {
        _minTrackingConfidence = confidence
    }
    fun setMinPresenceConfidence(confidence: Float) {
        _minPresenceConfidence = confidence
    }

    fun setMaxFaces(maxResults: Int) {
        _maxFaces = maxResults
    }

    fun setMaxHands(maxResults: Int) {
        _maxHands = maxResults
    }

}