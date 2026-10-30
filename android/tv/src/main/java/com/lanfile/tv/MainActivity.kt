package com.lanfile.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanfile.shared.HttpProto
import com.lanfile.tv.data.FileRepository
import com.lanfile.tv.data.SavedFile
import com.lanfile.tv.databinding.ActivityMainBinding
import com.lanfile.tv.http.HttpUploadServer
import com.lanfile.tv.ui.FocusNavigator
import com.lanfile.tv.ui.FileAdapter
import com.lanfile.tv.ui.FileItem
import com.lanfile.tv.ui.FileItemCallback
import com.lanfile.tv.ui.FileStatus
import com.lanfile.tv.util.ApkInstaller
import com.lanfile.tv.util.PortUtil
import com.lanfile.tv.util.QrCodeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 电视端主界面（TV 横屏）。
 *
 * 布局：左侧为上传地址二维码，右侧为已接收文件列表。
 * 职责：
 * 1. 启动内置 HTTP 上传服务器（[HttpUploadServer]）与 TCP 接收服务（[TvServer]）；
 * 2. 展示扫码上传地址与二维码，实现「扫码即传」的零配置体验；
 * 3. 渲染文件列表，响应「打开 / 删除」操作；
 * 4. 通过 [FileRepository] 持久化传输记录，重启后自动恢复列表；
 * 5. 上传的 APK 文件自动拉起系统安装器。
 */
