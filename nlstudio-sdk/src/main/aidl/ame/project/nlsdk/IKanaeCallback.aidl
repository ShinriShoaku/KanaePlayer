package ame.project.nlsdk;

interface IKanaeCallback {
    void onTrackChanged(String title, String artist, String duration, String thumbnail);
    void onLyricsChanged(String lyrics);
    void onQueueChanged(String queueJson);
    void onPlaybackStatusChanged(boolean isPlaying, long position, long duration);

    // TikTok Events
    void onChatMessage(String user, String message);
    void onGiftMessage(String user, String gift, int count);
    void onTikTokStatus(boolean connected, String username);

    // Additional Overlay Data
    void onUserJoined(String user, String profileUrl);
    void onUserLiked(String user, int count);
    void onUserFollowed(String user);
    void onUserShared(String user);
}
