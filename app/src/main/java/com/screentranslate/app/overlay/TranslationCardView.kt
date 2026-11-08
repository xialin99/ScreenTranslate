package com.screentranslate.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** 一张原位覆盖的译文卡片：不透明底色盖住原文，点击切换该条原文/译文 */
class TranslationCardView(context: Context) : TextView(context) {

    var originalText: String = ""
    var translatedText: String = ""
    var showingTranslation: Boolean = true

    init {
        includeFontPadding = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF1F2229.toInt())
            cornerRadius = dp(4f)
        }
    }

    fun bindText() {
        text = if (showingTranslation) translatedText else originalText
    }

    fun toggle() {
        showingTranslation = !showingTranslation
        bindText()
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )
}
