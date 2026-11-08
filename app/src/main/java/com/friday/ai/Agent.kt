package com.friday.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// "நீயே பண்ணு" முறை: திரையை படிச்சு → AI-கிட்ட அடுத்த செயல் கேட்டு → tap/type பண்ணி
// இலக்கு முடியுற வரை (அதிகபட்சம் 18 படி) திரும்பத் திரும்ப செய்யும்.
object Agent {
    @Volatile var running = false
    private val main = Handler(Looper.getMainLooper())

    fun start(svc: FridayService, goal: String) {
        if (running) { svc.say("ஏற்கனவே ஒரு வேலை நடக்குது"); return }
        if (FridayAccessibility.instance == null) { svc.say("முதல்ல Accessibility ON பண்ணுங்க"); return }
        running = true
        thread {
            var result = "இன்னும் முடியலை. தொடரணுமா?"
            try { result = loop(svc, goal) } catch (e: Exception) { result = "ஏதோ பிரச்சனை வந்துடுச்சு" }
            running = false
            Memory(svc).addTurn("model", result)
            svc.say(result)
        }
    }

    private fun loop(svc: FridayService, goal: String): String {
        val p = Prefs(svc)
        val done = mutableListOf<String>()
        for (step in 1..18) {
            if (!running) return "நிறுத்திட்டேன்"
            Thread.sleep(1500)
            val acc = FridayAccessibility.instance ?: return "Accessibility அணைஞ்சுடுச்சு"
            val screen = acc.dumpScreen()
            val prompt = """
You control an Android phone to achieve the user's goal by reading the screen and choosing ONE next action.
GOAL: $goal
CURRENT APP: ${acc.currentPackage()}
ACTIONS SO FAR: ${done.takeLast(8).joinToString(" -> ").ifBlank { "none" }}
SCREEN (index, kind, text):
$screen
Reply with ONLY one JSON object, no other text:
{"do":"tap","i":<index>} | {"do":"type","i":<index>,"text":"..."} | {"do":"scroll","dir":"down|up"} | {"do":"back"} | {"do":"home"} | {"do":"open_app","app":"app name"} | {"do":"wait"}
{"do":"done","say":"short spoken result in Tamil/Tanglish"} when goal is achieved.
{"do":"ask","say":"short Tamil/Tanglish question"} if you need info from the user, or BEFORE anything involving payments, sending money, deleting data, or posting/sending a message that the user did not fully dictate. Never type passwords or OTPs.
""".trimIndent()
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            val raw = Gemini.call(p, body) ?: return "இன்டர்நெட் பிரச்சனை"
            val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) continue
            val j = try { JSONObject(raw.substring(a, b + 1)) } catch (e: Exception) { continue }
            when (j.optString("do")) {
                "tap" -> { acc.tapIndex(j.optInt("i", -1)); done.add("tap " + j.optInt("i")) }
                "type" -> { acc.typeIndex(j.optInt("i", -1), j.optString("text")); done.add("type " + j.optString("text")) }
                "scroll" -> { acc.scroll(j.optString("dir") != "up"); done.add("scroll " + j.optString("dir")) }
                "back" -> { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); done.add("back") }
                "home" -> { acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME); done.add("home") }
                "open_app" -> { main.post { Actions.run(svc, JSONObject().put("action", "open_app").put("app", j.optString("app"))) }; done.add("open " + j.optString("app")); Thread.sleep(1500) }
                "wait" -> { done.add("wait"); Thread.sleep(1500) }
                "done" -> return j.optString("say").ifBlank { "வேலை முடிஞ்சது" }
                "ask" -> return j.optString("say").ifBlank { "கொஞ்சம் உறுதிப்படுத்துங்க" }
            }
        }
        return "இன்னும் முடியலை, இதுவரை பண்ணினேன். தொடரணுமா?"
    }
}
