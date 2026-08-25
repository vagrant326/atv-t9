package io.github.vagrant326.atvt9.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.vagrant326.atvt9.BuildConfig
import io.github.vagrant326.atvt9.R
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

private sealed interface Check {
    data class Newer(val tag: String) : Check
    data object UpToDate : Check
    data object NoReleases : Check
    data class Failed(val detail: String) : Check
}

/**
 * The only component in this app that touches the network, and it runs in its own process
 * (`:updater`, see the manifest) so the component handling keystrokes contains no networking and
 * no install code at all.
 *
 * Nothing runs unless the user opens this screen and presses something: no background job, no
 * boot receiver, no periodic poll, no check when the keyboard starts. One request for the
 * version, one for the file, no payload, no device identifier, no analytics. Nothing typed on
 * this device is ever sent anywhere — and this app has more to be careful with than its
 * siblings, because it is the one that keeps a dictionary of what the user types.
 *
 * Installing goes through [PackageInstaller] rather than an `ACTION_VIEW` intent. The intent
 * route cannot report anything: handing it a file succeeds even when the installer then shows
 * nothing, which is the dead end a sibling's second update ran into. A session reports back a
 * status and a message, and hands us the confirmation dialog explicitly instead of leaving it to
 * chance.
 *
 * All of it exists only because sideloading has no update channel. It comes out if the project
 * ever ships through a store.
 */
class UpdateActivity : Activity() {

    private lateinit var stateLabel: TextView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var stateCard: LinearLayout
    private lateinit var primary: Button
    private lateinit var secondary: Button

    private val worker = Executors.newSingleThreadExecutor()

