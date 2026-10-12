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

    // ===== 俯视小罗盘（头朝向正向示意，基于车辆外参正视基准）=====
    /** 罗盘底盘填充。 */
    private val mCompassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }
    /** 罗盘外圈描边。 */
    private val mCompassRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 160, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    /** 罗盘十字参考线（前后左右）。 */
    private val mCompassCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    /** 罗盘"正前"基准线（固定指向画面上方）。 */
    private val mCompassFwdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 120, 255, 120)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    /** 罗盘头朝向箭头。 */
    private val mCompassHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 90, 90)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    /** 罗盘文字。 */
    private val mCompassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
    }
    /** 罗盘诊断日志节流时间戳。 */
    private var mCompassLogMs = 0L

    // ===== MPIIGaze 眼图可视化（face-sdk 1.0.3 eyePatch）=====
    /**
     * 眼图复用 Bitmap：**左右眼各一个独立实例**。
     *
     * 注意：不能两眼共用一个 Bitmap。硬件加速下 canvas.drawBitmap 是延迟光栅化的，
     * 若两轮循环复用同一实例，两次绘制实际执行时都指向该实例的最终内容 →
     * 左眼会显示成右眼的样子（两眼图相同）。故每眼独占一个 Bitmap。
     */
    private val mEyePatchBitmaps = arrayOf(
        android.graphics.Bitmap.createBitmap(EYE_PATCH_W, EYE_PATCH_H, android.graphics.Bitmap.Config.ARGB_8888),
        android.graphics.Bitmap.createBitmap(EYE_PATCH_W, EYE_PATCH_H, android.graphics.Bitmap.Config.ARGB_8888)
    )
    /** 眼图像素复用缓冲（60×36），仅 UI 线程使用。 */
    private val mEyePatchPixels = IntArray(EYE_PATCH_W * EYE_PATCH_H)
    /** 眼图绘制画笔（关闭抗锯齿，保持像素清晰）。 */
    private val mEyePatchPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    /** 眼图外框画笔。 */
    private val mEyePatchBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 200)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    /** 眼图标签画笔。 */
    private val mEyePatchLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 200)
        textSize = 20f
        style = Paint.Style.FILL
    }
    /** 眼图放大倍数（36×60 原始尺寸过小，放大便于观察）。 */
    private val EYE_PATCH_SCALE = 3

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
                    // 分心监测：头姿/视线 Z 高度 + 点位 + 关键 5 点 + 头姿/视线箭头
                    // （不画人脸框/名称/置信度/眼嘴状态）
                    val poseHeight = mPosePaint.textSize
                    // 两行文字纵向排布（互不重叠）：
                    //   判定块在上：背景 [top - 2h - 12, top - h - 6]，基线 top - h - 12
                    //   头姿块在下：背景 [top - h - 6, top]，       基线 top - 6
                    // 注意：每块文字必须完整落在自己的背景框内，否则会出现上下行重叠。

                    // 第一行（重点）：视线 Z 轴高度 + 分心点位
                    val zText = if (face.gazeZHeight.isNaN()) {
                        "gazeZ: --"
                    } else {
                        "gazeZ: %.0fmm".format(face.gazeZHeight)
                    }
                    val ptText = if (face.pointId > 0) "点${face.pointId}" else "-"
                    val judgeText = "$zText   point: $ptText"
                    val judgeWidth = mPosePaint.measureText(judgeText)
                    canvas.drawRect(left, top - poseHeight * 2 - 12, left + judgeWidth + 8,
                        top - poseHeight - 6, mBgPaint)
                    canvas.drawText(judgeText, left + 4, top - poseHeight - 12, mPosePaint)

                    // 第二行：头姿角原始值
                    val poseText = "P:%.0f Y:%.0f R:%.0f".format(face.pitch, face.yaw, face.roll)
                    val poseWidth = mPosePaint.measureText(poseText)
                    canvas.drawRect(left, top - poseHeight - 6, left + poseWidth + 8, top, mBgPaint)
                    canvas.drawText(poseText, left + 4, top - 6, mPosePaint)

                    // 绘制 5 关键点（紫色，瞳孔/鼻尖/嘴角）
                    face.keypoints?.forEach { pt ->
                        canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, 4f, mKeypointPaint)
                    }

                    // 头姿坐标轴已按需求移除；头朝向以左下角俯视罗盘示意
                }

                DRAW_MODE_FUSION -> {
                    // 融合监测（FACEP-018）：仅绘制头朝向罗盘。
                    // 不画人脸框/名称/5点/68点地标/视线线/眼嘴文字，避免遮挡画面。
                    // 罗盘在固定屏幕位置绘制（见下方），不随人脸移动。
                }
            }
        }

        // 分心模式：左侧 zone 面板 + 固定分心提示 + 右下角 MPIIGaze 眼图 + 左下角俯视罗盘
        if (drawMode == DRAW_MODE_DISTRACTION) {
            drawZonePanel(canvas, faces)
            drawDistracted(canvas)
            faces.firstOrNull()?.let {
                drawEyePatches(canvas, it)
                drawHeadingCompass(canvas, it)
            }
        }

        // 融合监测模式：同样在左下角绘制 Y/P 两个罗盘（不画 68 点地标）
        if (drawMode == DRAW_MODE_FUSION) {
            faces.firstOrNull()?.let { drawHeadingCompass(canvas, it) }
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
     * 绘制**两个独立罗盘**，分别表达 Y（偏航）/ P（俯仰）与"正前方"的关系。
     *
     * 数据：`headDeviation = [pitch, yaw, roll]`（度，相对正前方；正视为 0）。
     * roll 无法由世界系方向向量得到（只有 yaw/pitch 两个自由度），故**不绘制 R 罗盘**。
     *
     * 画法统一（绿线 = 正前方基准，红箭头 = 当前姿态）：
     * - **Y 偏航**（俯视图）：基准朝上（正前），红箭头随 `yaw` 偏转；右转 → 偏右。
     * - **P 俯仰**（侧视图）：基准水平指向右（正前），红箭头随 `pitch` 上下偏；抬头 → 偏上。
     *
     * 两个罗盘横向并排放在**左下角**，各带标题与角度值。
     */
    private fun drawHeadingCompass(canvas: Canvas, face: FaceBox) {
        val dev = face.headDeviation
        if (dev == null || dev.size < 3) {
            // 诊断日志（节流）：headDeviation 为空时无法绘制罗盘
            val now = System.currentTimeMillis()
            if (now - mCompassLogMs > 5000L) {
                mCompassLogMs = now
                android.util.Log.w("FaceOverlayView",
                    "drawHeadingCompass skip: headDeviation=${dev?.contentToString()} view=${width}x${height}")
            }
            return
        }

        val pitch = dev[0]
        val yaw = dev[1]

        val radius = 44f
        val gap = 24f
        val titleH = 20f
        val valueH = 18f
        val margin = 20f
        val blockW = radius * 2
        val totalW = blockW * 2 + gap
        val totalH = titleH + radius * 2 + valueH
        // 左下角并排
        val left0 = margin
        val top0 = height - totalH - margin
        if (left0 + totalW > width || top0 < 0) return

        // Y 偏航（俯视图）：基准朝上 = 正前（yaw=0），右转为正 → 顺时针偏右。
        // 关键：箭头必须与基准线(0,-1)**同起点**起算，否则两指针夹角不等于 yaw。
        //   yaw=0   → (0,-1) 朝上（与基准重合）
        //   yaw=+90 → (1, 0) 朝右
        val yawRad = Math.toRadians(yaw.toDouble())
        drawSingleAxisCompass(
            canvas,
            cx = left0 + radius,
            cy = top0 + titleH + radius,
            radius = radius,
            title = "Y 偏航",
            value = yaw,
            dirX = Math.sin(yawRad),
            dirY = -Math.cos(yawRad),
            baseX = 0.0,
            baseY = -1.0
        )

        // P 俯仰（侧视图）：基准朝右 = 正前（pitch=0），抬头为正 → 逆时针偏上。
        // 同样要求与基准线(1,0)同起点：
        //   pitch=0   → (1, 0) 朝右（与基准重合）
        //   pitch=+90 → (0,-1) 朝上
        val pitchRad = Math.toRadians(pitch.toDouble())
        drawSingleAxisCompass(
            canvas,
            cx = left0 + blockW + gap + radius,
            cy = top0 + titleH + radius,
            radius = radius,
            title = "P 俯仰",
            value = pitch,
            dirX = Math.cos(pitchRad),
            dirY = -Math.sin(pitchRad),
            baseX = 1.0,
            baseY = 0.0
        )
    }

    /**
     * 绘制单个单轴罗盘（底盘 + 十字 + 绿色基准线 + 红色姿态箭头 + 标题 + 角度值）。
     *
     * @param cx,cy 圆心（View 坐标）
     * @param radius 半径
     * @param title 标题（如 "Y 偏航"）
     * @param value 该轴角度（度）
     * @param dirX,dirY 红色箭头方向（单位向量，**已含屏幕 y 轴向下**的处理）
     * @param baseX,baseY 绿色基准线方向（单位向量，同样为屏幕方向）
     */
    private fun drawSingleAxisCompass(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        title: String,
        value: Float,
        dirX: Double,
        dirY: Double,
        baseX: Double,
        baseY: Double
    ) {
        // 底盘 + 外圈
        canvas.drawCircle(cx, cy, radius, mCompassBgPaint)
        canvas.drawCircle(cx, cy, radius, mCompassRingPaint)
        // 十字参考
        canvas.drawLine(cx, cy - radius, cx, cy + radius, mCompassCrossPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, mCompassCrossPaint)

        val len = radius - 6f

        // 绿色基准线（"正前方"）
        val bex = cx + (baseX * len).toFloat()
        val bey = cy + (baseY * len).toFloat()
        canvas.drawLine(cx, cy, bex, bey, mCompassFwdPaint)
        drawArrowHead(canvas, bex, bey, cx, cy, mCompassFwdPaint)

        // 红色姿态箭头
        val tex = cx + (dirX * len).toFloat()
        val tey = cy + (dirY * len).toFloat()
        canvas.drawLine(cx, cy, tex, tey, mCompassHeadPaint)
        drawArrowHead(canvas, tex, tey, cx, cy, mCompassHeadPaint)

        // 标题（居中于罗盘上方）
        val tw = mCompassTextPaint.measureText(title)
        canvas.drawText(title, cx - tw / 2f, cy - radius - 6f, mCompassTextPaint)

        // 角度值（居中于罗盘下方）
        val vt = "%+.0f°".format(value)
        val vw = mCompassTextPaint.measureText(vt)
        canvas.drawText(vt, cx - vw / 2f, cy + radius + 18f, mCompassTextPaint)
    }

    /**
     * 在预览右下角绘制 MPIIGaze 归一化眼图（face-sdk 1.0.3 `eyePatch`）。
     *
     * 原始 patch 为每眼 36 行 × 60 列灰度字节（已直方图均衡、不镜像），
     * 此处按 [EYE_PATCH_SCALE] 放大显示，左眼在上、右眼在下；
     * 无效眼（`eyePatchValid` 对应位为 0）不绘制，并在框内标注 "N/A"。
     *
     * 固定屏幕位置绘制（不随人脸移动），避免遮挡人脸区域。
     */
    private fun drawEyePatches(canvas: Canvas, face: FaceBox) {
        val patches = face.eyePatch ?: return
        val dstW = EYE_PATCH_W * EYE_PATCH_SCALE
        val dstH = EYE_PATCH_H * EYE_PATCH_SCALE
        val margin = 16f
        val labelH = mEyePatchLabelPaint.textSize
        val gap = 8f

        // 右下角起始位置（两眼纵向排列）
        val left = width - dstW - margin
        val top = height - (dstH * 2 + gap + labelH * 2) - margin

        // [0]=左眼、[1]=右眼
        for (eye in 0 until 2) {
            if (eye >= patches.size) continue
            val patch = patches[eye]
            val valid = (face.eyePatchValid and (1 shl eye)) != 0
            val y = top + eye * (dstH + gap + labelH)

            // 标签
            canvas.drawText(if (eye == 0) "L-EYE" else "R-EYE", left, y + labelH - 2, mEyePatchLabelPaint)

            val imgTop = y + labelH
            val imgRect = RectF(left, imgTop, left + dstW, imgTop + dstH)

            if (valid && patch.size >= EYE_PATCH_W * EYE_PATCH_H) {
                // 灰度字节 → ARGB 像素（复用缓冲，避免每帧分配）
                for (i in mEyePatchPixels.indices) {
                    val g = patch[i].toInt() and 0xFF
                    mEyePatchPixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
                // 每眼用独立 Bitmap（见 mEyePatchBitmaps 注释：共用会因延迟光栅化导致两眼相同）
                val bmp = mEyePatchBitmaps[eye]
                bmp.setPixels(
                    mEyePatchPixels, 0, EYE_PATCH_W, 0, 0, EYE_PATCH_W, EYE_PATCH_H
                )
                canvas.drawBitmap(bmp, null, imgRect, mEyePatchPaint)
                canvas.drawRect(imgRect, mEyePatchBorderPaint)
            } else {
                // 无效：画空框 + "N/A"
                canvas.drawRect(imgRect, mEyePatchBorderPaint)
                canvas.drawText("N/A", left + 4, imgTop + labelH + 4, mEyePatchLabelPaint)
            }
        }
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
    // 视线追踪可视化（已按需求移除：不再绘制视线射线）
    // ============================================================

    // ============================================================
    // 头姿朝向箭头 + 坐标系
    // ============================================================

    // 头姿三维坐标轴绘制已按需求移除（头朝向改由左下角俯视罗盘示意）。

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
        /** 视线 Z 轴高度（头姿射线与 Y=680mm 平面交点高度，mm）；NaN=无效。 */
        val gazeZHeight: Float = Float.NaN,
        /** 分心点位编号（2~6）；-1=未命中。 */
        val pointId: Int = -1,
        /** 眼睛是否睁开（true=睁眼，false=闭眼）。 */
        val eyeOpen: Boolean = true,
        /** 嘴巴是否张开（true=张嘴，false=闭嘴）。 */
        val mouthOpen: Boolean = false,
        /**
         * MPIIGaze 归一化眼部灰度 patch（face-sdk 1.0.3）。
         * 每眼 36×60 = 2160 字节；[0]=左眼、[1]=右眼。
         * 由 Overlay 转 Bitmap 绘制在预览角落（分心模式）。
         */
        val eyePatch: Array<ByteArray>? = null,
        /** 眼图有效性位标志：bit0=左眼有效，bit1=右眼有效。 */
        val eyePatchValid: Int = 0,
        /**
         * 头朝向相对**正视基准**的偏差角 `[pitch, yaw, roll]`（度），
         * 取 F→W 的 `head_hw_rot`（正视时为单位阵，故其欧拉角即相对正前方的偏差）。
         * 用于绘制"俯视小罗盘"：抬头+/低头-、右转+/左转-、右倾+/左倾-。为 null 时不画。
         */
        val headDeviation: FloatArray? = null
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

        /** MPIIGaze 眼图尺寸（与 face-sdk 模型输入一致：60 列 × 36 行）。 */
        private const val EYE_PATCH_W = 60
        private const val EYE_PATCH_H = 36

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
