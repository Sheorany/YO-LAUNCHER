package com.minimal.launcher

import android.service.notification.NotificationListenerService

/**
 * Empty listener. Turning on notification access for it lets the launcher
 * read which song is playing (title, artist, progress) from YT Music.
 */
class MediaListener : NotificationListenerService()