class MainActivity : AppCompatActivity(), HttpUploadServer.Listener, TvServer.Listener, FileItemCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileAdapter
    private lateinit var fileRepository: FileRepository
    private lateinit var focusNavigator: FocusNavigator

    private var httpServer: HttpUploadServer? = null
    private var tcpServer: TvServer? = null
    private var nsd: NsdRegistrar? = null
    private var udpAnnouncer: UdpAnnouncer? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 内存中的传输记录（仅完成态，与列表中的完成条目对应）。 */
    private val savedFiles = mutableListOf<SavedFile>()

    /** 各文件的上一次进度采样（用于计算实时速度）。 */
    private val lastSampleAt = mutableMapOf<String, Long>()
    private val lastSampleBytes = mutableMapOf<String, Long>()

    /** 待安装的 APK 文件名（安装器回调时使用）。 */
    private var pendingApkFile: String? = null

    /**
     * APK 安装结果回调：无论成功或取消，都把条目标记为已完成并持久化。
     */
    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fileName = pendingApkFile ?: return@registerForActivityResult
        pendingApkFile = null
        val file = File(fileRepository.uploadDir, fileName)
        handler.post {
            fileAdapter.completeUpload(fileName, file)
            saveRecord(fileName, file)
            updateTitle()
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(this, "$fileName 安装已取消", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fileRepository = FileRepository(this)
        initFileList()
        initButtons()
        loadSavedFiles()
        updateTitle()

        // 遥控器可用性的关键一步：界面就绪后主动交出一个焦点落点。
        // 之前根布局 focusableInTouchMode=true 会把焦点吸到根 ViewGroup 上，
        // 导致所有 D-pad 按键（上下左右/确认）全部无效。
        binding.root.post { focusFirstItemOrRefresh() }

        startServers()
    }

    override fun onDestroy() {
        httpServer?.stop()
        tcpServer?.stop()
        nsd?.unregister()
        udpAnnouncer?.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /** 初始化文件列表 RecyclerView 与适配器。 */
    private fun initFileList() {
        fileAdapter = FileAdapter(this)
        binding.fileRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.fileRecyclerView.adapter = fileAdapter

        // 遥控器（D-pad）导航。
        //
        // 注意：RecyclerView **不能**自身可聚焦（isFocusable=true）。
        // 一旦它可聚焦，框架会把焦点优先交给 RecyclerView 本身，
        // 子条目里的按钮请求焦点时会被系统「就近寻址」覆盖回去，
        // 表现为按方向键焦点在列表内乱跳而不按预期移动。
        binding.fileRecyclerView.isFocusable = false
        binding.fileRecyclerView.isFocusableInTouchMode = false
        // 允许条目获得焦点，但不把焦点留在容器自身
        binding.fileRecyclerView.descendantFocusability =
            ViewGroup.FOCUS_BEFORE_DESCENDANTS

        // 焦点辅助器：负责启动时的初始焦点与当前选中项上报
        focusNavigator = FocusNavigator(
            recycler = binding.fileRecyclerView,
            adapter = fileAdapter,
        )

        // 列表内容变化后，焦点可能随条目移除而丢失；此时重新指回首项。
        // 用 hasFocus()（而非 focusedChild）判断：后者在焦点位于左侧面板时也是 null，
        // 会误把焦点从二维码面板抢回列表。
        binding.fileRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!binding.fileRecyclerView.hasFocus()) {
                focusFirstItemOrRefresh()
            }
        }
    }

    /**
     * 把焦点放到列表首项；列表为空时退回「刷新」按钮。
     *
     * 这是遥控器可用性的关键：Activity 启动后必须有一个明确的焦点落点，
     * 否则 D-pad 按键全部无效（用户看到「界面正常但按遥控器没反应」）。
     */
    private fun focusFirstItemOrRefresh() {
        binding.fileRecyclerView.post {
            if (focusNavigator.focusFirstRow()) return@post
            binding.btnRefresh.requestFocus()
        }
    }

    /** 初始化「刷新」与「清空」按钮。 */
    private fun initButtons() {
        binding.btnRefresh.setOnClickListener { refreshFileList() }
        binding.btnRefresh.setOnFocusChangeListener { _, hasFocus ->
            binding.btnRefresh.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFF00BCD4.toInt())
        }

        // 「清空」：只清空列表，磁盘文件保留
        binding.btnClear.setOnClickListener { confirmClearList() }
        binding.btnClear.setOnFocusChangeListener { _, hasFocus ->
            binding.btnClear.setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFE94560.toInt())
        }
    }

    // ------------------------------------------------------------------
    // 服务启动
    // ------------------------------------------------------------------

    /**
     * 启动内置服务：
     * - HTTP 上传服务器（扫码即传，主通道）；
     * - TCP 接收服务（兼容既有原生 App 通道与 Python 参考实现）；
     * - NSD 服务注册（让手机端自动发现本机）。
     */
    private fun startServers() {
        val ip = PortUtil.lan() ?: "127.0.0.1"
        val url = "http://$ip:${HttpProto.HTTP_PORT}"
        binding.urlText.text = url

        // 二维码在 IO 线程生成后切回主线程设置
        GlobalScope.launch(Dispatchers.Default) {
            val bitmap = QrCodeUtil.createQRCodeBitmap(url)
            withContext(Dispatchers.Main) {
                if (bitmap != null) binding.qrCodeImage.setImageBitmap(bitmap)
            }
        }

        // 左上角版本标识：SFTT v3.0（与桌面图标名 SFTT 对应）
        binding.tvVersionName.text = getString(R.string.tv_version_label)

        // HTTP 上传服务器
        httpServer = HttpUploadServer(
            uploadDir = fileRepository.uploadDir,
            listener = this,
            indexHtmlProvider = { loadIndexHtml() },
        ).also {
            runCatching { it.start() }
                .onSuccess { Log.d(TAG, "HTTP server started on $url") }
                .onFailure { Log.e(TAG, "HTTP server start failed", it) }
        }

        // TCP 接收服务（兼容通道）
        TvServer.enableFileDiagnostics(java.io.File(fileRepository.uploadDir.parentFile?.absolutePath ?: "/data/local/tmp"))
        tcpServer = TvServer(fileRepository.uploadDir.absolutePath, listener = this).also {
            runCatching { it.start() }
                .onSuccess { Log.d(TAG, "TCP server started") }
                .onFailure { Log.e(TAG, "TCP server start failed", it) }
        }

        // NSD 注册，广播控制端口 + 数据端口 + HTTP 端口
        nsd = NsdRegistrar(this).also { it.register() }

        // UDP 宣告器（与 Python 参考实现的 UDP 发现协议互通）：
        // 让非 Android 的扫描端（如 Python 手机端）也能自动发现本设备。
        udpAnnouncer = UdpAnnouncer(
            name = deviceName(),
            deviceId = deviceId(),
            controlPort = com.lanfile.shared.CONTROL_PORT,
            httpPort = HttpProto.HTTP_PORT,
        ).also { it.start() }
    }

    /**
     * 设备显示名：统一为 "SEND FILE TO TV"。
     *
     * 这是手机端「打开 TV 时显示的名字」——NSD 服务名与 UDP 广播名都用它，
     * 保证两端看到的名称一致。设备型号另由 UDP 的 `model` 字段携带。
     */
    private fun deviceName(): String = NsdRegistrar.DEVICE_DISPLAY_NAME

    /** 设备唯一 ID：包名 + 安装标识，保证同一设备稳定不变。 */
    private fun deviceId(): String {
        val id = runCatching {
            val prefs = getSharedPreferences("lanfiletransfer", MODE_PRIVATE)
            prefs.getString("device_id", null) ?: run {
                val generated = java.util.UUID.randomUUID().toString().take(12)
                prefs.edit().putString("device_id", generated).commit()
                generated
            }
        }.getOrNull()
        return id ?: "lanfile-tv"
    }

    /** 读取内置 Web 上传页。 */
    private fun loadIndexHtml(): String? = try {
        resources.openRawResource(com.lanfile.tv.R.raw.index).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "read index.html failed", e)
        null
    }

    // ------------------------------------------------------------------
    // 传输记录：加载与持久化
    // ------------------------------------------------------------------

    /** 启动时从仓库恢复传输记录并渲染到列表。 */
    private fun loadSavedFiles() {
        val records = fileRepository.load()
        // records 已按时间倒序（最新在前），按序追加即可保持「最新在最上」
        for (sf in records) {
            savedFiles.add(sf)
            fileAdapter.appendItem(
                FileItem(
                    name = sf.name,
                    size = sf.size,
                    file = File(sf.path),
                    progress = 100,
                    status = FileStatus.COMPLETED,
                )
            )
        }
        updateEmptyState()
    }

    /** 新增或覆盖一条传输记录（内存 + 磁盘）并刷新标题。 */
    private fun saveRecord(fileName: String, file: File) {
        savedFiles.removeAll { it.name == fileName }
        val record = SavedFile(
            name = fileName,
            size = file.length(),
            path = file.absolutePath,
            time = System.currentTimeMillis(),
        )
        savedFiles.add(0, record)
        fileRepository.upsert(record)
    }

    /** 刷新标题中的计数。 */
    private fun updateTitle() {
        val count = savedFiles.size
        binding.fileListTitle.text =
            if (count > 0) "${getString(R.string.file_list)} ($count)" else getString(R.string.file_list)
    }

    /** 依据列表是否为空切换空态视图。 */
    private fun updateEmptyState() {
        if (fileAdapter.getItems().isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.fileRecyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.fileRecyclerView.visibility = View.VISIBLE
        }
    }

    /** 从磁盘重新扫描文件列表（用于「刷新」按钮）。 */
    private fun refreshFileList() {
        GlobalScope.launch(Dispatchers.IO) {
            val records = fileRepository.load()
            withContext(Dispatchers.Main) {
                savedFiles.clear()
                // 先收集名称再逐个移除，避免边遍历边修改
                fileAdapter.getItems().map { it.name }.forEach { fileAdapter.removeItem(it) }
                for (sf in records) {
                    savedFiles.add(sf)
                    fileAdapter.appendItem(
                        FileItem(
                            name = sf.name,
                            size = sf.size,
                            file = File(sf.path),
                            progress = 100,
                            status = FileStatus.COMPLETED,
                        )
                    )
                }
                updateTitle()
                updateEmptyState()
            }
        }
    }

    /**
     * 「清空」：清空列表中的全部条目，但**保留磁盘上的文件**。
     *
     * 先弹确认框——这是批量操作，误触的代价（用户以为文件被删了）比多点一次大。
     *
     * 注意：TV 上对话框的默认焦点会落在 `buttonPanel` 容器上，此时按 OK **没有任何反应**
     * （实测 uiautomator：焦点节点 = `buttonPanel`，不是具体按钮）。
     * 因此这里显式把焦点交给「清空」按钮，保证遥控器用户能直接操作。
     */
    private fun confirmClearList() {
        val items = fileAdapter.getItems()
        val count = items.size
        if (count == 0) {
            Toast.makeText(this, getString(R.string.clear_nothing), Toast.LENGTH_SHORT).show()
            return
        }
        val uploading = items.count { it.status == FileStatus.UPLOADING }
        val message = if (uploading > 0) {
            getString(R.string.clear_message_partial, count, uploading)
        } else {
            getString(R.string.clear_message, count)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm) { _, _ -> performClearList() }
            .create()
        dialog.setOnShowListener {
            // 把焦点放到「清空」按钮上（TV 遥控器必须有一个明确的焦点落点）
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.requestFocus()
        }
        dialog.show()
    }

    /**
     * 执行清空：移除列表条目与传输记录，**不动磁盘文件**。
     *
     * 被清空的文件名会被记入「已隐藏」集合，这样目录扫描兜底不会把它们
     * 重新补录回列表（否则清空在下次刷新/重启后就失效了）。
     * 用户之后重新上传同名文件会自动恢复显示。
     *
     * 仍在「上传中」的条目不参与清空：它们还没完成，清走后上传完成时
     * 又会写回记录，语义上自相矛盾；保留在列表里更符合直觉。
     */
    private fun performClearList() {
        val all = fileAdapter.getItems()
        // 上传中的条目保留（file == null 即尚未落盘完成）
        val uploading = all.filter { it.status == FileStatus.UPLOADING }.map { it.name }
        val names = all.filter { it.status != FileStatus.UPLOADING }.map { it.name }
        if (names.isEmpty() && uploading.isEmpty()) {
            Toast.makeText(this, getString(R.string.clear_nothing), Toast.LENGTH_SHORT).show()
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            fileRepository.hideAll(names)
            withContext(Dispatchers.Main) {
                savedFiles.removeAll { it.name in names }
                fileAdapter.removeItems(names.toSet())
                updateTitle()
                updateEmptyState()
                val msg = if (uploading.isEmpty()) {
                    getString(R.string.clear_done, names.size)
                } else {
                    getString(R.string.clear_done_partial, names.size, uploading.size)
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 上传服务器回调（工作线程 -> 主线程）
    // ------------------------------------------------------------------

    /** 上传会话创建：插入「上传中」条目。 */
    override fun onSessionStart(sessionId: String, fileName: String, totalBytes: Long) {
        handler.post {
            fileAdapter.addItem(
                FileItem(
                    name = fileName,
                    size = totalBytes,
                    file = null,
                    progress = 0,
                    status = FileStatus.UPLOADING,
                )
            )
            updateEmptyState()
        }
    }

    /** 上传进度：刷新条目进度条与实时速度。 */
    override fun onProgress(sessionId: String, fileName: String, received: Long, total: Long) {
        val pct = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0
        val speed = sampleSpeed(fileName, received)
        handler.post { fileAdapter.updateProgress(fileName, pct, speed) }
    }

    /**
     * 采样实时上传速度（相邻两次进度差 / 时间差）。
     *
     * 首次采样时返回空串，避免显示一个虚假的巨大瞬时速度。
     */
    private fun sampleSpeed(fileName: String, received: Long): String {
        val now = System.currentTimeMillis()
        val prevAt = lastSampleAt[fileName]
        val prevBytes = lastSampleBytes[fileName]
        lastSampleAt[fileName] = now
        lastSampleBytes[fileName] = received
        if (prevAt == null || prevBytes == null) return ""
        val dt = (now - prevAt).coerceAtLeast(1L)
        if (received <= prevBytes) return ""
        val bytesPerSec = (received - prevBytes) * 1000 / dt
        return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    }

    /** 上传完成：持久化记录；若是 APK 则拉起安装器。 */
    override fun onFileComplete(sessionId: String, fileName: String, file: File) {
        handler.post {
            Toast.makeText(this, "$fileName 接收完成", Toast.LENGTH_SHORT).show()
            if (ApkInstaller.isApk(fileName)) {
                fileAdapter.setInstalling(fileName)
                pendingApkFile = fileName
                launchInstall(file)
            } else {
                fileAdapter.completeUpload(fileName, file)
                saveRecord(fileName, file)
                updateTitle()
            }
        }
    }

    /** 上传失败：标记条目失败并提示原因。 */
    override fun onError(sessionId: String, fileName: String, error: String) {
        handler.post {
            fileAdapter.setError(fileName)
            Toast.makeText(this, "$fileName 接收失败: $error", Toast.LENGTH_SHORT).show()
        }
    }

    /** 文件被删除：同步移除列表条目与记录。 */
    override fun onFileDeleted(fileName: String) {
        handler.post {
            fileAdapter.removeItem(fileName)
            savedFiles.removeAll { it.name == fileName }
            fileRepository.remove(fileName)
            updateTitle()
            updateEmptyState()
        }
    }

    // ------------------------------------------------------------------
    // TCP 接收服务回调（兼容通道）
    // ------------------------------------------------------------------

    override fun onSessionStarted(sessionId: String) {
        Log.d(TAG, "TCP session $sessionId started")
    }

    override fun onProgress(sessionId: String, states: Map<String, TvServer.FileInfo>) {
        // TCP 通道的文件进度统一汇入列表（按文件名匹配）
        for (s in states.values) {
            val pct = if (s.total > 0) (s.received * 100 / s.total).toInt().coerceIn(0, 100) else 0
            handler.post { fileAdapter.updateProgress(s.name, pct) }
        }
    }

    override fun onFileDone(sessionId: String, fileId: String, name: String, ok: Boolean, error: String) {
        if (!ok) {
            handler.post {
                fileAdapter.setError(name)
                Toast.makeText(this, "$name 接收失败: $error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSessionDone(sessionId: String, results: Map<String, TvServer.FileResult>) {
        Log.d(TAG, "TCP session $sessionId done: ${results.size} files")
    }

    // ------------------------------------------------------------------
    // 遥控器支持（电视自带遥控器 + 手机遥控器）
    // ------------------------------------------------------------------

    /**
     * 统一按键入口。
     *
     * **物理遥控器**（电视自带）与**手机遥控器**（网页面板）最终行为一致，但走的路径不同：
     *
     * - 物理按键由系统派发，方向键的焦点搜索在系统输入管线层完成
     *   （`ViewRootImpl.performFocusNavigation`），App 层无需干预；
     * - 手机按键由 App 主动派发，**到不了**系统输入管线层，方向键必须自己复刻
     *   那套焦点搜索算法（见 [FocusNavigator.moveFocus]）。
     *
     * 方向键**不做拦截**：交给系统的几何焦点搜索处理。手动 `requestFocus` 跨条目移动
     * 在 RecyclerView 里不可靠——目标行一旦需要滚动就会被回收重建，正在聚焦的视图
     * 随之失效，表现为「按了没反应」或焦点乱跳。系统的几何焦点搜索天然正确。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return super.dispatchKeyEvent(event)
    }

    /**
     * 处理来自手机遥控器的按键。
     *
     * - **方向键**：复刻系统 `ViewRootImpl.performFocusNavigation` 的焦点搜索
     *   （`focusSearch` + `requestFocus`），由 [FocusNavigator.moveFocus] 完成。
     *   这一步是手机遥控器能用的关键——方向键的焦点移动发生在系统输入管线层，
     *   App 层单纯派发 [KeyEvent] 是无效的（视图树本就不消费方向键）。
     * - **确认键 / 返回键**：派发成对的 ACTION_DOWN + ACTION_UP，
     *   让目标控件走 `View.onKeyDown`/`onKeyUp` 的标准语义
     *   （`onKeyUp` 中 `performClickInternal()` 触发点击）。
     */
    override fun onRemoteKey(key: String): String {
        // HTTP 回调来自工作线程，必须切回主线程再处理按键。
        handler.post {
            when (key) {
                "up" -> focusNavigator.moveFocus(View.FOCUS_UP)
                "down" -> focusNavigator.moveFocus(View.FOCUS_DOWN)
                "left" -> focusNavigator.moveFocus(View.FOCUS_LEFT)
                "right" -> focusNavigator.moveFocus(View.FOCUS_RIGHT)
                "ok" -> dispatchKeyPair(KeyEvent.KEYCODE_DPAD_CENTER)
                "back" -> dispatchKeyPair(KeyEvent.KEYCODE_BACK)
            }
        }
        return ""
    }

    /**
     * 派发一对完整的按键事件（ACTION_DOWN + ACTION_UP）。
     *
     * 必须成对派发：`View.onKeyDown` 在确认键上只负责 `setPressed(true)`，
     * 真正的点击发生在 `View.onKeyUp` 的 `performClickInternal()`。
     * 只发 DOWN 会表现为「按了有高亮但没反应」。
     */
    private fun dispatchKeyPair(keyCode: Int) {
        val now = System.currentTimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * 汇总当前焦点状态，供手机遥控器同步显示 TV 端选中了什么。
     *
     * 可能从 HTTP 工作线程调用，读 UI 状态必须切回主线程。
     */
    override fun onRemoteState(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return buildFocusState()
        }
        // 工作线程：切回主线程同步取一次
        var result = "{}"
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            result = buildFocusState()
            latch.countDown()
        }
        // 最多等 500ms，避免主线程卡顿时拖住 HTTP 响应
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    /** 生成当前焦点状态的 JSON 字符串（需在主线程调用）。 */
    private fun buildFocusState(): String {
        return runCatching {
            org.json.JSONObject()
                .put("focus", focusNavigator.currentLabel())
                .put("count", fileAdapter.getItems().size)
                .toString()
        }.getOrElse { "{}" }
    }

    /**
     * 物理遥控器按键处理。
     *
     * 根布局此前设置了 `focusableInTouchMode=true`，会把焦点吸到根 ViewGroup 上，
     * 导致所有 D-pad 按键失效；现由布局侧修正，这里只兜底处理「返回键」。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 返回键：焦点在列表中时先回列表首项，仍在首项则退出应用
            if (binding.fileRecyclerView.hasFocus()) {
                focusFirstItemOrRefresh()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // 文件操作
    // ------------------------------------------------------------------

    /** 「打开」：通过 FileProvider 拉起系统默认应用查看文件。 */
    override fun onOpen(item: FileItem) {
        val file = item.file
        if (file == null || !file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open file failed", e)
            Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 「删除」：删除磁盘文件与持久化记录，并从列表移除条目。 */
    override fun onDelete(item: FileItem) {
        val file = item.file
        if (file != null && file.exists()) {
            if (!file.delete()) {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                return
            }
        }
        fileAdapter.removeItem(item.name)
        savedFiles.removeAll { it.name == item.name }
        fileRepository.remove(item.name)
        updateTitle()
        Toast.makeText(this, "已删除: ${item.name}", Toast.LENGTH_SHORT).show()
        updateEmptyState()
    }

    /**
     * 拉起系统安装器安装上传的 APK。
     * 拉起失败时把条目收尾为已完成并持久化，避免条目卡在安装态。
     */
    private fun launchInstall(file: File) {
        try {
            val intent = ApkInstaller.buildInstallIntent(this, file)
            installLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            pendingApkFile = null
            fileAdapter.completeUpload(file.name, file)
            saveRecord(file.name, file)
            updateTitle()
            Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 根据扩展名推断 MIME 类型。 */
    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }
}
