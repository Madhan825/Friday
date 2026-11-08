package com.friday.ai

// UI-க்கும் service-க்கும் நடுவுல பகிரப்படும் நிலை
object Status {
    @Volatile var state = "off"        // off / listening / hearing / thinking / speaking
    @Volatile var level = 0f           // mic அளவு 0..1
    @Volatile var heard = ""
    @Volatile var reply = ""
}
