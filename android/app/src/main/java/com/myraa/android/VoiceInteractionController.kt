package com.myraa.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

enum class VoiceInteractionState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    SPEAKING,
    ERROR,
    CANCELLED,
    PERMISSION_REQUIRED,
}

data class VoiceInteractionSnapshot(
    val state: VoiceInteractionState,
    val draft: String,
    val message: String,
)

class VoiceInteractionController(
    context: Context,
    private val onSnapshotChanged: (VoiceInteractionSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var textToSpeechInitializing = false
    private var textToSpeechFailed = false
    private var pendingSpeech: Pair<Long, String>? = null
    private var generation = 0L
    private var closed = false
    private var snapshot = VoiceInteractionSnapshot(
        VoiceInteractionState.IDLE,
        "",
        appContext.getString(R.string.voice_idle_message),
    )

    init {
        onSnapshotChanged(snapshot)
    }

    fun updateDraft(text: String) {
        if (closed) return
        snapshot = snapshot.copy(draft = text)
        onSnapshotChanged(snapshot)
    }

    fun startListening() {
        if (closed) return
        invalidateCurrentOperation(releaseReadySpeech = true)
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_speech_unavailable))
            return
        }

        val requestGeneration = generation
        val createdRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (_: RuntimeException) {
            publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_speech_unavailable))
            return
        }
        recognizer = createdRecognizer
        createdRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isCurrent(requestGeneration)) {
                    publish(VoiceInteractionState.LISTENING, appContext.getString(R.string.voice_listening_message))
                }
            }

            override fun onBeginningOfSpeech() {
                if (isCurrent(requestGeneration)) {
                    publish(VoiceInteractionState.LISTENING, appContext.getString(R.string.voice_listening_message))
                }
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (isCurrent(requestGeneration)) {
                    publish(VoiceInteractionState.PROCESSING, appContext.getString(R.string.voice_processing_message))
                }
            }

            override fun onError(error: Int) {
                if (!isCurrent(requestGeneration)) return
                val (state, message) = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        VoiceInteractionState.PERMISSION_REQUIRED to appContext.getString(R.string.voice_permission_denied)
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        VoiceInteractionState.ERROR to appContext.getString(R.string.voice_no_speech)
                    else ->
                        VoiceInteractionState.ERROR to appContext.getString(R.string.voice_speech_failed)
                }
                finishRecognition(requestGeneration)
                publish(state, message)
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrent(requestGeneration)) return
                finishRecognition(requestGeneration)
                val recognizedText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (recognizedText.isBlank()) {
                    publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_no_speech))
                } else {
                    snapshot = snapshot.copy(draft = recognizedText)
                    publish(VoiceInteractionState.IDLE, appContext.getString(R.string.voice_draft_message))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrent(requestGeneration)) return
                val partialText = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partialText.isNullOrBlank()) {
                    snapshot = snapshot.copy(draft = partialText)
                    onSnapshotChanged(snapshot)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        publish(VoiceInteractionState.PROCESSING, appContext.getString(R.string.voice_starting_message))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            createdRecognizer.startListening(intent)
        } catch (_: SecurityException) {
            finishRecognition(requestGeneration)
            publish(VoiceInteractionState.PERMISSION_REQUIRED, appContext.getString(R.string.voice_permission_denied))
        } catch (_: RuntimeException) {
            finishRecognition(requestGeneration)
            publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_speech_failed))
        }
    }

    fun permissionRequired() {
        if (!closed) publish(VoiceInteractionState.PERMISSION_REQUIRED, appContext.getString(R.string.voice_permission_message))
    }

    fun permissionDenied() {
        if (!closed) publish(VoiceInteractionState.PERMISSION_REQUIRED, appContext.getString(R.string.voice_permission_denied))
    }

    fun speakDraft() {
        if (closed) return
        val text = snapshot.draft.trim()
        if (text.isEmpty()) {
            publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_empty_draft))
            return
        }

        invalidateCurrentOperation()
        if (textToSpeechFailed || textToSpeech == null) {
            if (textToSpeechFailed) {
                publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_tts_unavailable))
            } else {
                val requestGeneration = generation
                pendingSpeech = requestGeneration to text
                publish(VoiceInteractionState.PROCESSING, appContext.getString(R.string.voice_tts_preparing))
                initializeTextToSpeech()
            }
            return
        }

        val requestGeneration = generation
        if (textToSpeechReady) {
            startSpeech(textToSpeech ?: return, requestGeneration, text)
        } else {
            pendingSpeech = requestGeneration to text
            publish(VoiceInteractionState.PROCESSING, appContext.getString(R.string.voice_tts_preparing))
        }
    }

    fun cancel() {
        if (closed) return
        invalidateCurrentOperation(releaseReadySpeech = true)
        publish(VoiceInteractionState.CANCELLED, appContext.getString(R.string.voice_cancelled_message))
    }

    fun shutdown() {
        if (closed) return
        closed = true
        generation += 1
        pendingSpeech = null
        releaseRecognizer()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun invalidateCurrentOperation(releaseReadySpeech: Boolean = false) {
        generation += 1
        pendingSpeech = null
        releaseRecognizer()
        textToSpeech?.stop()
        if (releaseReadySpeech) releaseReadyTextToSpeech()
    }

    private fun startSpeech(engine: TextToSpeech, requestGeneration: Long, text: String) {
        if (!isCurrent(requestGeneration)) return
        val result = try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestGeneration.toString())
        } catch (_: RuntimeException) {
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            finishSpeech(requestGeneration, VoiceInteractionState.ERROR, appContext.getString(R.string.voice_tts_failed))
        } else {
            publish(VoiceInteractionState.PROCESSING, appContext.getString(R.string.voice_tts_preparing))
        }
    }

    private fun initializeTextToSpeech() {
        if (textToSpeechInitializing || textToSpeechReady || textToSpeechFailed) return
        textToSpeechInitializing = true
        val engine = try {
            TextToSpeech(appContext) { status ->
                mainHandler.post { completeTextToSpeechInitialization(status) }
            }
        } catch (_: RuntimeException) {
            failTextToSpeechInitialization()
            return
        }
        textToSpeech = engine
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val requestGeneration = utteranceId?.toLongOrNull() ?: return
                    mainHandler.post {
                        if (isCurrent(requestGeneration)) {
                            publish(VoiceInteractionState.SPEAKING, appContext.getString(R.string.voice_state_speaking))
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    val requestGeneration = utteranceId?.toLongOrNull() ?: return
                    mainHandler.post {
                        finishSpeech(
                            requestGeneration,
                            VoiceInteractionState.IDLE,
                            appContext.getString(R.string.voice_tts_complete),
                        )
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val requestGeneration = utteranceId?.toLongOrNull() ?: return
                    mainHandler.post {
                        finishSpeech(
                            requestGeneration,
                            VoiceInteractionState.CANCELLED,
                            appContext.getString(R.string.voice_cancelled_message),
                        )
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val requestGeneration = utteranceId?.toLongOrNull() ?: return
                    reportSpeechError(requestGeneration)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    val requestGeneration = utteranceId?.toLongOrNull() ?: return
                    reportSpeechError(requestGeneration)
                }
            })
        } catch (_: RuntimeException) {
            failTextToSpeechInitialization()
        }
    }

    private fun completeTextToSpeechInitialization(status: Int) {
        if (closed) return
        textToSpeechInitializing = false
        val engine = textToSpeech
        val languageAvailable = status == TextToSpeech.SUCCESS && engine != null &&
            try {
                engine.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
            } catch (_: RuntimeException) {
                false
            }
        if (!languageAvailable || engine == null) {
            failTextToSpeechInitialization()
            return
        }

        textToSpeechReady = true
        val pending = pendingSpeech ?: run {
            releaseReadyTextToSpeech()
            return
        }
        pendingSpeech = null
        if (isCurrent(pending.first)) startSpeech(engine, pending.first, pending.second)
    }

    private fun failTextToSpeechInitialization() {
        textToSpeechInitializing = false
        textToSpeechReady = false
        textToSpeechFailed = true
        textToSpeech?.shutdown()
        textToSpeech = null
        val pending = pendingSpeech
        pendingSpeech = null
        if (pending != null && isCurrent(pending.first)) {
            publish(VoiceInteractionState.ERROR, appContext.getString(R.string.voice_tts_unavailable))
        }
    }

    private fun reportSpeechError(requestGeneration: Long) {
        mainHandler.post {
            finishSpeech(requestGeneration, VoiceInteractionState.ERROR, appContext.getString(R.string.voice_tts_failed))
        }
    }

    private fun finishSpeech(requestGeneration: Long, state: VoiceInteractionState, message: String) {
        if (!isCurrent(requestGeneration)) return
        generation += 1
        pendingSpeech = null
        publish(state, message)
        releaseReadyTextToSpeech()
    }

    private fun releaseReadyTextToSpeech() {
        if (textToSpeechInitializing) return
        val engine = textToSpeech
        textToSpeech = null
        textToSpeechReady = false
        engine?.shutdown()
    }

    private fun releaseRecognizer(requestGeneration: Long? = null, cancel: Boolean = true) {
        if (requestGeneration != null && requestGeneration != generation) return
        recognizer?.let { activeRecognizer ->
            recognizer = null
            if (cancel) activeRecognizer.cancel()
            activeRecognizer.destroy()
        }
    }

    private fun finishRecognition(requestGeneration: Long) {
        if (!isCurrent(requestGeneration)) return
        generation += 1
        releaseRecognizer(cancel = false)
    }

    private fun isCurrent(requestGeneration: Long): Boolean =
        !closed && requestGeneration == generation

    private fun publish(state: VoiceInteractionState, message: String) {
        if (closed) return
        snapshot = snapshot.copy(state = state, message = message)
        onSnapshotChanged(snapshot)
    }
}
