package io.github.barteks2x.android.radiostream.server;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserCompat.ConnectionCallback;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.barteks2x.android.radiostream.MainActivity;
import io.github.barteks2x.android.radiostream.R;
import io.github.barteks2x.android.radiostream.StreamPlayer;

public class StreamService extends MediaBrowserServiceCompat {
    private static final int PLAYER_NOTIFICATION_ID = 1;
    private static final String MY_MEDIA_ROOT_ID = "media_root_id";
    private static final String MY_EMPTY_MEDIA_ROOT_ID = "empty_root_id";

    private static final String TAG = StreamService.class.getSimpleName();
    private StreamPlayer streamPlayer;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "RadioStream::WakelockTag");

        WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        WifiManager.WifiLock wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioStream::WifiLockTag");

        streamPlayer = new StreamPlayer(wakeLock, wifiLock, "https://r.dcs.redcdn.pl/sc/o2/Eurozet/live/audio.livx");
        // streamPlayer = new StreamPlayer(wakeLock, wifiLock, "https://rs101-krk.rmfstream.pl/RMFFM48");
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS);
        stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE);
        mediaSession.setPlaybackState(stateBuilder.build());

        mediaSession.setCallback(new PlayerCallback(
                this, this, mediaSession, streamPlayer
        ));
        mediaSession.setMetadata(
                new MediaMetadataCompat.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "RadioStream")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "RadioStream")
                        .build()
        );
        setSessionToken(mediaSession.getSessionToken());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.getController().getTransportControls().stop();
        System.out.println("DESTROY -> ");
        new Exception().printStackTrace();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid,
                                 Bundle rootHints) {
        // TODO: is this correct?
        System.out.println("OnGetRoot");
        if (allowBrowsing(clientPackageName, clientUid)) {
            return new BrowserRoot(MY_MEDIA_ROOT_ID, null);
        } else {
            return new BrowserRoot(MY_EMPTY_MEDIA_ROOT_ID, null);
        }
    }

    private boolean allowBrowsing(String clientPackageName, int clientUid) {
        return true;
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        // TODO: do I need this? Is this correct?
        System.out.println("OnLoadChildren");
        //  Browsing not allowed
        if (TextUtils.equals(MY_EMPTY_MEDIA_ROOT_ID, parentMediaId)) {
            result.sendResult(null);
            return;
        }

        // Assume for example that the music catalog is already loaded/cached.
        List<MediaItem> mediaItems = new ArrayList<>();

        // Check if this is the root menu:
        if (MY_MEDIA_ROOT_ID.equals(parentMediaId)) {
            mediaItems.add(new MediaItem(new MediaDescriptionCompat.Builder()
                    .setDescription("Radio Stream").setTitle("Radio Stream").build(), MediaItem.FLAG_PLAYABLE));
        } else {
            // TODO
        }
        result.sendResult(mediaItems);
    }

    private static class PlayerCallback extends MediaSessionCompat.Callback {

        private final IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        private final BroadcastReceiver myNoisyAudioStreamReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    onPause();
                }
            }
        };

        private final MediaBrowserServiceCompat service;
        private final Context context;
        private final AudioManager.OnAudioFocusChangeListener afChangeListener = focusChange -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                onPause();
            }
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                onPlay();
            }
        };
        private final MediaSessionCompat mediaSession;
        private final StreamPlayer player;

        private AudioFocusRequest audioFocusRequest;
        private boolean started;

        public PlayerCallback(MediaBrowserServiceCompat service, Context context, MediaSessionCompat mediaSession, StreamPlayer player) {
            this.service = service;
            this.context = context;
            this.mediaSession = mediaSession;
            this.player = player;

        }

        @Override
        public void onPlay() {
            if (started) {
                return;
            }
            System.out.println("!!!!!! ONPLAY");
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(afChangeListener)
                    .setAudioAttributes(attrs)
                    .build();
            int result = am.requestAudioFocus(audioFocusRequest);

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                context.startForegroundService(new Intent(context, StreamService.class));
                mediaSession.setActive(true);
                player.play();
                // Register BECOME_NOISY BroadcastReceiver
                service.registerReceiver(myNoisyAudioStreamReceiver, intentFilter);

                MediaControllerCompat controller = mediaSession.getController();


                int[] bitmapData = new int[256 * 256];
                Arrays.fill(bitmapData, 0xFFDD1111);
                Bitmap bitmap = Bitmap.createBitmap(bitmapData, 256, 256, Bitmap.Config.ARGB_8888);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(service.getApplicationContext(), MainActivity.NOTIFICATIONS_CHANNEL_ID);
                builder
                        .setSmallIcon(R.drawable.player_icon)
                        .setLargeIcon(bitmap)
                        .setColor(0xFFDD1111)
                        .setContentTitle("Radio Stream")
                        .setContentText("Radio Stream")
                        .setSubText("Radio stream")
                        .setContentIntent(controller.getSessionActivity())
                        .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                PlaybackStateCompat.ACTION_STOP))
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .addAction(new NotificationCompat.Action.Builder(
                                R.drawable.stop_icon, "Stop",
                                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                        PlaybackStateCompat.ACTION_STOP)).build())
                        .addAction(new NotificationCompat.Action.Builder(
                                R.drawable.play_icon, "Play",
                                MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                        PlaybackStateCompat.ACTION_PLAY)).build())
                        .setStyle(new MediaStyle()
                                .setMediaSession(mediaSession.getSessionToken())
                                .setShowActionsInCompactView(0, 1)
                                .setShowCancelButton(true)
                                .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context,
                                        PlaybackStateCompat.ACTION_STOP))
                        );

                Notification notif = builder.build();
                service.startForeground(PLAYER_NOTIFICATION_ID, notif);
                System.out.println("STARTED SERVICE");
                started = true;
            }
        }

        @Override
        public void onStop() {
            if (!started) {
                return;
            }
            System.out.println("!!!!!! STOPPING ->");
            new Exception().printStackTrace();
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocusRequest(audioFocusRequest);
            service.unregisterReceiver(myNoisyAudioStreamReceiver);
            service.stopSelf();
            mediaSession.setActive(false);
            player.stop();
            service.stopForeground(true);
            started = false;
        }

        @Override
        public void onPause() {
            onStop();
        }
    }
}
