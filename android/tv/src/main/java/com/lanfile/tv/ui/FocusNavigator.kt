package com.lanfile.tv.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanfile.tv.R

/**
 * 遥控器焦点辅助器。
 *
 * ## 焦点模型
 *
 * 焦点落在**可聚焦控件**上，由系统的几何焦点搜索负责方向键寻址：
 *
 * ```
 *   左侧二维码面板 ←→ 右侧「刷新」按钮
 *                          ↓
 *                     第 1 条「打开」 ←→ 「删除」
 *                          ↓
 *                     第 2 条「打开」 ←→ 「删除」
 * ```
 *
 * ## 为什么不用代码计算目标视图
 *
 * 曾经尝试在 `RecyclerView` 里用 `requestFocus()` 手动跨条目移动焦点，但**行不通**：
 * 目标行一旦需要滚动就会被回收重建，正在聚焦的视图随之失效，
 * 表现为「按了没反应」或焦点跳回原处。
 * 系统的几何焦点搜索天然正确，因此这里只负责：
 *
 * 1. [focusFirstRow] —— 启动时给出一个明确的焦点落点（否则所有按键都无效）；
 * 2. [currentLabel] —— 汇报当前选中项（供手机遥控器同步显示）。
 */
class FocusNavigator(
    private val recycler: RecyclerView,
    private val adapter: FileAdapter,
) {

    /**
     * 把焦点放到列表首行的「打开」按钮。
     *
     * 这是遥控器可用性的关键：Activity 启动后若没有焦点落点，
     * D-pad 按键会被系统丢弃，用户看到「界面正常但按遥控器没反应」。
     *
     * @return 是否成功聚焦到列表（列表为空时返回 false，由调用方决定退到哪个按钮）
     */
    fun focusFirstRow(): Boolean {
        if (adapter.itemCount == 0) return false
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return false
        val row = layoutManager.findViewByPosition(0) ?: return false
        val target = row.findViewById<View>(R.id.btnOpen)
        val ok = target != null && target.requestFocus()
        if (!ok) {
            // 条目尚未完成布局：下一帧再试一次
            recycler.post {
                val retry = layoutManager.findViewByPosition(0)
                retry?.findViewById<View>(R.id.btnOpen)?.requestFocus()
            }
        }
        return ok
    }

    /** 当前聚焦项的显示文案（需在主线程调用）。 */
    fun currentLabel(): String {
        val focused = recycler.focusedChild ?: return ""
        return when (focused.id) {
            R.id.btnRefresh -> "刷新"
            R.id.leftPanel -> "二维码面板"
            R.id.itemRoot, R.id.btnOpen, R.id.btnDelete -> {
                val row = rowOf(focused) ?: return ""
                row.findViewById<TextView>(R.id.fileName)?.text?.toString() ?: ""
            }
            else -> ""
        }
    }

    /**
     * 按指定方向移动焦点（复刻系统 `ViewRootImpl.performFocusNavigation`）。
     *
     * ## 为什么需要自己实现
     *
     * Android 的方向键焦点搜索**不在视图树里**，而在系统输入管线层：
     * `ViewRootImpl.processKeyEvent()` 先让视图树消费按键，**消费失败之后**才调用
     * `performFocusNavigation()` 做几何焦点搜索。
     *
     * ```
     * ViewRootImpl.processKeyEvent(event)
     *   ├─ mView.dispatchKeyEvent(event)   ← 视图树消费；DPAD 方向键无人消费 → false
     *   ├─ shouldDropInputEvent / mUnhandledKeyManager
     *   └─ performFocusNavigation(event)   ← 焦点真正在这里移动
     * ```
     *
     * 因此 App 层调 `dispatchKeyEvent()` **永远走不到**焦点移动：
     * 方向键本就不被任何 View 消费（诊断日志里的 `downHandled=false` 即此现象，
     * 它是正常的，不是 bug）。物理遥控器由系统派发，所以能正常移动焦点；
     * 手机遥控器是 App 主动派发，必须自己复刻这套算法。
     *
     * ## 复刻内容
     *
     * 全部使用公开 API（API 1 起，兼容 minSdk 21），与系统逐行等价：
     *
     * 1. `focused.focusSearch(direction)` —— 沿父链向上查找几何上最近的候选。
     *    焦点在列表内时会命中 `RecyclerView.focusSearch()` 的重写，
     *    从而获得列表边缘自动滚动（`onFocusSearchFailed`）的能力。
     * 2. `target.requestFocus(direction, rect)` —— 带方向与矩形落焦，
     *    让目标视图知道焦点来自哪个方向（用于滚动对齐）。
     *
     * @param direction [View.FOCUS_UP] / [View.FOCUS_DOWN] / [View.FOCUS_LEFT] / [View.FOCUS_RIGHT]
     * @return 是否成功移动了焦点
     */
    fun moveFocus(direction: Int): Boolean {
        val root = recycler.rootView as? ViewGroup ?: return false
        val focused = root.findFocus()
        if (focused == null) {
            // 界面上还没有任何焦点落点：先给出一个，再重试一次
            // （系统此时会走 restoreDefaultFocus()，效果等同）。
            if (!focusFirstRow()) return false
            return moveFocus(direction)
        }
        val target = focused.focusSearch(direction) ?: return false
        if (target === focused) return false

        // 与系统一致：把「原焦点矩形」换算到「目标视图」的坐标系，
        // 让 requestFocus 知道焦点从哪个方向过来（用于滚动对齐）。
        val rect = Rect()
        focused.getFocusedRect(rect)
        root.offsetDescendantRectToMyCoords(focused, rect)
        root.offsetRectIntoDescendantCoords(target, rect)

        val ok = target.requestFocus(direction, rect)
        if (ok) {
            // 与系统一致地播放焦点移动音效
            runCatching {
                focused.playSoundEffect(
                    android.view.SoundEffectConstants.getConstantForFocusDirection(
                        direction, false
                    )
                )
            }
        }
        return ok
    }

    /**
     * 找到焦点视图所属的条目行视图。
     *
     * 焦点可能落在条目根视图，也可能落在条目内的「打开/删除」按钮上；
     * 后者需向上回溯到条目根视图。
     */
    private fun rowOf(focused: View): View? {
        var node: View? = focused
        while (node != null) {
            if (node.id == R.id.itemRoot) return node
            val parent = node.parent
            node = if (parent is View) parent else null
        }
        return null
    }
}
