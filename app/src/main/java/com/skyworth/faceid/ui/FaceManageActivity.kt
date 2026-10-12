package com.skyworth.faceid.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.skyworth.faceid.core.AlgoSession
import com.skyworth.faceid.R

/**
 * 已录入人脸管理页（FACEP-012）。
 *
 * 独立页面，简洁大方的卡片网格展示已录入人脸，支持**多选批量删除**。
 * 通过 [AlgoSession] 单例访问算法的人脸库（名称列表 / 删除）。
 * 删除同步持久化到 `face_enrollments.json`；返回识别页后刷新计数。
 */
class FaceManageActivity : AppCompatActivity() {

    private val TAG = "FaceManageActivity"

    private lateinit var mTitleText: TextView
    private lateinit var mSelectedCountText: TextView
    private lateinit var mGridView: GridView
    private lateinit var mEmptyView: View

    private val mFaceNames: MutableList<String> = mutableListOf()
    private lateinit var mAdapter: ArrayAdapter<String>

    /** 头像占位色板（随索引循环，视觉更丰富）。 */
    private val avatarColors = intArrayOf(
        0xFF5B8DEF.toInt(), 0xFF5AD0A2.toInt(), 0xFFF5A623.toInt(),
        0xFFE8698F.toInt(), 0xFF8B6CF6.toInt(), 0xFF3EC6C6.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_manage)

        mTitleText = findViewById(R.id.tv_manage_title)
        mSelectedCountText = findViewById(R.id.tv_selected_count)
        mGridView = findViewById(R.id.gv_faces)
        mEmptyView = findViewById(R.id.empty_view)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_manage_done).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_delete_selected).setOnClickListener { deleteSelectedFaces() }

        mAdapter = object : ArrayAdapter<String>(
            this, R.layout.item_face_grid, R.id.tv_face_name, mFaceNames
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val name = getItem(position) ?: return view
                // 头像：圆形占位显示名称首字
                val avatar = view.findViewById<TextView>(R.id.tv_face_avatar)
                avatar.text = name.take(1)
                avatar.setBackgroundResource(R.drawable.bg_face_avatar)
                avatar.setTextColor(0xFFFFFFFF.toInt())
                // 用色板循环区分（简单起见通过 tag 缓存，避免重复分配）
                var colorTag = view.tag as? Int
                if (colorTag == null) {
                    colorTag = position % avatarColors.size
                    view.tag = colorTag
                }
                avatar.background?.mutate()?.setTint(avatarColors[colorTag])
                // 选中态：根布局 isSelected + 右上角勾
                val checked = mGridView.isItemChecked(position)
                view.isSelected = checked
                view.findViewById<TextView>(R.id.tv_check_indicator)
                    .visibility = if (checked) View.VISIBLE else View.GONE
                return view
            }
        }
        mGridView.adapter = mAdapter

        // 选中变化时：刷新计数 + 强制重绘 item 以显示选中勾/高亮
        mGridView.setOnItemClickListener { _, _, _, _ ->
            updateSelectedCount()
            mGridView.invalidateViews()
        }

        loadFaces()
    }

    override fun onResume() {
        super.onResume()
        loadFaces()
    }

    /** 加载已录入人脸名称到网格。 */
    private fun loadFaces() {
        val names = AlgoSession.get().algorithm().getEnrolledNames()
        mFaceNames.clear()
        mFaceNames.addAll(names)
        mAdapter.notifyDataSetChanged()
        mGridView.clearChoices()
        updateTitle()
        updateSelectedCount()
        updateEmptyState()
        Log.i(TAG, "loaded ${mFaceNames.size} faces")
    }

    private fun updateTitle() {
        mTitleText.text = getString(R.string.manage_title_format, mFaceNames.size)
    }

    private fun updateSelectedCount() {
        val n = mGridView.checkedItemCount
        mSelectedCountText.text = if (n > 0) "已选 $n" else ""
    }

    private fun updateEmptyState() {
        val empty = mFaceNames.isEmpty()
        mEmptyView.visibility = if (empty) View.VISIBLE else View.GONE
        mGridView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** 批量删除选中的人脸（删除前二次确认）。 */
    private fun deleteSelectedFaces() {
        val selected = (0 until mFaceNames.size)
            .filter { mGridView.isItemChecked(it) }
            .map { mFaceNames[it] }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.manage_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val algo = AlgoSession.get().algorithm()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.enroll_delete_title)
            .setMessage(getString(R.string.manage_delete_batch_confirm, selected.size))
            .setPositiveButton(R.string.enroll_confirm) { _, _ ->
                var okCount = 0
                selected.forEach { name -> if (algo.deleteFace(name)) okCount++ }
                mFaceNames.removeAll(selected.toSet())
                mAdapter.notifyDataSetChanged()
                mGridView.clearChoices()
                updateTitle()
                updateSelectedCount()
                updateEmptyState()
                if (mFaceNames.isEmpty()) {
                    finish()
                }
                Toast.makeText(this,
                    getString(R.string.enroll_delete_success, "$okCount 张"),
                    Toast.LENGTH_SHORT).show()
                Log.i(TAG, "deleted $okCount faces")
            }
            .setNegativeButton(R.string.enroll_cancel, null)
            .create()
        dialog.show()
        shrinkDialog(dialog)
    }

    /** 缩小 AlertDialog 窗口宽度（车机大屏下默认弹框过宽）。 */
    private fun shrinkDialog(dialog: AlertDialog) {
        dialog.window?.let { w ->
            val width = (resources.displayMetrics.density * 360).toInt()
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }
}
