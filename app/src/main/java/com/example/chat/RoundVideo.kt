package com.example.chat

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

private suspend fun awaitCameraProvider(ctx: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(ctx))
    }

/**
 * Видеосообщение-«кружок» — запись внутри приложения через CameraX, без системной камеры. Кадр обрезается
 * до квадрата (1:1) уже на уровне захвата (ViewPort), одинаково и в превью, и в самом файле — итоговое видео
 * само по себе квадратное, а кружком его показывает только UI (Modifier.clip(CircleShape) в ChatScreen),
 * ровно как это устроено и в Telegram. Фронтальная камера по умолчанию — это видео*сообщение*, а не съёмка
 * происходящего вокруг.
 *
 * Ограничение: обрезка кадра рассчитана на портретную ориентацию (Surface.ROTATION_0) — экран приложения
 * ориентацию не блокирует, но переворачивать телефон во время записи кружка никто в здравом уме не станет.
 */
class RoundVideoRecorder(private val ctx: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var finalizeOk: CompletableDeferred<Boolean>? = null
    private var file: File? = null
    private var startedAt = 0L

    /** Поднимает камеру и превью; звать при появлении кружка на экране, пока идёт запись. */
    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = awaitCameraProvider(ctx)
        cameraProvider = provider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        videoCapture = capture
        val viewPort = ViewPort.Builder(Rational(1, 1), Surface.ROTATION_0).build()
        val group = UseCaseGroup.Builder()
            .addUseCase(preview).addUseCase(capture).setViewPort(viewPort).build()
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, group)
    }

    /** Отпускает камеру; звать, когда кружок убран с экрана (запись к этому моменту уже должна быть завершена). */
    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
    }

    @SuppressLint("MissingPermission")   // разрешения (камера, микрофон) уже запрошены выше по вызову, в UI
    fun start() {
        val capture = videoCapture ?: return
        val f = File(ctx.cacheDir, "roundvid_${System.currentTimeMillis()}.mp4")
        file = f
        startedAt = SystemClock.elapsedRealtime()
        val deferred = CompletableDeferred<Boolean>()
        finalizeOk = deferred
        activeRecording = capture.output.prepareRecording(ctx, FileOutputOptions.Builder(f).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                if (event is VideoRecordEvent.Finalize) deferred.complete(!event.hasError())
            }
    }

    /** Останавливает запись и ждёт, пока файл действительно допишется на диск (Finalize-событие CameraX) —
        иначе можно получить и попытаться отправить недописанный файл. Возвращает файл и длительность
        в секундах, либо null — если запись слишком короткая или не удалась. */
    suspend fun stop(): Pair<File, Double>? {
        val r = activeRecording ?: return null
        activeRecording = null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        r.stop()
        val ok = finalizeOk?.await() ?: false
        finalizeOk = null
        val f = file; file = null
        return if (!ok || seconds < 0.6 || f == null || !f.exists() || f.length() == 0L) {
            f?.delete(); null
        } else f to seconds
    }

    suspend fun cancel() {
        val r = activeRecording ?: run { file?.delete(); file = null; return }
        activeRecording = null
        r.stop()
        finalizeOk?.await()
        finalizeOk = null
        file?.delete(); file = null
    }
}