    private val installResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // The confirmation dialog. With the intent route this step was implicit and
                    // could silently not happen; here it is ours to launch.
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        show(
                            getString(R.string.update_problem),
                            getString(R.string.update_no_dialog_headline),
                            getString(R.string.update_no_dialog_body),
                            CARD_WARNING,
                        )
                        return
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(confirm) }.onFailure {
                        show(
                            getString(R.string.update_problem),
                            getString(R.string.update_dialog_failed),
                            it.javaClass.simpleName,
                            CARD_WARNING,
                        )
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    show(
                        getString(R.string.update_state_done),
                        getString(R.string.update_installed_headline),
                        getString(R.string.update_installed_body),
                        CARD_OK,
                    )
                    primary.visibility = View.GONE
                    secondary.visibility = View.GONE
                }

                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: getString(R.string.update_no_detail)
                    show(
                        getString(R.string.update_state_error),
                        getString(R.string.update_install_failed),
                        getString(R.string.update_install_status, code, message),
                        CARD_WARNING,
                    )
                    offerPrimary(getString(R.string.update_try_again)) { download() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stateLabel = label(getString(R.string.update_state_checking), MUTED, 12f)
        headline = label(getString(R.string.update_working), Color.WHITE, 30f)
        detail = label("", SECONDARY, 15f)

        stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(CARD)
            setPadding(dp(24), dp(22), dp(24), dp(24))
            layoutParams = stack(dp(20))
            addView(stateLabel)
            addView(headline.apply { setPadding(0, dp(6), 0, 0) })
            addView(detail.apply { setPadding(0, dp(8), 0, 0) })
        }

        primary = action(getString(R.string.update_working)) {}.apply { visibility = View.GONE }
        secondary = action(getString(R.string.update_check_again)) { check() }
            .apply { visibility = View.GONE }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = stack(dp(12))
            addView(primary)
            addView(secondary)
        }

        val installedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(SUNKEN)
            setPadding(dp(24), dp(16), dp(24), dp(18))
            layoutParams = stack(dp(16))
            addView(label(getString(R.string.update_installed_label), MUTED, 12f))
            addView(
                label(BuildConfig.VERSION_NAME, SECONDARY, 15f)
                    .apply { setPadding(0, dp(4), 0, 0) }
            )
            addView(
                label(getString(R.string.update_privacy), MUTED, 13f)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(getString(R.string.update_title), Color.WHITE, 28f))
            addView(
                label(getString(R.string.update_subtitle), SECONDARY, 15f)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
            addView(stateCard)
            addView(actions)
            addView(installedCard)
            addView(
                label(getString(R.string.update_back), MUTED, 13f)
                    .apply { setPadding(0, dp(18), 0, 0) }
            )
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    LinearLayout(this@UpdateActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(28), dp(28), dp(32))
                        addView(content)
                    }
                )
            }
        )

        ContextCompat.registerReceiver(
            this,
            installResult,
            IntentFilter(installAction()),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        check()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(installResult) }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun show(state: String, title: String, body: String, cardColour: Int = CARD) {
        stateLabel.text = state
        headline.text = title
        detail.text = body
        detail.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        stateCard.background = card(cardColour)
    }

    private fun offerPrimary(text: String, onClick: () -> Unit) {
        primary.text = text
        primary.isEnabled = true
        primary.visibility = View.VISIBLE
        primary.setOnClickListener { onClick() }
        primary.requestFocus()
    }

    private fun check() {
        show(
            getString(R.string.update_state_checking),
            getString(R.string.update_working),
            "",
        )
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
        worker.execute {
            val outcome = fetchLatest()
            runOnUiThread { present(outcome) }
        }
    }

    private fun present(result: Check) {
        secondary.visibility = View.VISIBLE
        when (result) {
            is Check.UpToDate -> show(
                getString(R.string.update_state_uptodate),
                BuildConfig.VERSION_NAME,
                getString(R.string.update_uptodate_body),
                CARD_OK,
            )

            is Check.NoReleases -> show(
                getString(R.string.update_state_none),
                getString(R.string.update_none_headline),
                getString(R.string.update_none_body),
            )

            is Check.Failed -> show(
                getString(R.string.update_state_failed),
                getString(R.string.update_failed_headline),
                result.detail,
                CARD_WARNING,
            )

            is Check.Newer -> {
                show(
                    getString(R.string.update_state_available),
                    result.tag,
                    getString(R.string.update_available_body, BuildConfig.VERSION_NAME),
                    CARD_ACCENT,
                )
                offerPrimary(getString(R.string.update_download)) { download() }
            }
        }
    }

    private fun download() {
        primary.isEnabled = false
        primary.text = getString(R.string.update_busy)
        show(
            getString(R.string.update_state_downloading),
            getString(R.string.update_percent, 0),
            "",
        )
        worker.execute {
            val target = File(cacheDir, "update.apk")
            val outcome = runCatching { fetch(target) }
            runOnUiThread {
                outcome.fold(
                    onSuccess = { install(target) },
                    onFailure = {
                        show(
                            getString(R.string.update_state_error),
                            getString(R.string.update_downloading_failed),
                            "${it.javaClass.simpleName}\n${it.message ?: ""}",
                            CARD_WARNING,
                        )
                        offerPrimary(getString(R.string.update_try_again)) { download() }
                    },
                )
            }
        }
    }

    private fun fetch(target: File) {
        // A stale file from the previous attempt is one of the few things that differs between
        // the first update and the second, so start from nothing rather than writing over it.
        target.delete()
        val connection = URL(DOWNLOAD_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val total = connection.contentLength.toLong()
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            runOnUiThread {
                                headline.text = getString(R.string.update_percent, percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // An APK is a zip. Checking the magic bytes turns "the installer did nothing" into a
        // message here, where the cause is still visible.
        require(target.length() > MINIMUM_APK_BYTES) {
            "downloaded ${target.length()} bytes, too small to be an APK"
        }
        val header = target.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        require(header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            "downloaded file is not a zip, so not an APK"
        }
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            show(
                getString(R.string.update_state_permission),
                getString(R.string.update_permission_headline),
                getString(R.string.update_permission_body),
                CARD_WARNING,
            )
            offerPrimary(getString(R.string.update_permission_open)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
                offerPrimary(getString(R.string.update_install)) { install(apk) }
            }
            return
        }

        show(
            getString(R.string.update_state_installing),
            getString(R.string.update_installing_headline, apk.length() / 1024),
            getString(R.string.update_installing_body),
        )
        primary.isEnabled = false
        primary.text = getString(R.string.update_busy)

        runCatching {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(pendingResult(sessionId).intentSender)
            }
        }.onFailure { failure ->
            show(
                getString(R.string.update_state_error),
                getString(R.string.update_start_failed),
                "${failure.javaClass.simpleName}\n${failure.message ?: ""}",
                CARD_WARNING,
            )
            offerPrimary(getString(R.string.update_try_again)) { download() }
        }
    }

    /** Mutable on purpose: the system fills in the status extras on this intent. */
    private fun pendingResult(sessionId: Int): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(
            this,
            sessionId,
            Intent(installAction()).setPackage(packageName),
            flags,
        )
    }

    private fun installAction() = "$packageName.INSTALL_RESULT"

    /**
     * Lists releases and picks the newest tag belonging to **this** channel.
     *
     * `releases/latest` cannot be used: the two channels share a repository, and the dev releases
     * are published with `--latest=false` precisely so they do not steal that endpoint from the
     * production one. Filtering by tag prefix is what keeps a dev build from offering to install
     * a production APK over itself, and vice versa.
     */
    private fun fetchLatest(): Check {
        val connection = try {
            URL(API_URL).openConnection() as HttpsURLConnection
        } catch (failure: Exception) {
            return Check.Failed(failure.javaClass.simpleName)
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            // Android's HttpURLConnection is OkHttp-backed and can throw here on a 404 rather
            // than returning the code. Before the first release exists a 404 is the normal
            // state, so it must not surface as an error.
            val code = try {
                connection.responseCode
            } catch (absent: FileNotFoundException) {
                HttpURLConnection.HTTP_NOT_FOUND
            }

            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> Check.NoReleases
                code !in 200..299 -> Check.Failed("HTTP $code")
                else -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val newest = TAG_NAME.findAll(body)
                        .map { it.groupValues[1] }
                        .filter { it.startsWith(BuildConfig.RELEASE_TAG_PREFIX) }
                        // The rolling alias tags carry no version and must not be compared.
                        .filter { version(it).isNotEmpty() }
                        .maxWithOrNull { left, right -> compare(version(left), version(right)) }
                        ?: return Check.NoReleases
                    if (isNewer(newest, BuildConfig.VERSION_NAME)) {
                        Check.Newer(newest)
                    } else {
                        Check.UpToDate
                    }
                }
            }
        } catch (failure: Exception) {
            Check.Failed(failure.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun version(text: String): List<Int> =
        text.removePrefix(BuildConfig.RELEASE_TAG_PREFIX)
            .split('.', '-')
            .mapNotNull { it.toIntOrNull() }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) {
                return a.compareTo(b)
            }
        }
        return 0
    }

    /**
     * Component-wise and numeric, so `0.0.10` is not read as older than `0.0.9` and a debug build
     * reporting `0.0.0-dev` sees any release as newer.
     */
    private fun isNewer(tag: String, installed: String): Boolean =
        compare(version(tag), version(installed)) > 0

    private fun label(text: String, colour: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = card(SUNKEN)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(10) }
        setOnFocusChangeListener { view, hasFocus ->
            view.background = card(if (hasFocus) FOCUSED else SUNKEN)
        }
        setOnClickListener { onClick() }
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
        const val REPO = "vagrant326/atv-t9"
        const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=40"
        val DOWNLOAD_URL = "https://github.com/$REPO/releases/download/" +
            "${BuildConfig.RELEASE_ALIAS}/${BuildConfig.RELEASE_ASSET}"
        const val TIMEOUT_MS = 20_000
        const val MINIMUM_APK_BYTES = 100_000L
        const val APK_ENTRY = "atv-t9.apk"
        val TAG_NAME = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()

        const val BACKGROUND = 0xFF08080B.toInt()
        const val CARD = 0xFF16161C.toInt()
        const val CARD_ACCENT = 0xFF14283A.toInt()
        const val CARD_OK = 0xFF14241C.toInt()
        const val CARD_WARNING = 0xFF2E2212.toInt()
        const val SUNKEN = 0xFF101014.toInt()
        const val FOCUSED = 0xFF2A3A46.toInt()
        const val SECONDARY = 0xFFB0B0BC.toInt()
        const val MUTED = 0xFF6B6B78.toInt()
    }
}
