package com.skyworth.faceid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R
import com.skyworth.faceid.core.CameraPreference

/**
 * 主页（FACEP-011）。
 *
 * 提供三个功能入口：人脸识别、疲劳监测、分心监测；
 * 并提供**取流摄像头选择**（AVMF/AVMR/AVMB/AVML/RVC/DMS），
 * 选择结果持久化，进入功能模块取流时使用所选摄像头。
 *
 * 作为应用 LAUNCHER 入口，从主页进入对应功能模块。
 */
class HomeActivity : AppCompatActivity() {

    private val TAG = "HomeActivity"

    private lateinit var mCameraBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // 加载上次选择的摄像头
        CameraPreference.init(this)

        findViewById<Button>(R.id.btn_recognition).setOnClickListener {
            Log.i(TAG, "entry: recognition")
            startActivity(Intent(this, RecognitionActivity::class.java))
        }
        findViewById<Button>(R.id.btn_fatigue).setOnClickListener {
            Log.i(TAG, "entry: fatigue")
            startActivity(Intent(this, FatigueActivity::class.java))
        }
        findViewById<Button>(R.id.btn_distraction).setOnClickListener {
            Log.i(TAG, "entry: distraction")
            startActivity(Intent(this, DistractionActivity::class.java))
        }

        // 摄像头选择
        mCameraBtn = findViewById(R.id.btn_camera_select)
        mCameraBtn.setOnClickListener { showCameraSelect() }
        updateCameraButton()

        // 版本信息
        val versionText = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            getString(R.string.home_version) + " " + info.versionName
        } catch (e: Exception) {
            Log.w(TAG, "version info error", e)
            getString(R.string.home_version) + " ?"
        }
        findViewById<TextView>(R.id.home_version).text = versionText

        Log.i(TAG, "onCreate: done")
    }

    /** 更新摄像头选择按钮文字（显示当前选择的摄像头）。 */
    private fun updateCameraButton() {
        val id = CameraPreference.selectedCameraId
        val name = CameraPreference.cameraDisplayName[id] ?: id
        mCameraBtn.text = getString(R.string.camera_select_format, name)
    }

    /** 弹出摄像头选择弹框（单选 6 个）。 */
    private fun showCameraSelect() {
        val ids = CameraPreference.selectableCameraIds
        val labels = ids.map { CameraPreference.cameraDisplayName[it] ?: it }.toTypedArray()
        val current = CameraPreference.selectedCameraId
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_select_title)
            .setSingleChoiceItems(labels, ids.indexOf(current).coerceAtLeast(0)) { _, which ->
                val id = ids[which]
                CameraPreference.setSelected(this, id)
                updateCameraButton()
                Toast.makeText(this,
                    getString(R.string.camera_select_format,
                        CameraPreference.cameraDisplayName[id] ?: id),
                    Toast.LENGTH_SHORT).show()
                Log.i(TAG, "camera selected: $id")
            }
            .setPositiveButton(R.string.camera_select_ok, null)
            .setNegativeButton(R.string.enroll_cancel, null)
            .show()
    }
}
