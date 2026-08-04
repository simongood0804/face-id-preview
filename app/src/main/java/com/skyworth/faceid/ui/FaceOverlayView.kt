package com.skyworth.faceid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

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

    /** 当前裁剪窗口（原图坐标，null 表示不绘制）。 */
    @Volatile private var mCropRect: RectF? = null

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

    /** 头姿文字画笔（小字）。 */
    private val mPosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 20f
        style = Paint.Style.FILL
    }

    /** 文字背景画笔（半透明）。 */
    private val mBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /** 紫色关键点画笔（5 点）。 */
    private val mKeypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 160, 32, 240)  // Purple
        style = Paint.Style.FILL
    }

    /** 黄色密集地标画笔（106 点）。 */
    private val mLandmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    /** 黄色裁剪框画笔（虚线描边）。 */
    private val mCropBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    /** 坐标系 X 轴画笔（红色，脸部朝向）。 */
    private val mAxisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 坐标系 Y 轴画笔（绿色）。 */
    private val mAxisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    /** 坐标系 Z 轴画笔（蓝色）。 */
    private val mAxisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    // 预计算弧度常量，避免每帧重复 Math.toRadians
    private val DEG2RAD = (Math.PI / 180.0).toFloat()
    private val Y_BASE_RAD = (-90f * DEG2RAD)  // Y 轴默认垂直向上
    private val Z_BASE_RAD = (180f * DEG2RAD)   // Z 轴默认水平向左

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

    /**
     * 设置裁剪窗口矩形（用于绘制黄色采样框）。
     * @param rect 原图坐标，null 时不绘制
     */
    fun setCropRect(rect: RectF?) {
        mCropRect = rect
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

            val label = buildString {
                append(face.label ?: when (face.type) {
                    FaceType.DETECTED -> "detected"
                    FaceType.SPOOF -> "spoof"
                })
                append(" ${(face.confidence * 100).toInt()}%")
            }

            // 标签背景 + 文字（名称 + 置信度）
            val labelWidth = mLabelPaint.measureText(label)
            val labelHeight = mLabelPaint.textSize
            canvas.drawRect(left, top - labelHeight - 8, left + labelWidth + 12, top, mBgPaint)
            canvas.drawText(label, left + 6, top - 6, mLabelPaint)

            // 头姿信息（小字，框下方）
            val poseText = "P:%.0f Y:%.0f R:%.0f".format(face.pitch, face.yaw, face.roll)
            val poseWidth = mPosePaint.measureText(poseText)
            val poseHeight = mPosePaint.textSize
            canvas.drawRect(left, bottom + 2, left + poseWidth + 8, bottom + poseHeight + 6, mBgPaint)
            canvas.drawText(poseText, left + 4, bottom + poseHeight + 1, mPosePaint)

            // 绘制 106 密集地标（黄色小点）
            face.denseLandmarks?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 1f, mLandmarkPaint)
            }

            // 绘制 5 关键点（紫色）
            face.keypoints?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 3f, mKeypointPaint)
            }

            // 绘制头姿朝向箭头 + 坐标系（仅 DETECTED）
            if (face.type == FaceType.DETECTED) {
                drawHeadPoseArrow(canvas, face, scaleX, scaleY)
            }
        }
    }

    // ============================================================
    // 头姿朝向箭头 + 坐标系
    // ============================================================

    /**
     * 绘制头姿坐标系：X=红色(脸部朝向)，Y=绿色，Z=蓝色。
     *
     * 原点为两眼中间点（从 5 关键点中取左眼/右眼），
     * fallback 到人脸框中心。X 轴方向由 yaw/pitch 决定。
     * 所有坐标在原图空间，需乘以 scaleX/scaleY 缩放至 View 空间。
     */
    private fun drawHeadPoseArrow(canvas: Canvas, face: FaceBox, scaleX: Float, scaleY: Float) {
        // 起点：两眼中间点（keypoints[0]=左眼, keypoints[1]=右眼）
        val startX: Float
        val startY: Float
        val kps = face.keypoints
        if (kps != null && kps.size >= 2) {
            startX = (kps[0].x + kps[1].x) / 2f
            startY = (kps[0].y + kps[1].y) / 2f
        } else {
            startX = (face.rect.left + face.rect.right) / 2f
            startY = (face.rect.top + face.rect.bottom) / 2f
        }

        val faceW = face.rect.right - face.rect.left
        val axisLen = faceW * 1.2f

        val yawRad = (-face.yaw) * DEG2RAD
        val pitchRad = face.pitch * DEG2RAD
        val rollRad = face.roll * DEG2RAD

        // 缩放至 View 空间
        val sx = startX * scaleX
        val sy = startY * scaleY

        // 绘制原点圆点（白色）
        val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawCircle(sx, sy, 5f, originPaint)

        // --- X 轴（红色）：脸部朝向，由 yaw/pitch 决定 ---
        val dx = sin(yawRad) * axisLen
        val dy = sin(-pitchRad) * axisLen
        val xEx = sx + dx * scaleX
        val xEy = sy + dy * scaleY
        canvas.drawLine(sx, sy, xEx, xEy, mAxisXPaint)
        drawArrowHead(canvas, xEx, xEy, sx, sy, mAxisXPaint)

        // --- Y 轴（绿色）：始终指向上方（鼻梁方向），仅 roll 控制旋转 ---
        val yAngle = Y_BASE_RAD + rollRad
        val yEx = sx + cos(yAngle) * axisLen * scaleX
        val yEy = sy + sin(yAngle) * axisLen * scaleY
        canvas.drawLine(sx, sy, yEx, yEy, mAxisYPaint)
        drawArrowHead(canvas, yEx, yEy, sx, sy, mAxisYPaint)

        // --- Z 轴（蓝色）：垂直于面部平面（人脸正前方），仅 roll 控制 ---
        val zAngle = Z_BASE_RAD + rollRad
        val zAxisLen = axisLen * 0.7f
        val zEx = sx + cos(zAngle) * zAxisLen
        val zEy = sy + sin(zAngle) * zAxisLen
        canvas.drawLine(sx, sy, zEx, zEy, mAxisZPaint)
        drawArrowHead(canvas, zEx, zEy, sx, sy, mAxisZPaint)
    }

    /**
     * 在线段末端绘制三角箭头。
     */
    private fun drawArrowHead(canvas: Canvas, ex: Float, ey: Float, sx: Float, sy: Float,
                              paint: Paint) {
        val arrowSize = 10f
        val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble()).toFloat()
        val path = Path()
        path.moveTo(ex, ey)
        path.lineTo(
            ex - arrowSize * cos(angle - Math.toRadians(25.0).toFloat()),
            ey - arrowSize * sin(angle - Math.toRadians(25.0).toFloat())
        )
        path.moveTo(ex, ey)
        path.lineTo(
            ex - arrowSize * cos(angle + Math.toRadians(25.0).toFloat()),
            ey - arrowSize * sin(angle + Math.toRadians(25.0).toFloat())
        )
        canvas.drawPath(path, paint)
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
        val denseLandmarks: List<PointF>? = null,
        /** 头部姿态角（度）。 */
        val pitch: Float = 0f,
        val yaw: Float = 0f,
        val roll: Float = 0f
    )

    enum class FaceType { DETECTED, SPOOF }
}
