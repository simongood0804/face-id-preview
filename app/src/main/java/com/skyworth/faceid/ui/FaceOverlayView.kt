package com.skyworth.faceid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 人脸框覆盖层。
 *
 * 接收算法检测到的人脸结果（坐标已从图像空间缩放至本 View 空间），
 * detected = 绿色画框，spoof = 红色画框。
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前帧的人脸列表。 */
    @Volatile private var mFaces: List<FaceBox> = emptyList()

    /** 绿色画框画笔（detected）。 */
    private val mGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /** 红色画框画笔（spoof）。 */
    private val mRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /** 标签文字画笔。 */
    private val mLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
    }

    /** 文字背景画笔（半透明）。 */
    private val mBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** 蓝色关键点画笔（5 点）。 */
    private val mBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    /** 黄色密集地标画笔（106 点）。 */
    private val mYellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    /**
     * 更新人脸列表并重绘。
     * 坐标 [rect] 应在原图空间，会在绘制时自动缩放至 View 尺寸。
     *
     * @param faces  人脸框列表（原图坐标）
     * @param imgW   原图宽度
     * @param imgH   原图高度
     */
    fun setFaces(faces: List<FaceBox>, imgW: Int, imgH: Int) {
        mFaces = faces
        tag = "${imgW}x${imgH}"  // 暂存原图尺寸，用于缩放
        postInvalidate()
    }

    /** 清除所有画框。 */
    fun clearFaces() {
        mFaces = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val faces = mFaces
        if (faces.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return

        // 解析原图尺寸
        val tagStr = tag as? String ?: return
        val dims = tagStr.split("x")
        if (dims.size != 2) return
        val imgW = dims[0].toFloatOrNull() ?: return
        val imgH = dims[1].toFloatOrNull() ?: return

        val scaleX = vw / imgW
        val scaleY = vh / imgH

        for (face in faces) {
            // 缩放至 View 空间
            val left = face.rect.left * scaleX
            val top = face.rect.top * scaleY
            val right = face.rect.right * scaleX
            val bottom = face.rect.bottom * scaleY
            val scaled = RectF(left, top, right, bottom)

            val paint = when (face.type) {
                FaceType.DETECTED -> mGreenPaint
                FaceType.SPOOF -> mRedPaint
            }
            val label = face.label ?: when (face.type) {
                FaceType.DETECTED -> "detected"
                FaceType.SPOOF -> "spoof"
            }

            canvas.drawRect(scaled, paint)

            // 标签背景 + 文字
            val labelWidth = mLabelPaint.measureText(label)
            val labelHeight = mLabelPaint.textSize
            canvas.drawRect(left, top - labelHeight - 8, left + labelWidth + 12, top, mBgPaint)
            canvas.drawText(label, left + 6, top - 6, mLabelPaint)

            // 置信度（小字）
            val confText = "${(face.confidence * 100).toInt()}%"
            canvas.drawText(confText, left + 6, bottom + labelHeight + 4, mLabelPaint)

            // 绘制 106 密集地标（黄色）
            face.denseLandmarks?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 2f, mYellowPaint)
            }

            // 绘制 5 关键点（蓝色）
            face.keypoints?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 4f, mBluePaint)
            }
        }
    }

    /** 单个人脸框数据。 */
    data class FaceBox(
        val rect: RectF,
        val type: FaceType,
        val confidence: Float,
        /** 显示名称，null 则使用默认文字（detected/spoof）。 */
        val label: String? = null,
        /** 5 个面部关键点（蓝色）。 */
        val keypoints: List<PointF>? = null,
        /** 106 个密集地标（黄色）。 */
        val denseLandmarks: List<PointF>? = null
    )

    enum class FaceType { DETECTED, SPOOF }
}
