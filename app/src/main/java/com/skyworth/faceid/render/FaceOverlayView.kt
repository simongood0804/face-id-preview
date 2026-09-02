package com.skyworth.faceid.render

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
import kotlin.math.hypot
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

    /**
     * 绘制模式（FACEP-011 功能划分，按模块分区绘制）：
     * 0=DISTRACTION（全部），1=RECOGNITION（仅框/名称/置信度），2=FATIGUE（+眼嘴状态）。
     */
    @Volatile var drawMode: Int = DRAW_MODE_DISTRACTION

    /** 当前裁剪窗口（原图坐标，null 表示不绘制）。 */
    @Volatile private var mCropRect: RectF? = null

    /** 分心提示是否显示（固定屏幕位置，不随人脸移动）。 */
    @Volatile private var mDistractShown = false

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

    /** 粉色 68 密集地标画笔（疲劳模块绘制眼嘴点位）。 */
    private val mLandmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 105, 180)  // HotPink
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
    /** π 弧度，用于轴的 180° 反向。 */
    private val PI_RAD = (Math.PI).toFloat()
    /** 轴端点到原点最小 View 长度(px)：过短时视为退化，不画箭头，避免 atan2(0,0) 异常。 */
    private val MIN_ARROW_LEN = 6f

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

    /**
     * 设置分心提示是否显示。
     * 分心提示绘制在固定屏幕位置（右侧中部），不随人脸框移动，
     * 避免遮挡视线/头姿等有效区域。
     *
     * @param shown true=显示 "DISTRACTED"，false=隐藏
     */
    fun setDistracted(shown: Boolean) {
        mDistractShown = shown
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

            when (drawMode) {
                DRAW_MODE_BEHAVIOR -> {
                    // 行为监测：仅画人脸框（绿=有效人脸，红=spoof），不含文字/关键点/箭头
                    val boxPaint = if (face.type == FaceType.DETECTED) mGreenPaint else mRedPaint
                    canvas.drawRect(left, top, right, bottom, boxPaint)
                }

                DRAW_MODE_RECOGNITION -> {
                    // 人脸识别：仅人脸框 + 名称 + 置信度
                    val boxPaint = if (face.type == FaceType.DETECTED) mGreenPaint else mRedPaint
                    canvas.drawRect(left, top, right, bottom, boxPaint)

                    val label = buildString {
                        append(face.label ?: when (face.type) {
                            FaceType.DETECTED -> "detected"
                            FaceType.SPOOF -> "spoof"
                        })
                        append(" ${(face.confidence * 100).toInt()}%")
                    }
                    val labelWidth = mLabelPaint.measureText(label)
                    val labelHeight = mLabelPaint.textSize
                    canvas.drawRect(left, top - labelHeight - 8, left + labelWidth + 12, top, mBgPaint)
                    canvas.drawText(label, left + 6, top - 6, mLabelPaint)
                }

                DRAW_MODE_FATIGUE -> {
                    // 疲劳监测：68 眼嘴点位 + 眼睛/嘴巴开合状态（不画人脸框/名称/置信度）
                    face.denseLandmarks?.forEach { pt ->
                        canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 2f, mLandmarkPaint)
                    }
                    // 眼睛/嘴巴开合状态（小字，画面上方）
                    val poseHeight = mPosePaint.textSize
                    val emText = "E:${if (face.eyeOpen) "OPEN" else "CLOSED"}  " +
                            "M:${if (face.mouthOpen) "OPEN" else "CLOSED"}"
                    val emWidth = mPosePaint.measureText(emText)
                    canvas.drawRect(left, top - poseHeight - 8, left + emWidth + 8, top, mBgPaint)
                    canvas.drawText(emText, left + 4, top - poseHeight - 3, mPosePaint)
                }

                DRAW_MODE_DISTRACTION -> {
                    // 分心监测：头姿 + 视线 + 关键 5 点 + 头姿/视线箭头
                    // （不画人脸框/名称/置信度/眼嘴状态）
                    val poseHeight = mPosePaint.textSize

                    // 头姿信息（小字，画面上方）
                    val poseText = "P:%.0f Y:%.0f R:%.0f".format(face.pitch, face.yaw, face.roll)
                    val poseWidth = mPosePaint.measureText(poseText)
                    canvas.drawRect(left, top - poseHeight * 2 - 12, left + poseWidth + 8,
                        top - poseHeight - 6, mBgPaint)
                    canvas.drawText(poseText, left + 4, top - poseHeight - 1, mPosePaint)

                    // 视线信息（小字，头姿下方）
                    val gazeText = "G:yaw%.0f pit%.0f v%d c%d d%d".format(
                        face.gazeYaw, face.gazePitch,
                        if (face.gazeValid > 0f) 1 else 0,
                        if (face.gazeCalibrated > 0f) 1 else 0,
                        if (face.gazeDistracted > 0f) 1 else 0)
                    val gazeWidth = mPosePaint.measureText(gazeText)
                    canvas.drawRect(left, top - poseHeight - 8, left + gazeWidth + 8, top, mBgPaint)
                    canvas.drawText(gazeText, left + 4, top - poseHeight - 3, mPosePaint)

                    // 绘制 5 关键点（紫色，瞳孔/鼻尖/嘴角）
                    face.keypoints?.forEach { pt ->
                        canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 4f, mKeypointPaint)
                    }

                    // 头姿坐标轴 + 视线（仅 DETECTED）
                    if (face.type == FaceType.DETECTED) {
                        drawHeadPoseArrow(canvas, face, scaleX, scaleY)
                        drawGaze(canvas, face, scaleX, scaleY)
                    }
                }

                DRAW_MODE_FUSION -> {
                    // 融合监测（FACEP-018）：仅绘制 68 点密集地标 + 头姿坐标轴。
                    // 不画人脸框/名称/5点/视线线/眼嘴文字，避免遮挡画面。
                    // 头姿坐标轴无条件绘制（不论 detected/spoof/未录入），供分心/头姿判定持续参考。
                    face.denseLandmarks?.forEach { pt ->
                        canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 3f, mLandmarkPaint)
                    }
                    drawHeadPoseArrow(canvas, face, scaleX, scaleY)
                }
            }
        }

        // 分心模式：左侧 zone 面板 + 固定分心提示
        if (drawMode == DRAW_MODE_DISTRACTION) {
            drawZonePanel(canvas, faces)
            drawDistracted(canvas)
        }
    }

    /**
     * 在固定屏幕位置（右侧中部）绘制分心提示。
     * 由 [setDistracted] 控制显隐；不依赖人脸框位置，避免遮挡视线/头姿等有效区域。
     */
    private fun drawDistracted(canvas: Canvas) {
        if (!mDistractShown) return
        val text = "DISTRACTED"
        // 固定位置：画面右侧中部
        val x = width * 0.70f
        val y = height * 0.45f
        val w = mDistractedPaint.measureText(text)
        // 背景 + 文字
        canvas.drawRect(x - 8f, y - mDistractedPaint.textSize - 8f,
            x + w + 8f, y + 8f, mBgPaint)
        canvas.drawText(text, x, y, mDistractedPaint)
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
     * 左眼起点 = 5 关键点 index 0（左眼中心），右眼起点 = index 1（右眼中心），
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
     * 绘制头姿三维坐标轴（2D 三角函数方案，FACEP-007）。
     *
     * 画面配色与方向（画笔颜色：mAxisX=红 / mAxisY=绿 / mAxisZ=蓝，绘制时按角色重排）：
     *   - 红(mAxisX) = 脸部朝向，由 yaw/pitch 决定；
     *   - 蓝(mAxisZ) = 竖直向上，由 roll 控制（位于原 Y 逻辑位置）；
     *   - 绿(mAxisY) = 水平，由 roll 控制，基准取反默认朝右，长度为红/蓝的 0.7。
     * 角度处理：yaw 取负(前置镜像)、roll 取负；绘制顺序 绿→蓝→红(红最顶层，避免被遮挡)。
     *
     * 原点为两眼中间点（从 5 关键点中取左眼/右眼），fallback 到人脸框中心。
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

        // ---- 头姿角极性说明（勿随意改动，方向均经真机校准）----
        // face.yaw/pitch/roll 为 SDK 原始角度(度)；此处仅做方向/镜像处理：
        //  - yawRad = -face.yaw  ：前置摄像头画面为镜像，转头方向取反；
        //  - pitchRad = face.pitch：原值；下方红轴竖直投影 sin(-pitchRad) 等于 -sin(pitch)，
        //    即 pitch 方向在竖直上又反了一次（点头方向如反，改这里而非下面公式）；
        //  - rollRad = -face.roll ：roll 取反，翻转歪头旋转方向（歪头方向如反，改这里）。
        // 注意：若未来 SDK 侧 headYaw/headPitch/headRoll 已按 DMS/镜像约定输出过方向，
        //       上面这些取负会造成"双重反转"、方向反掉——需在升级 SDK 后整体复核。
        val yawRad = (-face.yaw) * DEG2RAD
        val pitchRad = face.pitch * DEG2RAD
        val rollRad = (-face.roll) * DEG2RAD

        // 缩放至 View 空间
        val sx = startX * scaleX
        val sy = startY * scaleY

        // 绘制原点圆点（白色）
        val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawCircle(sx, sy, 5f, originPaint)

        // 绘制顺序（底层→顶层）＝ 绿 → 蓝 → 红：红(朝向)最后画在最顶层，
        // 这样红绿/红蓝交叉处露出红线，不被横向/竖向轴遮挡。
        // --- 水平轴（绿色）：原蓝色(Z)位置的水平向，仅 roll 控制旋转。
        //    取反：基准角 Z_BASE_RAD(180°朝左) 加 π(180°) 反向 → 默认朝右。
        //    修复隐患：与红/蓝一致补乘 scaleX/scaleY，避免非 1:1 缩放下长度/位置错位。 ---
        val zAngle = Z_BASE_RAD + PI_RAD + rollRad
        val zAxisLen = axisLen * 0.7f
        val zEx = sx + cos(zAngle) * zAxisLen * scaleX
        val zEy = sy + sin(zAngle) * zAxisLen * scaleY
        canvas.drawLine(sx, sy, zEx, zEy, mAxisYPaint)
        drawArrowHead(canvas, zEx, zEy, sx, sy, mAxisYPaint)

        // --- 竖直轴（蓝色）：始终指向上方，仅 roll 控制旋转。
        //    注：蓝色移到原本绿色(Y)所在的位置（竖直向上），相对三轴布局不变。 ---
        val yAngle = Y_BASE_RAD + rollRad
        val yEx = sx + cos(yAngle) * axisLen * scaleX
        val yEy = sy + sin(yAngle) * axisLen * scaleY
        canvas.drawLine(sx, sy, yEx, yEy, mAxisZPaint)
        drawArrowHead(canvas, yEx, yEy, sx, sy, mAxisZPaint)

        // --- X 轴（红色）：脸部朝向，由 yaw/pitch 决定（最后绘制=最顶层） ---
        // 修复隐患：正直(yaw/pitch≈0)时该轴端点≈原点，长度过小会画出方向固定的异常
        // 小三角(atan2(0,0))；故先算 View 空间长度，过小则只画短线、跳过箭头。
        val dx = sin(yawRad) * axisLen
        val dy = sin(-pitchRad) * axisLen
        val xEx = sx + dx * scaleX
        val xEy = sy + dy * scaleY
        canvas.drawLine(sx, sy, xEx, xEy, mAxisXPaint)
        val redLenV = hypot(xEx - sx, xEy - sy)
        if (redLenV >= MIN_ARROW_LEN) {
            drawArrowHead(canvas, xEx, xEy, sx, sy, mAxisXPaint)
        }
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
        /** 68 个密集地标（疲劳模式粉色绘制）。 */
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
        val zoneId: Float = 0f,
        /** 眼睛是否睁开（true=睁眼，false=闭眼）。 */
        val eyeOpen: Boolean = true,
        /** 嘴巴是否张开（true=张嘴，false=闭嘴）。 */
        val mouthOpen: Boolean = false
    )

    enum class FaceType { DETECTED, SPOOF }

    companion object {
        /** 绘制模式：分心（全部，头姿/视线/分区/分心提示）。 */
        const val DRAW_MODE_DISTRACTION = 0
        /** 绘制模式：人脸识别（仅人脸框/名称/置信度）。 */
        const val DRAW_MODE_RECOGNITION = 1
        /** 绘制模式：疲劳（人脸框/名称/置信度 + 眼嘴状态）。 */
        const val DRAW_MODE_FATIGUE = 2
        /** 绘制模式：行为监测（仅人脸框，无文字/关键点/箭头）。 */
        const val DRAW_MODE_BEHAVIOR = 3
        /** 绘制模式：融合监测（FACEP-018，仅 68 点密集地标 + 头姿坐标轴 + zone 面板）。 */
        const val DRAW_MODE_FUSION = 4

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
