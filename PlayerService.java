package com.duroos.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

public class PlayerService extends Service {
    static final String CH = "play";
    static final String A_UPD = "upd", A_CMD = "cmd";

    MediaSession ms;
    PowerManager.WakeLock wl;
    WifiManager.WifiLock wf;
    String title = "", artist = "";
    boolean playing;
    long pos, dur;

    static void update(Context c, String t, String a, boolean p, long pos, long dur) {
        Intent i = new Intent(c, PlayerService.class).setAction(A_UPD)
                .putExtra("t", t).putExtra("a", a).putExtra("p", p)
                .putExtra("pos", pos).putExtra("dur", dur);
        try {
            c.startForegroundService(i);
        } catch (Exception e) { /* التطبيق في الخلفية ومفيش خدمة شغالة */ }
    }

    static void halt(Context c) {
        try { c.stopService(new Intent(c, PlayerService.class)); } catch (Exception e) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CH, "التشغيل", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        ms = new MediaSession(this, "duroos");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("play"); }
            @Override public void onPause() { cmd("pause"); }
            @Override public void onSkipToNext() { cmd("next"); }
            @Override public void onSkipToPrevious() { cmd("prev"); }
            @Override public void onSeekTo(long p) { cmd("seek:" + p); }
        });
        ms.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "duroos:play");
        wl.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "duroos:wifi");
        wf.setReferenceCounted(false);
    }

    static void cmd(String c) {
        MainActivity.js("window.nativeCmd&&nativeCmd('" + c + "')");
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null) { stopSelf(); return START_NOT_STICKY; }
        if (A_CMD.equals(i.getAction())) {
            cmd(i.getStringExtra("c"));
            return START_NOT_STICKY;
        }
        title = i.getStringExtra("t") == null ? "" : i.getStringExtra("t");
        artist = i.getStringExtra("a") == null ? "" : i.getStringExtra("a");
        playing = i.getBooleanExtra("p", false);
        pos = i.getLongExtra("pos", 0);
        dur = i.getLongExtra("dur", 0);
        render();
        return START_NOT_STICKY;
    }

    PendingIntent act(String c, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(A_CMD).putExtra("c", c);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void render() {
        ms.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build());
        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                .build());

        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "السابق", act("prev", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this,
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "إيقاف" : "تشغيل", act("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "التالي", act("next", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        if (playing) {
            if (!wl.isHeld()) wl.acquire(6 * 3600 * 1000L);
            if (!wf.isHeld()) wf.acquire();
        } else {
            if (wl.isHeld()) wl.release();
            if (wf.isHeld()) wf.release();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { if (wl.isHeld()) wl.release(); } catch (Exception e) { }
        try { if (wf.isHeld()) wf.release(); } catch (Exception e) { }
        try { ms.release(); } catch (Exception e) { }
        super.onDestroy();
    }
}
