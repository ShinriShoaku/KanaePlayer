package ame.project.kanae.model

data class TikTokChat(
    val uniqueId: String,       // TikTok username
    val nickname: String,
    val comment: String,
    val timestamp: Long = System.currentTimeMillis(),
    // parsed command fields
    val commandType: CommandType = CommandType.NONE,
    val commandArg: String? = null,
    val emotes: List<TikTokEmote> = emptyList()
) {
    enum class CommandType {
        NONE,
        REQUEST,
        SKIP,
        STOP,
        QUEUE,
        /** #cm <position>  →  commandArg = "1", "2", etc. (1-indexed) */
        CLEAR_MUSIC
    }
}

data class TikTokEmote(
    val placeInComment: Int,
    val imageUrl: String
)
