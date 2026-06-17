# Change Log & New Features

## [8.1.0] - Latest Updates

### 💬 Chat Overlay & Performance
- **Bug Fix: Max Chat Lines**: Fixed a critical issue where the chat limit didn't work. Now strictly enforces the maximum number of visible messages to maintain performance and prevent memory leaks.
- **Smart Dummy Text**: Improved "Chat Overlay Active" behavior. It now automatically hides after 4 seconds when activated normally, but remains persistent while in the Settings menu for easier positioning.
- **Clean Chat Mode**: Removed **Like, Gift, and Share** events from the Chat Overlay. These events are now handled exclusively by the Notification Overlay to reduce clutter.
- **Improved Animations**: Added smoother fade-in and fade-out animations for chat bubbles.

### 🔔 Customizable Notification Overlay (New Feature!)
- **Total Customization**: You can now set **custom images and sounds** for TikTok Share and Gift events.
- **Persistent Settings Preview**: While adjusting notification settings, the overlay stays visible so you can see your changes in real-time.
- **Interaction Awareness**: Moving or touching the notification overlay now automatically resets its auto-hide timer, preventing it from disappearing while you're positioning it.
- **Audio Feedback**: Added support for custom notification sounds (WAV/MP3) when a user shares the live or sends a gift.

### 🛠️ Core Improvements
- **Optimized Memory Management**: View instances are now properly recycled and pruned from memory when they expire.
- **Enhanced Gesture Helper**: Improved interaction logic to better handle simultaneous dragging and auto-hide behaviors across all overlays.
- **UI Synchronization**: Better consistency between the BottomSheet settings panel and the actual overlay states.

---

## [7.5.0] - Previous Updates

### 💬 Chat Overlay Enhancements
- **Dynamic Text Scaling**: Font size control instead of layout scaling for better clarity.
- **Auto-Height Management**: Dynamic container resizing.
- **Natural Chat Flow**: Newest messages appear at the bottom.
- **Authorizer User (Admin List)**: Added management for authorized users to execute commands.
- **General Fixes**: Improved gesture stability and settings sync.
