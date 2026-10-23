package com.rafi.glasshome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a music app (Spotify by default) is playing, from its media session. Event driven:
 * it listens for session, metadata and playback changes and never polls.
 */
final class NowPlaying {
    static final class Info {
        final String pkg, title, artist;
        final Bitmap art;          // small copy of the cover, or null
        final boolean playing;
        final long duration;       // ms, 0 if unknown
        final long position;       // ms at positionAt
        final long positionAt;     // SystemClock.elapsedRealtime() of that position
        final float speed;

        Info(String pkg, String title, String artist, Bitmap art, boolean playing,
             long duration, long position, long positionAt, float speed) {
            this.pkg = pkg;
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.playing = playing;
            this.duration = duration;
            this.position = position;
            this.positionAt = positionAt;
            this.speed = speed;
        }

        /** Current position, moved on from the last report if it's playing. */
        long positionNow() {
            if (!playing) return position;
            long p = position + (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
            return duration > 0 ? Math.min(p, duration) : p;
        }
    }

    interface Listener {
        void onNowPlaying(Info info); // null when nothing worth showing
    }

    private final Context context;
    private final Handler handler;
    private final List<String> apps;
    private final ComponentName listenerComponent;
    private MediaSessionManager sessions;
    private Listener listener;
    private final List<MediaController> watched = new ArrayList<>();
    private String artKey;
    private Bitmap artSmall;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> rewatch(list);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            publish();
        }
    };

    NowPlaying(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
        this.apps = Arrays.asList(context.getResources().getStringArray(R.array.now_playing_apps));
        this.listenerComponent = new ComponentName(context, NowPlayingListener.class);
    }

    void start(Listener l) {
        listener = l;
        sessions = context.getSystemService(MediaSessionManager.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler);
            rewatch(sessions.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            // Listener not enabled (see install.sh): just never show anything.
            l.onNowPlaying(null);
        }
    }

    void stop() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
                // never registered
            }
        }
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        listener = null;
    }

    private void rewatch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (apps.contains(c.getPackageName())) {
                    c.registerCallback(callback, handler);
                    watched.add(c);
                }
            }
        }
        publish();
    }

    private void publish() {
        if (listener == null) return;
        MediaController best = null;
        for (MediaController c : watched) {
            PlaybackState st = c.getPlaybackState();
            if (c.getMetadata() == null || st == null) continue;
            int s = st.getState();
            boolean playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
            boolean paused = s == PlaybackState.STATE_PAUSED;
            if (playing) { best = c; break; }
            if (paused && best == null) best = c;
        }
        if (best == null) {
            listener.onNowPlaying(null);
            return;
        }
        MediaMetadata m = best.getMetadata();
        PlaybackState st = best.getPlaybackState();
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (title == null) {
            listener.onNowPlaying(null);
            return;
        }
        listener.onNowPlaying(new Info(best.getPackageName(), title, artist == null ? "" : artist,
                smallArt(m, title + "|" + artist),
                st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING,
                m.getLong(MediaMetadata.METADATA_KEY_DURATION), st.getPosition(),
                st.getLastPositionUpdateTime(), st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed()));
    }

    /** The cover scaled down to 160 px once per track, so a big bitmap isn't kept around. */
    private Bitmap smallArt(MediaMetadata m, String key) {
        if (key.equals(artKey)) return artSmall;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        artKey = key;
        artSmall = b == null ? null : Bitmap.createScaledBitmap(b, 160, 160 * b.getHeight() / Math.max(1, b.getWidth()), true);
        return artSmall;
    }
}
