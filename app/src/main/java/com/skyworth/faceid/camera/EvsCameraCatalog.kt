package com.skyworth.faceid.camera

import android.util.Log
import com.android.car.evs.CameraIds
import org.json.JSONObject
import java.io.File

/**
 * 本车 EVS 相机目录：解析出**真正可用**的相机名（按语义槽位排序）。
 *
 * 为什么需要：可用的相机名由固件决定，**不同车型/固件不一样**——
 *
 * ```
 * van233 ： RVC / DMS / LBS / RBS / FVC / TVC        环视四路叫 FVC / RBS / RVC / LBS
 * minibus： RVC / DMS / AVML / AVMR / AVMF / AVMB    环视四路就叫 AVMF / AVMR / AVMB / AVML
 * ```
 *
 * 写死任一套，换到另一台车就会"切过去没流、预览也黑"：HAL 对**未声明**的名字可能
 * `openCamera` 返回成功却一帧不出，而且**没有任何报错**（2026-10-09 两台车各踩一次）。
 *
 * ## 三级解析（先精确、后兜底）
 * 1. **显式清单**：配置里给了 `order` → 直接用（想彻底手动时用）；
 * 2. **车型方案**：配置里 `mode = "van233" | "avm"` → 用对应那套名字；
 * 3. **自动识别**（默认 `mode = "auto"`）：读固件的 `/vendor/etc/evs_hal_devices.xml` 声明。
 *
 * ## 配置文件（可选，不用重编 APK）
 * `/vendor/etc/faceid/evs_camera_profile.json`
 * ```json
 * { "mode": "auto",              // auto | van233 | avm
 *   "order": [] }                // 非空则优先于 mode，例：["AVMF","AVMR","AVMB","AVML","RVC","DMS"]
 * ```
 * 用 `make push-profile MODE=van233` 推送；**改完需重启 App**（进程内只读一次）。
 */
object EvsCameraCatalog {

    private const val TAG = "EvsCameraCatalog"

    /** 固件相机清单（自动模式下的权威来源）。 */
    private const val DEVICES_XML = "/vendor/etc/evs_hal_devices.xml"

    /** 命名方案配置文件（可选；vendor 侧便于按车推送，无需重编 APK）。 */
    private const val PROFILE_FILE = "/vendor/etc/faceid/evs_camera_profile.json"

    /**
     * 语义槽位 → 候选 ID（按优先级取第一个"固件声明过"的）。
     *
     * 顺序即观看端按钮顺序，**与观看端 `DEFAULT_NAMES`、中继 `CSWITCH_CAMS` 的 6 路约定一致**：
     *
     * `1=前视  2=右视  3=后视  4=左视  5=倒车  6=驾驶员`
     *
     * 槽位名固定、**ID 随车型变**（本表就是"这台车可能叫什么"的清单）。
     * 缺 AVM 后视的车型上，后视与倒车会解析成同一个 `RVC`，属预期。
     */
    private val SLOTS: List<Pair<String, List<String>>> = listOf(
        "前视" to listOf(CameraIds.AVMF, CameraIds.FVC),
        "右视" to listOf(CameraIds.AVMR, CameraIds.RBS),
        "后视" to listOf(CameraIds.AVMB, CameraIds.RVC),
        "左视" to listOf(CameraIds.AVML, CameraIds.LBS),
        "倒车" to listOf(CameraIds.RVC),
        "驾驶员" to listOf(CameraIds.DMS)
    )

    /** 车型方案 → 固定 6 路 ID（`mode` 非 auto 时用）。 */
    private val PRESETS: Map<String, List<String>> = mapOf(
        "van233" to listOf(
            CameraIds.FVC, CameraIds.RBS, CameraIds.RVC,
            CameraIds.LBS, CameraIds.RVC, CameraIds.DMS
        ),
        "avm" to listOf(
            CameraIds.AVMF, CameraIds.AVMR, CameraIds.AVMB,
            CameraIds.AVML, CameraIds.RVC, CameraIds.DMS
        )
    )

    /** 配置文件内容（只读一次；null = 没配/读不到）。 */
    private val profile: JSONObject? by lazy { readProfile() }

    /** 固件声明的相机名（只解析一次；空 = 解析失败）。 */
    private val declared: List<String> by lazy { parseDeclared() }

