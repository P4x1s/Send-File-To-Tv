package com.lanfile.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lanfile.tv.R
import java.io.File
import java.util.Locale

/**
 * 文件列表条目数据模型。
 *
 * 同一条目会经历多个状态（见 [FileStatus]）：上传中、完成、失败、取消、安装中。
 */
data class FileItem(
    val name: String,
    val size: Long,
    var file: File?,
    var progress: Int = 100,
    var status: FileStatus = FileStatus.COMPLETED,
    /** 实时速度文案（如 "8.2 MB/s"），为空则不显示。 */
    var speedText: String = "",
)

/** 文件条目的生命周期状态，决定进度条、文案与按钮可见性。 */
enum class FileStatus {
    /** 上传进行中：显示进度条与百分比，隐藏「打开」按钮。 */
    UPLOADING,

    /** 上传完成：隐藏进度条，显示「打开」按钮。 */
    COMPLETED,

    /** 上传失败。 */
    ERROR,

    /** APK 上传完成、正在拉起安装器。 */
    INSTALLING,

    /** 上传被取消（网页端取消或连接中断）。 */
    CANCELLED,
}

/** 文件条目的用户操作回调。 */
interface FileItemCallback {
    /** 用户点击「打开」。 */
    fun onOpen(item: FileItem)

    /** 用户点击「删除」。 */
    fun onDelete(item: FileItem)
}

/**
 * 文件列表适配器：负责条目渲染与状态驱动刷新。
 *
 * 所有更新方法均按 [FileItem.name] 定位条目；未找到时静默忽略，
 * 因此调用方无需关心条目是否仍存在（取消/失败竞态场景）。
 */
