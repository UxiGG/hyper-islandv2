package com.custom.hyperisland.overlay

import android.content.Context
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

class IslandTouchLayout(
    context: Context,
    private val onDismissKeyboardFocus: () -> Unit
) : FrameLayout(context) {

    @Volatile
    private var activeIslandRect = RectF(0f, 0f, 0f, 0f)

    @Volatile
    var isFocusableInputActive: Boolean = false

    private var isTrackingGestureInsideIsland = false

    fun updateInteractiveBounds(left: Float, top: Float, right: Float, bottom: Float) {
        activeIslandRect.set(left, top, right, bottom)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFocusableInputActive && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onDismissKeyboardFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (isFocusableInputActive) {
                onDismissKeyboardFocus()
            }
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inside = activeIslandRect.contains(ev.x, ev.y)
                isTrackingGestureInsideIsland = inside

                if (!inside) {
                    if (isFocusableInputActive) {
                        onDismissKeyboardFocus()
                    }
                    return false
                }
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!isTrackingGestureInsideIsland) {
                    return false
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isTrackingGestureInsideIsland = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
