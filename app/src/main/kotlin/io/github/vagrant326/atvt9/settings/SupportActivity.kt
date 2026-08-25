package io.github.vagrant326.atvt9.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.vagrant326.atvt9.R

/**
 * Asks for a voluntary donation, and says plainly that nothing is withheld without one.
 *
 * The QR is drawn with filtering and antialiasing off. A QR code is hard pixels, and letting
 * Android smooth it while scaling to TV size blurs the module edges — which is exactly what a
 * phone camera has to resolve.
 */
class SupportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val code = ImageView(this).apply {
            val bitmap = ContextCompat.getDrawable(context, R.drawable.support_qr) as BitmapDrawable
            bitmap.isFilterBitmap = false
            bitmap.paint.isAntiAlias = false
            setImageDrawable(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(300))
        }

        val codeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = card(CARD)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = stack(dp(20))
            addView(code)
            addView(
                label(getString(R.string.support_scan), MUTED, 13f).apply {
                    setPadding(0, dp(14), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            addView(
                label(getString(R.string.support_or), MUTED, 13f).apply {
                    setPadding(0, dp(12), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            // Spelled out as well as encoded: a QR is no use to anyone without a phone in
            // reach, and this is short enough to read off the screen and type.
            addView(
                label(getString(R.string.support_url), Color.WHITE, 17f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(4), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(getString(R.string.support_title), Color.WHITE, 28f))
            addView(
                label(getString(R.string.support_free), SECONDARY, 15f)
                    .apply { setPadding(0, dp(10), 0, 0) }
            )
            addView(
                label(getString(R.string.support_thanks), SECONDARY, 15f)
                    .apply { setPadding(0, dp(12), 0, 0) }
            )
            addView(codeCard)
            addView(
                label(getString(R.string.support_back), MUTED, 13f)
                    .apply { setPadding(0, dp(18), 0, 0) }
            )
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    LinearLayout(this@SupportActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(28), dp(28), dp(32))
                        addView(content)
                    }
                )
            }
        )
    }

    private fun label(text: String, colour: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun card(colour: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(colour)
    }

    private fun stack(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF08080B.toInt()
        const val CARD = 0xFF16161C.toInt()
        const val SECONDARY = 0xFFB0B0BC.toInt()
        const val MUTED = 0xFF6B6B78.toInt()
    }
}
