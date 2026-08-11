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

    /** 视线方向线画笔（橙色）。 */
    private val mGazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 140, 0)  // Orange
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    /** 分心提示文字画笔（红色）。 */
    private val mDistractedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 40f
        style = Paint.Style.FILL
    }

    /** 视线文字画笔（白色小字）。 */
    private val mGazeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 26f
        style = Paint.Style.FILL
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

            // 视线信息（小字，头姿下方）
            val gazeText = "G:yaw%.0f pit%.0f v%d c%d d%d".format(
                face.gazeYaw, face.gazePitch,
                if (face.gazeValid > 0f) 1 else 0,
                if (face.gazeCalibrated > 0f) 1 else 0,
                if (face.gazeDistracted > 0f) 1 else 0)
            val gazeWidth = mPosePaint.measureText(gazeText)
            canvas.drawRect(left, bottom + poseHeight + 8, left + gazeWidth + 8,
                bottom + poseHeight * 2 + 12, mBgPaint)
            canvas.drawText(gazeText, left + 4, bottom + poseHeight * 2 + 7, mPosePaint)

            // 绘制 106 密集地标（黄色小点）
            face.denseLandmarks?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 1f, mLandmarkPaint)
            }

            // 绘制 5 关键点（紫色）
            face.keypoints?.forEach { pt ->
                canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 3f, mKeypointPaint)
            }

            // 头姿坐标轴 + 视线（仅 DETECTED）
            if (face.type == FaceType.DETECTED) {
                drawHeadPoseArrow(canvas, face, scaleX, scaleY)
                drawGaze(canvas, face, scaleX, scaleY)
            }
        }

        // 左侧输出当前朝向 zone 信息
        drawZonePanel(canvas, faces)
    }

    /**
     * 在画面左侧输出当前朝向的 DMS zone 信息。
     * 顶部显示当前 zone 名称 + 坐标值；下方列出全部 zone 并高亮当前命中项。
     */
    private fun drawZonePanel(canvas: Canvas, faces: List<FaceBox>) {
        val face = faces.firstOrNull() ?: return
        val zoneId = face.zoneId.toInt()
        val zoneName = if (zoneId in ZONE_NAMES.indices) ZONE_NAMES[zoneId] else "UNKNOWN($zoneId)"

        val panelX = 16f
        val panelTop = 16f
        val lineH = 24f
        val pad = 10f
        val textSize = 18f

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            this.textSize = textSize
            style = Paint.Style.FILL
        }
        val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            this.textSize = textSize
            style = Paint.Style.FILL
        }

        // 当前 zone 名称（含中文翻译）
        val zoneCn = if (zoneId in ZONE_NAMES_CN.indices) ZONE_NAMES_CN[zoneId] else ""
        // 标题行：当前 zone
        val title = "Zone: $zoneName $zoneCn"
        val titleH = textSize + 6
        // 背景
        val panelW = 300f
        val totalH = titleH + ZONE_NAMES.size * lineH + pad * 2
        canvas.drawRect(panelX, panelTop, panelX + panelW, panelTop + totalH, mBgPaint)

        // 当前 zone 标题（高亮）
        canvas.drawText(title, panelX + pad, panelTop + pad + textSize, highlightPaint)

        // 全部 zone 列表，英文名 + 中文翻译，当前命中的高亮
        var y = panelTop + pad + titleH + textSize
        for (i in ZONE_NAMES.indices) {
            val paint = if (i == zoneId) highlightPaint else dimPaint
            val marker = if (i == zoneId) ">> " else "   "
            val cn = if (i in ZONE_NAMES_CN.indices) ZONE_NAMES_CN[i] else ""
            canvas.drawText("$marker${ZONE_NAMES[i]}  $cn", panelX + pad, y, paint)
            y += lineH
        }
    }

    // ============================================================
    // 视线追踪可视化
    // ============================================================

    /**
     * 绘制左右眼两条视线方向线 + 分心状态提示。
     * 左眼起点 = 106 点地标 index 88（左瞳），右眼起点 = index 89（右瞳），
     * 各自以同一 gazeYaw/gazePitch 方向延伸（橙色线）。
     * 分心时显示红色 "DISTRACTED" 提示。
     */
    private fun drawGaze(canvas: Canvas, face: FaceBox, scaleX: Float, scaleY: Float) {
        if (face.gazeValid <= 0f) return

        val faceW = face.rect.right - face.rect.left
        val gazeLen = faceW * 1.6f  // 视线线比头姿线更长，便于观察
        val gazeYawRad = face.gazeYaw * DEG2RAD
        val gazePitchRad = face.gazePitch * DEG2RAD
        val dx = sin(gazeYawRad) * gazeLen * scaleX
        val dy = sin(-gazePitchRad) * gazeLen * scaleY

        // 左右眼起点：5 关键点（index 0=左眼, 1=右眼，即瞳孔位置）。
        // 算法只输出一个视线方向（gazeYaw/gazePitch），因此左右眼共用同一方向，
        // 但各自从自己的瞳孔点出发画线。
        val kps = face.keypoints
        val leftEye = when {
            kps != null && kps.size >= 1 -> kps[0]
            else -> PointF(face.rect.left + (face.rect.right - face.rect.left) * 0.4f,
                           (face.rect.top + face.rect.bottom) / 2f)
        }
        val rightEye = when {
            kps != null && kps.size >= 2 -> kps[1]
            else -> PointF(face.rect.left + (face.rect.right - face.rect.left) * 0.6f,
                           (face.rect.top + face.rect.bottom) / 2f)
        }

        drawSingleGaze(canvas, leftEye, dx, dy, scaleX, scaleY)
        drawSingleGaze(canvas, rightEye, dx, dy, scaleX, scaleY)

        // 分心提示（显示在人脸框上方）
        if (face.gazeDistracted > 0f) {
            canvas.drawText("DISTRACTED",
                face.rect.left * scaleX, face.rect.top * scaleY - 10f, mDistractedPaint)
        }
    }

    /**
     * 以单只眼睛为起点绘制一条视线线。
     *
     * @param eye 眼睛起点（原图坐标）
     * @param dx  水平方向增量（已缩放）
     * @param dy  垂直方向增量（已缩放）
     */
    private fun drawSingleGaze(canvas: Canvas, eye: PointF, dx: Float, dy: Float,
                               scaleX: Float, scaleY: Float) {
        val sx = eye.x * scaleX
        val sy = eye.y * scaleY
        val ex = sx + dx
        val ey = sy + dy
        canvas.drawLine(sx, sy, ex, ey, mGazePaint)
        drawArrowHead(canvas, ex, ey, sx, sy, mGazePaint)
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
        val roll: Float = 0f,
        /** 视线是否有效（1=有效）。 */
        val gazeValid: Float = 0f,
        /** 视线偏航角（度）。 */
        val gazeYaw: Float = 0f,
        /** 视线俯仰角（度）。 */
        val gazePitch: Float = 0f,
        /** 是否已标定（1=已标定）。 */
        val gazeCalibrated: Float = 0f,
        /** 是否分心（1=分心）。 */
        val gazeDistracted: Float = 0f,
        /** DMS 分区 ID。 */
        val zoneId: Float = 0f
    )

    enum class FaceType { DETECTED, SPOOF }

    companion object {
        /** DMS 分区 ID → 名称映射（与 C 侧 InitDefaultZones 对齐）。 */
        private val ZONE_NAMES = arrayOf(
            "FORWARD",                    // 0
            "DRV_LEFT_KNEE",              // 1
            "DRV_RIGHT_KNEE",             // 2
            "DRV_BELT",                   // 3
            "PASS_FOOTWELL",              // 4
            "PASS_SEAT",                  // 5
            "GLOVEBOX",                   // 6
            "DRV_LEFT_VENT",              // 7
            "DRV_RIGHT_VENT",             // 8
            "DASHBOARD",                  // 9
            "STEERING_WHEEL",             // 10
            "GEAR_SELECTOR",              // 11
            "HVAC",                       // 12
            "INFOTAINMENT",               // 13
            "CENTER_CONSOLE"              // 14
        )

        /** DMS 分区 ID → 中文名称映射（与 ZONE_NAMES 一一对应）。 */
        private val ZONE_NAMES_CN = arrayOf(
            "正视前方",     // 0  FORWARD
            "驾驶左膝",     // 1  DRV_LEFT_KNEE
            "驾驶右膝",     // 2  DRV_RIGHT_KNEE
            "安全带",       // 3  DRV_BELT
            "副驾脚部",     // 4  PASS_FOOTWELL
            "副驾驶座",     // 5  PASS_SEAT
            "手套箱",       // 6  GLOVEBOX
            "驾驶左出风口", // 7  DRV_LEFT_VENT
            "驾驶右出风口", // 8  DRV_RIGHT_VENT
            "仪表台",       // 9  DASHBOARD
            "方向盘",       // 10 STEERING_WHEEL
            "挡位选择器",   // 11 GEAR_SELECTOR
            "空调面板",     // 12 HVAC
            "信息娱乐屏",   // 13 INFOTAINMENT
            "中央扶手箱"    // 14 CENTER_CONSOLE
        )
    }
}