class FileAdapter(private val callback: FileItemCallback) :
    RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()

    /**
     * 在列表顶部插入条目（新的传输永远在最上面）。
     *
     * 若已存在同名条目则替换它（断点续传会复用同一个文件名），
     * 避免出现两条同名记录。
     */
    fun addItem(item: FileItem) {
        val existing = items.indexOfFirst { it.name == item.name }
        if (existing >= 0) {
            items[existing] = item
            notifyItemChanged(existing)
            return
        }
        items.add(0, item)
        notifyItemInserted(0)
        if (items.size > 1) notifyItemRangeChanged(1, items.size - 1)
    }

    /** 在列表底部追加条目（用于恢复历史记录时保持既有顺序）。 */
    fun appendItem(item: FileItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    /** 按文件名移除条目。 */
    fun removeItem(name: String) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, items.size - index)
        }
    }

    /**
     * 清空全部条目（一次性通知，不逐条动画）。
     *
     * 只清列表，**不碰磁盘文件**（文件删除由调用方按需处理）。
     */
    fun clearAll() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    /**
     * 按文件名批量移除条目（一次性通知）。
     *
     * 用于「清空」时保留仍在上传中的条目——它们尚未完成，
     * 不应该被当作历史记录清走。
     *
     * @param names 要移除的文件名集合
     */
    fun removeItems(names: Collection<String>) {
        if (names.isEmpty()) return
        val before = items.size
        items.removeAll { it.name in names }
        if (items.size != before) notifyDataSetChanged()
    }

    /** 刷新指定条目的上传进度。 */
    fun updateProgress(name: String, progress: Int, speedText: String = "") = updateItem(name) {
        it.progress = progress
        it.status = FileStatus.UPLOADING
        it.speedText = speedText
    }

    /** 上传完成：绑定磁盘文件、进度置 100、状态置为完成。 */
    fun completeUpload(name: String, file: File) = updateItem(name) {
        it.file = file
        it.progress = 100
        it.status = FileStatus.COMPLETED
    }

    /** 标记为安装中。 */
    fun setInstalling(name: String) = updateItem(name) { it.status = FileStatus.INSTALLING }

    /** 标记为失败。 */
    fun setError(name: String) = updateItem(name) { it.status = FileStatus.ERROR }

    /** 标记为已取消。 */
    fun setCancelled(name: String) = updateItem(name) { it.status = FileStatus.CANCELLED }

    /** 当前条目的只读快照。 */
    fun getItems(): List<FileItem> = items.toList()

    /** 按文件名定位条目并应用变更，定位失败时静默忽略。 */
    private fun updateItem(name: String, transform: (FileItem) -> Unit) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            transform(items[index])
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position + 1, callback)
    }

    override fun getItemCount(): Int = items.size

    /** 条目视图持有者：按 [FileStatus] 切换控件可见性/文案/颜色，并绑定交互回调。 */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val indexBadge: TextView = itemView.findViewById(R.id.indexBadge)
        private val fileSize: TextView = itemView.findViewById(R.id.fileSize)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val fileStatus: TextView = itemView.findViewById(R.id.fileStatus)
        private val btnOpen: TextView = itemView.findViewById(R.id.btnOpen)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnDelete)

        /** 渲染条目：填充序号/名称/大小，按状态切换 UI，并绑定交互回调。 */
        fun bind(item: FileItem, index: Int, callback: FileItemCallback) {
            indexBadge.text = index.toString()
            fileName.text = item.name
            // 跑马灯：仅在 selected 状态下滚动，文本未超宽时系统自动不滚动
            fileName.isSelected = true
            fileSize.text = formatFileSize(item.size)

            when (item.status) {
                FileStatus.UPLOADING -> {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE
                    progressBar.progress = item.progress
                    progressText.text = if (item.speedText.isEmpty()) {
                        "${item.progress}%"
                    } else {
                        "${item.progress}%  ${item.speedText}"
                    }
                    fileStatus.text = "上传中"
                    fileStatus.setTextColor(0xFFE94560.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.COMPLETED -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "已完成"
                    fileStatus.setTextColor(0xFF4CAF50.toInt())
                    btnOpen.visibility = View.VISIBLE
                }

                FileStatus.INSTALLING -> {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE
                    progressBar.progress = 100
                    progressText.text = "安装中..."
                    fileStatus.text = "安装中"
                    fileStatus.setTextColor(0xFFFF9800.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.ERROR -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "失败"
                    fileStatus.setTextColor(0xFFE94560.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.CANCELLED -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "已取消"
                    fileStatus.setTextColor(0xFFB0B0B0.toInt())
                    btnOpen.visibility = View.GONE
                }
            }

            btnOpen.setOnClickListener { callback.onOpen(item) }
            btnDelete.setOnClickListener { callback.onDelete(item) }

            // 条目获得焦点时抬升阴影，TV 上提示当前选中项。
            // 焦点实际落在条目内的按钮上，所以按钮获得焦点时也要让整行保持高亮。
            itemView.setOnFocusChangeListener { _, hasFocus ->
                itemView.elevation = if (hasFocus) 12f else 0f
            }
            setupFocusAnimation(btnOpen, itemView)
            setupFocusAnimation(btnDelete, itemView)
        }

        /**
         * 为操作按钮添加焦点呼吸动画（透明度 1.0 -> 0.4 -> 1.0 循环），
         * 并在按钮获得焦点时把所属条目整行高亮。
         */
        private fun setupFocusAnimation(view: TextView, row: View) {
            view.setOnFocusChangeListener { _, hasFocus ->
                (view.getTag() as? android.animation.ObjectAnimator)?.cancel()
                if (hasFocus) {
                    view.alpha = 1f
                    val animator =
                        android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f)
                    animator.duration = 1000
                    animator.repeatCount = android.animation.ValueAnimator.INFINITE
                    animator.start()
                    view.setTag(animator)
                    // 焦点在本按钮上 -> 整行高亮，让遥控器用户看清当前选中的是哪一条
                    row.isSelected = true
                    row.elevation = 12f
                } else {
                    view.alpha = 1f
                    // 只有当焦点不在同一行的另一个按钮上时，行高亮才可撤销
                    val sibling = row.findViewById<TextView>(
                        if (view.id == R.id.btnOpen) R.id.btnDelete else R.id.btnOpen
                    )
                    if (sibling?.hasFocus() != true) {
                        row.isSelected = false
                        row.elevation = 0f
                    }
                }
            }
        }
    }

    }

/** 将字节数格式化为人类可读大小（B / KB / MB / GB，保留 1 位小数）。 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 ->
            String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024))
    }
}
