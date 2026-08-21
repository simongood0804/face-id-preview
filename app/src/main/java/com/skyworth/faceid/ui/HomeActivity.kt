package com.skyworth.faceid.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.R

/**
 * 主页（FACEP-011）。
 *
 * 提供三个功能入口：人脸识别、疲劳监测、分心监测。
 * 作为应用 LAUNCHER 入口，从主页进入对应功能模块。
 *
 * 注意：三个功能模块 Activity（RecognitionActivity/FatigueActivity/DistractionActivity）
 * 属后续阶段（FACEP-011 阶段二~四）。当前阶段三入口暂时指向 [PreviewActivity]（现有功能页，
 * 保证功能可用、不破坏现有），后续模块拆分后改为各模块 Activity。
 */
class HomeActivity : AppCompatActivity() {

    private val TAG = "HomeActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

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
}
