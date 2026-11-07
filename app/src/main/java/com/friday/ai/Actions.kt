package com.friday.ai

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.concurrent.thread

// AI சொல்ற செயல்களை phone-ல செய்யும் இடம்
object Actions {
    private val main = Handler(Looper.getMainLooper())

    private fun start(ctx: Context, i: Intent) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    private fun view(ctx: Context, url: String) = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun run(ctx: Context, a: JSONObject) {
        when (a.optString("action")) {
            "open_app" -> openApp(ctx, a.optString("app"))
            "youtube_play" -> youtube(ctx, a.optString("query"))
            "web_search" -> view(ctx, "https://www.google.com/search?q=" + URLEncoder.encode(a.optString("query"), "UTF-8"))
            "open_url" -> view(ctx, a.optString("url"))
            "call" -> call(ctx, a.optString("to"))
            "alarm" -> start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "timer" -> start(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "flashlight" -> try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], a.optBoolean("on", true))
            } catch (_: Exception) {}
            "volume" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val d = when (a.optString("dir")) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
            }
            "settings" -> start(ctx, Intent(when (a.optString("page")) {
                "wifi" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY   // Android 10+: நேரடியா on/off முடியாது, panel திறக்கும்
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                else -> Settings.ACTION_SETTINGS }))
            "type_text" -> FridayAccessibility.instance?.typeText(a.optString("text"))
            "tap_text" -> FridayAccessibility.instance?.clickText(a.optString("text"))
            "scroll" -> FridayAccessibility.instance?.scroll(a.optString("dir") != "up")
            "back" -> FridayAccessibility.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> FridayAccessibility.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "send_device" -> thread { DeviceLink.send(ctx, a.optString("device"), a.optString("command")) }
            "wake_pc" -> thread { DeviceLink.wake(ctx, a.optString("device")) }
        }
    }

    // நிறுவிய ஆப்களில் பேரு பொருந்துறதை திறக்கும்
    private fun openApp(ctx: Context, name: String) {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
        hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName)?.let { i -> start(ctx, i) } }
    }

    // YouTube ஆப்ல தேடி, முதல் வீடியோவை tap பண்ணி play பண்ணும் (heuristic - YouTube update-ல மாறலாம்)
    private fun youtube(ctx: Context, q: String) {
        start(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        for (delay in longArrayOf(3500, 5500, 7500)) main.postDelayed({
            FridayAccessibility.instance?.clickDescContains("play video")
        }, delay)
    }

    private fun call(ctx: Context, to: String) {
        var number = to.filter { it.isDigit() || it == '+' }
        if (number.length < 5) {
            try {
                ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$to%"), null)?.use {
                    if (it.moveToFirst()) number = it.getString(0)
                }
            } catch (_: Exception) {}
        }
        if (number.isNotBlank()) start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    }
}
