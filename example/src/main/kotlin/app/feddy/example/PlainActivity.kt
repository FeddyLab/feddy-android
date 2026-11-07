package app.feddy.example

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.feddy.sdk.Feddy
import app.feddy.sdk.FeddyUnreadListener

/**
 * A plain `android.app.Activity` with Views only — the shape of a Flutter or
 * React Native host. Presenting and the reply popup must work from here.
 */
class PlainActivity : Activity() {
    private lateinit var badge: TextView
    private val listener = FeddyUnreadListener { count -> badge.text = "Unread: $count" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        badge = TextView(this).apply { text = "Unread: …" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 3, padding, padding)
            addView(TextView(this@PlainActivity).apply { text = "Plain Activity host" })
            addView(badge)
            addView(Button(this@PlainActivity).apply {
                text = "Help & support"
                setOnClickListener { Feddy.present(this@PlainActivity) }
            })
        }
        setContentView(layout)
    }

    override fun onStart() {
        super.onStart()
        Feddy.addUnreadCountListener(listener)
        Feddy.unreadCount(listener)
    }

    override fun onStop() {
        super.onStop()
        Feddy.removeUnreadCountListener(listener)
    }
}