    /** 生效方案名（日志/上屏用）：auto | van233 | avm | order。 */
    val profileName: String by lazy {
        when {
            explicitOrder() != null -> "order"
            profile?.optString("mode").isNullOrBlank() -> "auto"
            else -> profile!!.optString("mode")
        }
    }

    /** 该车实际可用的相机 ID（按槽位顺序，长度固定 = 6）。 */
    val ids: List<String> by lazy { resolveIds() }

    /**
     * 相机 ID → 展示名：**直接用 ID 本身**，不加中文。
     * 与观看端按钮（`index.html` 的 `DEFAULT_NAMES`）保持同一套名字，便于两边对照。
     */
    val displayNames: Map<String, String> by lazy { ids.associateWith { it } }

    /** 供日志/界面上屏的一行摘要（排查"这台车有哪些相机、走了哪套方案"时最有用）。 */
    fun describe(): String {
        val source = if (profile != null) PROFILE_FILE else "内置 auto（无配置文件）"
        return "EVS 相机：方案=$profileName（$source）" +
            " 固件声明=${declared.ifEmpty { listOf("(不可读)") }} → 本页顺序=$ids"
    }

    // ============================================================
    // 解析
    // ============================================================

    private fun resolveIds(): List<String> {
        val resolved = explicitOrder()?.also {
            Log.i(TAG, "使用配置中的显式清单：$it")
        } ?: PRESETS[profileName]?.also {
            Log.i(TAG, "使用车型方案 '$profileName'：$it")
        } ?: resolveFromFirmware()
        Log.i(TAG, "槽位解析：" + SLOTS.mapIndexed { i, s -> "${s.first}=${resolved[i]}" }.joinToString(" "))
        return resolved
    }

    /** 配置里的显式 6 路清单；未配置或格式不对则返回 null。 */
    private fun explicitOrder(): List<String>? {
        val arr = profile?.optJSONArray("order") ?: return null
        if (arr.length() == 0) return null   // 空数组 = 没配（不刷日志）
        val list = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        // 长度必须与槽位一致，否则忽略并回退（避免按钮数与中继约定错位）
        return list.takeIf { it.size == SLOTS.size } ?: run {
            Log.w(TAG, "配置 order=$list（${list.size} 项）应为 ${SLOTS.size} 项，忽略并回退")
            null
        }
    }

    /** 自动模式：按固件声明把每个槽位解析成第一个"被声明过"的名字。 */
    private fun resolveFromFirmware(): List<String> {
        val d = declared
        return SLOTS.map { (_, candidates) ->
            if (d.isEmpty()) {
                candidates.first().also {
                    Log.w(TAG, "固件清单不可读，暂用候选首选 $it（大概率打不开，见无帧回退）")
                }
            } else {
                candidates.firstOrNull { it in d } ?: candidates.first().also {
                    Log.w(TAG, "固件未声明 ${candidates.joinToString("/")}，暂用 $it（大概率打不开）")
                }
            }
        }
    }

    /** 解析 `<device name="X" .../>`；异常一律返回空表（由调用方回退）。 */
    private fun parseDeclared(): List<String> {
        return try {
            val file = File(DEVICES_XML)
            if (!file.canRead()) {
                Log.w(TAG, "固件清单不可读：$DEVICES_XML（回退默认候选）")
                return emptyList()
            }
            val names = DEVICE_NAME_RE.findAll(file.readText()).map { it.groupValues[1] }.toList()
            Log.i(TAG, "固件声明的相机：$names")
            names
        } catch (t: Throwable) {
            Log.w(TAG, "解析 $DEVICES_XML 失败（回退默认候选）", t)
            emptyList()
        }
    }

    /** 读命名方案配置；不存在或格式错误都返回 null（= 走 auto）。 */
    private fun readProfile(): JSONObject? {
        return try {
            val file = File(PROFILE_FILE)
            if (!file.canRead()) return null
            JSONObject(file.readText()).also {
                Log.i(TAG, "读到命名方案配置 $PROFILE_FILE：$it")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析 $PROFILE_FILE 失败（按 auto 处理）", t)
            null
        }
    }

    private val DEVICE_NAME_RE = Regex("""<device\s+name="([A-Za-z0-9_]+)"""")
}
