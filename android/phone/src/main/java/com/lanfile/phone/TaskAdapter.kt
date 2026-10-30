package com.lanfile.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * 手机端传输任务条目。
 *
 * @property name 文件名
 * @property size 总字节数
 * @property sent 已发送字节数
 * @property status 状态文案
 * @property done 是否已结束（完成或失败）
 * @property ok 是否成功
 */
data class TaskItem(
    val name: String,
    val size: Long,
    val sent: Long,
    val status: String,
    val done: Boolean = false,
    val ok: Boolean = false,
) {
    /** 进度百分比（0-100）。 */
    val percent: Int get() = if (size > 0) (sent * 100 / size).toInt().coerceIn(0, 100) else 0
}

/**
 * 传输任务列表适配器：按「最新任务在最上」排序，并实时刷新进度。
 *
 * 更新方式按文件名定位条目（与 TV 端一致），未找到时静默忽略，
 * 避免 UI 层关心条目的生命周期。
 */
class TaskAdapter : RecyclerView.Adapter<TaskAdapter.ViewHolder>() {

    private val items = mutableListOf<TaskItem>()

    /** 在列表顶部插入新任务（最新上传的文件排在最上面）。 */
    fun addTop(item: TaskItem) {
        items.add(0, item)
        notifyItemInserted(0)
        notifyItemRangeChanged(1, items.size - 1)
    }

    /** 更新指定文件名的进度。 */
    fun update(name: String, transform: (TaskItem) -> TaskItem) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items[index] = transform(items[index])
            notifyItemChanged(index)
        }
    }

    /** 当前条目的只读快照。 */
    fun getItems(): List<TaskItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** 条目视图持有者。 */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.taskName)
        private val fileSize: TextView = itemView.findViewById(R.id.taskSize)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.taskProgress)
        private val progressText: TextView = itemView.findViewById(R.id.taskProgressText)
        private val statusText: TextView = itemView.findViewById(R.id.taskStatus)

        fun bind(item: TaskItem) {
            fileName.text = item.name
            fileSize.text = "${formatSize(item.size)}  ·  ${formatSize(item.sent)} / ${formatSize(item.size)}"
            progressBar.progress = item.percent
            progressBar.visibility = if (item.done) View.GONE else View.VISIBLE
            progressText.text = "${item.percent}%"
            statusText.text = item.status
            statusText.setTextColor(
                when {
                    item.done && item.ok -> 0xFF2E7D32.toInt()
                    item.done -> 0xFFC62828.toInt()
                    else -> 0xFF1565C0.toInt()
                }
            )
        }
    }
}

/** 字节数格式化为人类可读大小。 */
fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
    size < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024 * 1024))
}
