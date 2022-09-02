package io.github.barteks2x.android.radiostream;

import android.app.Activity;
import android.content.ComponentName;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.widget.Button;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;

import io.github.barteks2x.android.radiostream.server.StreamService;


public class MainActivity extends Activity {
    public static final String NOTIFICATIONS_CHANNEL_ID = "io.github.barteks2x.radiostream.notifications";
    private MediaBrowser mediaBrowser;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        createNotificationChannel();
        initMediaBrowser();
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.notification_channel_name);
        int importance = NotificationManagerCompat.IMPORTANCE_LOW;
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder(NOTIFICATIONS_CHANNEL_ID, importance).setName(name).build();
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannel(channel);
    }

    private void initMediaBrowser() {
        Button play = findViewById(R.id.playButton);
        Button pause = findViewById(R.id.pauseButton);
        Button stop = findViewById(R.id.stopButton);

        mediaBrowser = new MediaBrowser(this,
                new ComponentName(this, StreamService.class),
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        // Get the token for the MediaSession
                        MediaSession.Token token = mediaBrowser.getSessionToken();

                        // Create a MediaControllerCompat
                        MediaController mediaController =
                                new MediaController(MainActivity.this, token);

                        // Save the controller
                        setMediaController(mediaController);

                        play.setOnClickListener(v -> mediaController.getTransportControls().play());
                        pause.setOnClickListener(v -> mediaController.getTransportControls().pause());
                        stop.setOnClickListener(v -> mediaController.getTransportControls().stop());

                    }
                },
                null); // optional Bundle
    }

    @Override
    public void onStart() {
        super.onStart();
        mediaBrowser.connect();
    }

    @Override
    public void onResume() {
        super.onResume();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override
    public void onStop() {
        super.onStop();
        // (see "stay in sync with the MediaSession")
        if (getMediaController() != null) {
            //getMediaController().unregisterCallback(controllerCallback);
        }
        mediaBrowser.disconnect();
    }



}