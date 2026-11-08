package com.friday.ai

// திரையும் service-ம் பகிர்ந்துக்கும் நிலை (orb அனிமேஷன், text)
object UiState {
    @Volatile var state = "idle"        // idle, listening, thinking, speaking
    @Volatile var level = 0f            // 0..1
    @Volatile var mode = "—"            // LIVE / CLASSIC
    @Volatile var running = false
    @Volatile var userLine = ""
    @Volatile var replyLine = ""
}
