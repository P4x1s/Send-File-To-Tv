package com.lanfile.phone

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lanfile.phone.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机端主界面。
 *
 * 流程：
 * 1. 通过 NSD 自动发现局域网内的电视设备（零配置，无需手动输入地址）；
 * 2. 用户选择目标设备与文件（可多选，**不限制文件大小**，直接流式读取 URI 上传）；
 * 3. 走 HTTP 通道（与电视端内置 HTTP 服务器配合，分块并发 + 逐片 SHA-256 校验），
 *    **最多 5 个文件同时传输**，其余排队；
 * 4. 实时显示每个文件的进度、已传字节与状态。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var taskAdapter: TaskAdapter
    private val devices = mutableListOf<TvDevice>()
    private var selected: TvDevice? = null

    /** 上一次进度回调时间（用于计算实时速度）。 */
    private val lastTickAt = mutableMapOf<String, Long>()
    private val lastTickBytes = mutableMapOf<String, Long>()

    /** 设备发现。 */
    private lateinit var discovery: DiscoveryHelper

    /** 文件选择器（多选）。 */
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        startTransfer(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        taskAdapter = TaskAdapter()
        binding.taskList.layoutManager = LinearLayoutManager(this)
        binding.taskList.adapter = taskAdapter

        discovery = DiscoveryHelper(this) { dev ->
            runOnUiThread {
                if (devices.none { it.name == dev.name && it.host == dev.host }) {
                    devices.add(dev)
                    updateDeviceList()
                }
            }
        }
        discovery.start()
        binding.pickBtn.setOnClickListener { pickFiles() }
    }

    /** 刷新设备列表（展示名称、IP 与可用通道）。 */
    private fun updateDeviceList() {
        val labels = devices.map { "${it.name}  (${it.host}:${it.httpPort})" }
        binding.deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        binding.deviceList.setOnItemClickListener { _, _, i, _ ->
            selected = devices[i]
            binding.status.text = "已选择目标：${devices[i].name}（${devices[i].host}）"
        }
        if (binding.deviceList.adapter != null && devices.isNotEmpty()) {
            binding.deviceList.setSelection(devices.size - 1)
            selected = devices.last()
        }
    }

    /** 调起系统文件选择器（支持多选）。 */
    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // 保留 URI 权限：断开后重新选择同一文件即可断点续传
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickLauncher.launch(intent)
    }

    /** 开始传输。 */
    private fun startTransfer(uris: List<Uri>) {
        val target = selected
        if (target == null) {
            binding.status.text = "请先在上方选择一台电视设备"
            return
        }
        if (uris.isEmpty()) {
            binding.status.text = "没有可发送的文件"
            return
        }

        lifecycleScope.launch {
            binding.status.text = "正在读取文件信息…"
            val pending = withContext(Dispatchers.IO) { uris.mapNotNull { toPendingFile(it) } }
            if (pending.isEmpty()) {
                binding.status.text = "没有可发送的文件"
                return@launch
            }

            // 任务列表：最新在最上
            for (p in pending) {
                taskAdapter.addTop(TaskItem(p.name, p.size, 0, "排队中"))
            }
            binding.status.text = "正在发送 ${pending.size} 个文件到 ${target.name}（最多 5 个同时传输）…"

            val client = HttpTransferClient(target.host, target.httpPort, context = this@MainActivity)
            val results = client.transfer(pending) { p ->
                runOnUiThread { updateProgress(p) }
            }

            val okCount = results.count { it.ok }
            runOnUiThread {
                binding.status.text = "发送完成：$okCount/${results.size} 个文件成功"
                results.filter { !it.ok }.forEach {
                    taskAdapter.update(it.name) { t -> t.copy(done = true, ok = false, status = "失败：${it.error}") }
                }
            }
        }
    }

    /** 更新单个任务的实时进度（进度条 + 已传字节 + 速度）。 */
    private fun updateProgress(p: FileProgress) {
        val now = System.currentTimeMillis()
        val prevAt = lastTickAt[p.name] ?: now
        val prevBytes = lastTickBytes[p.name] ?: 0L
        lastTickAt[p.name] = now
        lastTickBytes[p.name] = p.sent

        val dt = (now - prevAt).coerceAtLeast(1L)
        val speed = if (p.sent > prevBytes) (p.sent - prevBytes) * 1000 / dt else 0L

        val status = when {
            p.done && p.ok -> "已完成"
            p.done -> "失败：${p.error}"
            p.sent > 0 -> "上传中  ${formatSize(speed)}/s"
            else -> "排队中"
        }
        taskAdapter.update(p.name) {
            it.copy(sent = p.sent, size = p.total, status = status, done = p.done, ok = p.ok)
        }
    }

    /**
     * 把 content URI 转换为待发送文件。
     *
     * 注意：**不复制文件到本地**，直接使用 URI 流式读取，
     * 因此 50GB 文件也能在手机存储不爆的情况下传输。
     */
    private fun toPendingFile(uri: Uri): PendingFile? {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        var lastModified = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx) ?: name
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    val lmIdx = c.getColumnIndex("last_modified")
                    if (lmIdx >= 0 && !c.isNull(lmIdx)) lastModified = c.getLong(lmIdx)
                }
            }
        }
        if (size < 0) {
            // 拿不到大小时退回读取流长度（仅一次顺序扫描，代价可控）
            size = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    var total = 0L
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    total
                } ?: -1L
            }.getOrDefault(-1L)
        }
        if (size < 0) return null
        return PendingFile(uri, name, size, lastModified)
    }

    override fun onDestroy() {
        discovery.stop()
        super.onDestroy()
    }
}
