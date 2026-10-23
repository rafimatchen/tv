package com.rafi.glasshome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets Glass Home see which app is playing music
 * (MediaSessionManager requires an enabled notification listener). It ignores notifications.
 * install.sh enables it with "cmd notification allow_listener".
 */
public class NowPlayingListener extends NotificationListenerService {
}
