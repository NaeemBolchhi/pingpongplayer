package nb.couchpotato.phone;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;

public class ControlActivity extends AppCompatActivity {
    private TextView tvDeviceName;
    private Button btnPause, btnResume, btnSearchSubtitle;
    private EditText etSubtitleQuery;
    private ListView subtitleList;
    private SeekBar seekAudioSync, seekSubtitleSync, seekSubtitleSize;
    private Switch switchHdr;
    
    private MainActivity.DeviceInfo device;
    private DatagramSocket socket;
    private Handler handler = new Handler();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);
        
        device = getIntent().getParcelableExtra("device");
        
        tvDeviceName = findViewById(R.id.tv_device_name);
        btnPause = findViewById(R.id.btn_pause);
        btnResume = findViewById(R.id.btn_resume);
        btnSearchSubtitle = findViewById(R.id.btn_search_subtitle);
        etSubtitleQuery = findViewById(R.id.et_subtitle_query);
        subtitleList = findViewById(R.id.subtitle_list);
        seekAudioSync = findViewById(R.id.seek_audio_sync);
        seekSubtitleSync = findViewById(R.id.seek_subtitle_sync);
        seekSubtitleSize = findViewById(R.id.seek_subtitle_size);
        switchHdr = findViewById(R.id.switch_hdr);
        
        if (device != null) {
            tvDeviceName.setText("Connected to: " + device.serviceName);
        }
        
        try {
            socket = new DatagramSocket();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        btnPause.setOnClickListener(v -> sendCommand("PAUSE"));
        btnResume.setOnClickListener(v -> sendCommand("RESUME"));
        
        btnSearchSubtitle.setOnClickListener(v -> {
            String query = etSubtitleQuery.getText().toString();
            searchSubtitles(query);
        });
        
        // Setup settings listeners
        seekAudioSync.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    sendCommand("AUDIO_SYNC:" + (progress - 100));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        seekSubtitleSync.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    sendCommand("SUBTITLE_SYNC:" + (progress - 100));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        seekSubtitleSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    sendCommand("SUBTITLE_SIZE:" + progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        switchHdr.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendCommand("HDR:" + (isChecked ? "ON" : "OFF"));
        });
    }
    
    private void sendCommand(String command) {
        if (device == null || socket == null) return;
        new Thread(() -> {
            try {
                byte[] data = command.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, device.host, device.port);
                socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    private void searchSubtitles(String query) {
        // Simulate subtitle search - in real app would use API like OpenSubtitles
        ArrayList<String> subtitles = new ArrayList<>();
        subtitles.add("English (subdb.example.com)");
        subtitles.add("Spanish (opensubtitles.org)");
        subtitles.add("French (podnapisi.net)");
        
        ArrayAdapter<String> subAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, subtitles);
        subtitleList.setAdapter(subAdapter);
        
        subtitleList.setOnItemClickListener((parent, view, position, id) -> {
            String subtitle = subtitles.get(position);
            sendCommand("SUBTITLE_URL:" + "http://example.com/subtitle.srt");
            Toast.makeText(this, "Subtitle sent to TV: " + subtitle, Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null) {
            socket.close();
        }
    }
}
