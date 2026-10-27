package nb.couchpotato.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

public class MainActivity extends FragmentActivity {
    private VLCVideoLayout videoLayout;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private LinearLayout controlsOverlay;
    private SeekBar seekBar;
    private Button btnPlayPause, btnSubtitle;
    private Handler handler = new Handler();
    private boolean controlsVisible = false;
    
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private String serviceName = "StreamCastTV_" + android.os.Build.MODEL;
    private static final String SERVICE_TYPE = "_streamcast._tcp.";
    
    private DatagramSocket udpSocket;
    private int controlPort = 8888;
    private int discoveryPort = 8889;
    
    private MediaReceiver receiver;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        videoLayout = findViewById(R.id.video_layout);
        controlsOverlay = findViewById(R.id.controls_overlay);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSubtitle = findViewById(R.id.btn_subtitle);
        
        ArrayList<String> args = new ArrayList<>();
        args.add("-vvv");
        libVLC = new LibVLC(this, args);
        mediaPlayer = new MediaPlayer(libVLC);
        videoLayout.setMediaPlayer(mediaPlayer);
        
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnSubtitle.setOnClickListener(v -> toggleSubtitles());
        
        setupControls();
        registerService();
        startUdpServer();
        
        receiver = new MediaReceiver();
        IntentFilter filter = new IntentFilter("nb.couchpotato.PLAY_MEDIA");
        registerReceiver(receiver, filter);
    }
    
    private void setupControls() {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.setPosition(progress / 100f);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        handler.postDelayed(updateSeekBarRunnable, 1000);
    }
    
    private Runnable updateSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                seekBar.setProgress((int) (mediaPlayer.getPosition() * 100));
            }
            handler.postDelayed(this, 1000);
        }
    };
    
    private void togglePlayPause() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.play();
            }
        }
    }
    
    private void toggleSubtitles() {
        if (mediaPlayer != null) {
            int spuCount = mediaPlayer.getSpuCount();
            if (spuCount > 0) {
                int currentSpu = mediaPlayer.getSpu();
                if (currentSpu == -1) {
                    mediaPlayer.setSpu(mediaPlayer.getSpuTracks()[0].id);
                } else {
                    mediaPlayer.setSpu(-1);
                }
            }
        }
    }
    
    private void showControls() {
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, 5000);
    }
    
    private void hideControls() {
        controlsOverlay.setVisibility(View.GONE);
        controlsVisible = false;
    }
    
    private Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            hideControls();
        }
    };
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showControls();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mediaPlayer != null) {
                    mediaPlayer.setPosition(Math.max(0, mediaPlayer.getPosition() - 0.05f));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mediaPlayer != null) {
                    mediaPlayer.setPosition(Math.min(1, mediaPlayer.getPosition() + 0.05f));
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    private void registerService() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(controlPort);
        
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                serviceName = NsdServiceInfo.getServiceName();
            }
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }
    
    private void startUdpServer() {
        new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(controlPort);
                byte[] buffer = new byte[4096];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    handleControlMessage(message);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    private void handleControlMessage(String message) {
        // Parse control messages from phone
        runOnUiThread(() -> {
            if (message.startsWith("PLAY:")) {
                String url = message.substring(5);
                playMedia(url);
            } else if (message.equals("PAUSE")) {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } else if (message.equals("RESUME")) {
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    mediaPlayer.play();
                }
            }
        });
    }
    
    private void playMedia(String url) {
        if (mediaPlayer != null) {
            Media media = new Media(libVLC, url);
            mediaPlayer.setMedia(media);
            media.play();
            hideControls();
        }
    }
    
    public class MediaReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String url = intent.getStringExtra("url");
            if (url != null) {
                playMedia(url);
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nsdManager != null && registrationListener != null) {
            nsdManager.unregisterService(registrationListener);
        }
        if (udpSocket != null) {
            udpSocket.close();
        }
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        if (libVLC != null) {
            libVLC.release();
        }
    }
}
