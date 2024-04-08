package io.sentry.android.replay.capture

import io.sentry.DateUtils
import io.sentry.Hint
import io.sentry.ReplayRecording
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.SentryReplayEvent
import io.sentry.SentryReplayEvent.ReplayType
import io.sentry.SentryReplayEvent.ReplayType.SESSION
import io.sentry.android.replay.ReplayCache
import io.sentry.android.replay.ReplayIntegration
import io.sentry.android.replay.ReplayIntegration.Companion
import io.sentry.android.replay.ReplayIntegration.ReplayExecutorServiceThreadFactory
import io.sentry.android.replay.util.gracefullyShutdown
import io.sentry.android.replay.util.submitSafely
import io.sentry.protocol.SentryId
import io.sentry.rrweb.RRWebMetaEvent
import io.sentry.rrweb.RRWebVideoEvent
import io.sentry.transport.ICurrentDateProvider
import io.sentry.util.FileUtils
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

abstract class BaseCaptureStrategy(
    private val options: SentryOptions,
    private val dateProvider: ICurrentDateProvider
) : CaptureStrategy {

    internal companion object {
        private const val TAG = "CaptureStrategy"
    }

    protected var cache: ReplayCache? = null
    protected val currentReplayId = AtomicReference(SentryId.EMPTY_ID)
    protected val segmentTimestamp = AtomicReference<Date>()
    protected val replayStartTimestamp = AtomicLong()
    protected val currentSegment = AtomicInteger(0)

    protected val replayExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor(ReplayExecutorServiceThreadFactory())
    }

    override fun start(segmentId: Int, replayId: SentryId, cleanupOldReplays: Boolean) {
        currentSegment.set(segmentId)
        currentReplayId.set(replayId)

        if (cleanupOldReplays) {
            replayExecutor.submitSafely(options, "${TAG}.replays_cleanup") {
                // clean up old replays
                options.cacheDirPath?.let { cacheDir ->
                    File(cacheDir).listFiles { dir, name ->
                        // TODO: also exclude persisted replay_id from scope when implementing ANRs
                        if (name.startsWith("replay_") && !name.contains(
                                currentReplayId.get().toString()
                            )
                        ) {
                            FileUtils.deleteRecursively(File(dir, name))
                        }
                        false
                    }
                }
            }
        }

        cache = ReplayCache(options, currentReplayId.get(), recorderConfig)

        // TODO: replace it with dateProvider.currentTimeMillis to also test it
        segmentTimestamp.set(DateUtils.getCurrentDateTime())
        replayStartTimestamp.set(dateProvider.currentTimeMillis)
        // TODO: finalize old recording if there's some left on disk and send it using the replayId from persisted scope (e.g. for ANRs)
    }

    override fun resume() {
        // TODO: replace it with dateProvider.currentTimeMillis to also test it
        segmentTimestamp.set(DateUtils.getCurrentDateTime())
    }

    protected fun createAndCaptureSegment(
        duration: Long,
        currentSegmentTimestamp: Date,
        replayId: SentryId,
        segmentId: Int,
        height: Int,
        width: Int,
        replayType: ReplayType = SESSION,
        hint: Hint? = null
    ): Long? {
        val generatedVideo = cache?.createVideoOf(
            duration,
            currentSegmentTimestamp.time,
            segmentId,
            height,
            width
        ) ?: return null

        val (video, frameCount, videoDuration) = generatedVideo
        captureReplay(
            video,
            replayId,
            currentSegmentTimestamp,
            segmentId,
            height,
            width,
            frameCount,
            videoDuration,
            replayType,
            hint
        )
        return videoDuration
    }

    private fun captureReplay(
        video: File,
        currentReplayId: SentryId,
        segmentTimestamp: Date,
        segmentId: Int,
        height: Int,
        width: Int,
        frameCount: Int,
        duration: Long,
        replayType: ReplayType,
        hint: Hint? = null
    ) {
        val replay = SentryReplayEvent().apply {
            eventId = currentReplayId
            replayId = currentReplayId
            this.segmentId = segmentId
            this.timestamp = DateUtils.getDateTime(segmentTimestamp.time + duration)
            if (segmentId == 0) {
                replayStartTimestamp = segmentTimestamp
            }
            this.replayType = replayType
            videoFile = video
        }

        val recording = ReplayRecording().apply {
            this.segmentId = segmentId
            payload = listOf(
                RRWebMetaEvent().apply {
                    this.timestamp = segmentTimestamp.time
                    this.height = height
                    this.width = width
                },
                RRWebVideoEvent().apply {
                    this.timestamp = segmentTimestamp.time
                    this.segmentId = segmentId
                    this.durationMs = duration
                    this.frameCount = frameCount
                    size = video.length()
                    frameRate = recorderConfig.frameRate
                    this.height = height
                    this.width = width
                    // TODO: support non-fullscreen windows later
                    left = 0
                    top = 0
                }
            )
        }

//        hub?.captureReplay(replay, (hint ?: Hint()).apply { replayRecording = recording })
    }

    override fun close() {
        replayExecutor.gracefullyShutdown(options)
    }

    private class ReplayExecutorServiceThreadFactory : ThreadFactory {
        private var cnt = 0
        override fun newThread(r: Runnable): Thread {
            val ret = Thread(r, "SentryReplayIntegration-" + cnt++)
            ret.setDaemon(true)
            return ret
        }
    }
}
