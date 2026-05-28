package ame.project.kanae.model

data class TikTokChat(
    val uniqueId: String,       // TikTok username
    val nickname: String,
    val comment: String,
    val timestamp: Long = System.currentTimeMillis(),
    // parsed command fields
    val commandType: CommandType = CommandType.NONE,
    val commandArg: String? = null
) {
    enum class CommandType { NONE, REQUEST, SKIP, STOP, QUEUE }
}
