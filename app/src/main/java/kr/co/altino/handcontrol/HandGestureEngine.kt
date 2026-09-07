package kr.co.altino.handcontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

class HandGestureEngine(context: Context) : AutoCloseable {
    private val handLandmarker: HandLandmarker
    private var lastAnalyzeMs = 0L

    init {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
            )
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.50f)
            .build()
        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun analyze(image: ImageProxy): DriveCommand? {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAnalyzeMs < 80) return null
        lastAnalyzeMs = now

        val bitmap = rgbaImageProxyToBitmap(image)
        val rotated = rotateAndMirror(bitmap, image.imageInfo.rotationDegrees.toFloat())
        if (rotated !== bitmap) bitmap.recycle()

        return try {
            val mpImage = BitmapImageBuilder(rotated).build()
            val result = handLandmarker.detectForVideo(mpImage, now)
            if (result.landmarks().isEmpty()) DriveCommand.UNKNOWN
            else classify(result.landmarks()[0])
        } finally {
            rotated.recycle()
        }
    }

    private fun classify(lm: List<NormalizedLandmark>): DriveCommand {
        if (lm.size < 21) return DriveCommand.UNKNOWN
        val index = fingerExtended(lm, 5, 6, 8)
        val middle = fingerExtended(lm, 9, 10, 12)
        val ring = fingerExtended(lm, 13, 14, 16)
        val pinky = fingerExtended(lm, 17, 18, 20)
        return when {
            !index && !middle && !ring && !pinky -> DriveCommand.STOP
            index && !middle && !ring && !pinky -> DriveCommand.BACKWARD
            index && middle && !ring && !pinky -> DriveCommand.LEFT
            index && middle && ring && !pinky -> DriveCommand.RIGHT
            index && middle && ring && pinky -> DriveCommand.FORWARD
            else -> DriveCommand.UNKNOWN
        }
    }

    private fun fingerExtended(lm: List<NormalizedLandmark>, mcp: Int, pip: Int, tip: Int): Boolean {
        val angle = angleDeg(lm[mcp], lm[pip], lm[tip])
        val wrist = lm[0]
        val tipDistance = distance(wrist, lm[tip])
        val pipDistance = distance(wrist, lm[pip])
        return angle > 155.0 && tipDistance > pipDistance * 1.08
    }

    private fun angleDeg(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Double {
        val bax = a.x() - b.x(); val bay = a.y() - b.y(); val baz = a.z() - b.z()
        val bcx = c.x() - b.x(); val bcy = c.y() - b.y(); val bcz = c.z() - b.z()
        val dot = bax * bcx + bay * bcy + baz * bcz
        val n1 = sqrt((bax * bax + bay * bay + baz * baz).toDouble())
        val n2 = sqrt((bcx * bcx + bcy * bcy + bcz * bcz).toDouble())
        if (n1 == 0.0 || n2 == 0.0) return 0.0
        val cos = (dot / (n1 * n2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Double = sqrt(
        (a.x() - b.x()).toDouble().pow(2.0) +
            (a.y() - b.y()).toDouble().pow(2.0) +
            (a.z() - b.z()).toDouble().pow(2.0)
    )

    private fun rgbaImageProxyToBitmap(image: ImageProxy): Bitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val buffer = image.planes[0].buffer
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun rotateAndMirror(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees); postScale(-1f, 1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun close() { handLandmarker.close() }
}
