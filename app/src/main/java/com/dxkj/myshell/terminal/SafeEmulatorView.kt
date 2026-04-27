package com.dxkj.myshell.terminal

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession

/**
 * 某些版本的 emulatorview 在链接识别(createLinks)时会抛出 ArrayIndexOutOfBoundsException，
 * 直接导致 UI 线程崩溃。这里做兜底，避免整 App 被杀。
 */
class SafeEmulatorView : EmulatorView {
    constructor(context: Context, session: TermSession, metrics: android.util.DisplayMetrics) : super(context, session, metrics)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)
        } catch (_: ArrayIndexOutOfBoundsException) {
            // ignore: prevent crash due to library bug during linkification
        }
    }
}

