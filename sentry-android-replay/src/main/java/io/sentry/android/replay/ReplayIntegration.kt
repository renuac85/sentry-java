package io.sentry.android.replay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import io.sentry.Hint
import io.sentry.IHub
import io.sentry.Integration
import io.sentry.ReplayController
import io.sentry.SentryEvent
import io.sentry.SentryIntegrationPackageStorage
import io.sentry.SentryLevel.DEBUG
import io.sentry.SentryLevel.INFO
import io.sentry.SentryOptions
import io.sentry.android.replay.capture.BufferCaptureStrategy
import io.sentry.android.replay.capture.CaptureStrategy
import io.sentry.android.replay.capture.SessionCaptureStrategy
import io.sentry.android.replay.util.sample
import io.sentry.protocol.SentryId
import io.sentry.transport.ICurrentDateProvider
import io.sentry.util.IntegrationUtils.addIntegrationToSdkVersion
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

class ReplayIntegration(
    private val context: Context,
    private val dateProvider: ICurrentDateProvider
) : Integration, Closeable, ScreenshotRecorderCallback, ReplayController, ComponentCallbacks {

    private lateinit var options: SentryOptions
    private var hub: IHub? = null
    private var recorder: WindowRecorder? = null
    private val random by lazy { SecureRandom() }

    // TODO: probably not everything has to be thread-safe here
    private val isEnabled = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private var captureStrategy: CaptureStrategy? = null

    private lateinit var recorderConfig: ScreenshotRecorderConfig

    override fun register(hub: IHub, options: SentryOptions) {
        this.options = options

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            options.logger.log(INFO, "Session replay is only supported on API 26 and above")
            return
        }

        if (!options.experimental.sessionReplay.isSessionReplayEnabled &&
            !options.experimental.sessionReplay.isSessionReplayForErrorsEnabled
        ) {
            options.logger.log(INFO, "Session replay is disabled, no sample rate specified")
            return
        }

        this.hub = hub
        recorder = WindowRecorder(options, this)
        isEnabled.set(true)

        try {
            context.registerComponentCallbacks(this)
        } catch (e: Throwable) {
            options.logger.log(INFO, "ComponentCallbacks is not available, orientation changes won't be handled by Session replay", e)
        }

        addIntegrationToSdkVersion(javaClass)
        SentryIntegrationPackageStorage.getInstance()
            .addPackage("maven:io.sentry:sentry-android-replay", BuildConfig.VERSION_NAME)
    }

    override fun isRecording() = isRecording.get()

    override fun start() {
        // TODO: add lifecycle state instead and manage it in start/pause/resume/stop
        if (!isEnabled.get()) {
            return
        }

        if (isRecording.getAndSet(true)) {
            options.logger.log(
                DEBUG,
                "Session replay is already being recorded, not starting a new one"
            )
            return
        }

        val isFullSession = random.sample(options.experimental.sessionReplay.sessionSampleRate)
        if (!isFullSession && !options.experimental.sessionReplay.isSessionReplayForErrorsEnabled) {
            options.logger.log(INFO, "Session replay is not started, full session was not sampled and errorSampleRate is not specified")
            return
        }

        recorderConfig = ScreenshotRecorderConfig.from(context, options.experimental.sessionReplay)
        captureStrategy = if (isFullSession) {
            SessionCaptureStrategy(options, hub, dateProvider, recorderConfig)
        } else {
            BufferCaptureStrategy(options, hub, dateProvider, recorderConfig, random)
        }

        captureStrategy?.start()
        recorder?.startRecording(recorderConfig)
    }

    override fun resume() {
        if (!isEnabled.get() || !isRecording.get()) {
            return
        }

        captureStrategy?.resume()
        recorder?.resume()
    }

    override fun sendReplayForEvent(event: SentryEvent, hint: Hint) {
        if (!isEnabled.get() || !isRecording.get()) {
            return
        }

        if (!(event.isErrored || event.isCrashed)) {
            options.logger.log(DEBUG, "Event is not error or crash, not capturing for event %s", event.eventId)
            return
        }

        if (SentryId.EMPTY_ID.equals(captureStrategy?.currentReplayId?.get())) {
            options.logger.log(DEBUG, "Replay id is not set, not capturing for event %s", event.eventId)
            return
        }

        captureStrategy?.sendReplayForEvent(event, hint, onSegmentSent = { captureStrategy?.currentSegment?.getAndIncrement() })
        captureStrategy = captureStrategy?.convert()
    }

    override fun pause() {
        if (!isEnabled.get() || !isRecording.get()) {
            return
        }

        recorder?.pause()
        captureStrategy?.pause()
    }

    override fun stop() {
        if (!isEnabled.get() || !isRecording.get()) {
            return
        }

        recorder?.stopRecording()
        captureStrategy?.stop()
        isRecording.set(false)
        captureStrategy = null
    }

    override fun onScreenshotRecorded(bitmap: Bitmap) {
        captureStrategy?.onScreenshotRecorded(bitmap)
    }

    override fun close() {
        if (!isEnabled.get()) {
            return
        }

        try {
            context.unregisterComponentCallbacks(this)
        } catch (ignored: Throwable) {
        }
        stop()
        captureStrategy?.close()
        captureStrategy = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!isEnabled.get() || !isRecording.get()) {
            return
        }

        recorder?.stopRecording()

        // refresh config based on new device configuration
        recorderConfig = ScreenshotRecorderConfig.from(context, options.experimental.sessionReplay)
        captureStrategy?.onConfigurationChanged(recorderConfig)

        recorder?.startRecording(recorderConfig)
    }

    override fun onLowMemory() = Unit
}
