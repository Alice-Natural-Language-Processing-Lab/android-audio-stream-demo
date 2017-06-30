package com.nabto.androidaudiodemo;

import com.nabto.api.*;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.app.Activity;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {
    // UI
    private EditText editText;
    private Button openStreamButton;
    private Button closeStreamButton;

    // Nabto API
    private NabtoApi nabtoApi;
    private Session session;
    private Stream stream;

    // Audio
    private AudioRecord record;
    private AudioTrack track;

    private final static int BITRATE = 16000;
    private final static float RECORD_BUFFER_TIME = 4;  // seconds
    private final static float PLAY_BUFFER_TIME = 4;  // seconds

    private ADPCM adpcm;
    private boolean streaming = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);

        // Initialize Nabto and open session with guest account
        nabtoApi = new NabtoApi(new NabtoAndroidAssetManager(this));
        Log.v(this.getClass().toString(), "Nabto API version: " + nabtoApi.version());
        nabtoApi.startup();
        session = nabtoApi.openSession("guest", "");

        // Init UI
        editText = (EditText) findViewById(R.id.editTextDeviceId);

        openStreamButton = (Button) findViewById(R.id.buttonOpenStream);
        openStreamButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String deviceId = editText.getText().toString();
                startStreaming(deviceId);
            }
        });

        closeStreamButton = (Button) findViewById(R.id.buttonCloseStream);
        closeStreamButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                stopStreaming();
            }
        });

        adpcm = new ADPCM();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 1: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                    Toast.makeText(MainActivity.this, "Permission denied to record audio", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
    }

    /**
     * Start streaming.
     * @param deviceId Device ID to connect to.
     */
    private void startStreaming(final String deviceId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        editText.setEnabled(false);
                        openStreamButton.setEnabled(false);
                    }
                });

                if (!openStream(deviceId)) {
                    stopStreaming();
                    return;
                }

                if (!sendCommand("audio\n")) {
                    stopStreaming();
                    return;
                }

                streaming = true;

                startRecording();
                startPlaying();

                runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        closeStreamButton.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /**
     * Open stream.
     * @param deviceId Device ID to connect to.
     * @return Returns TRUE if stream was successfully opened.
     */
    private boolean openStream(final String deviceId) {
        // Open stream
        Log.v(this.getClass().toString(), "Opening stream to " + deviceId + "...");
        stream = nabtoApi.streamOpen(deviceId, session);
        if (stream == null) {
            Log.v(this.getClass().toString(), "Failed to open stream!");
            return false;
        }
        NabtoStatus status = stream.getStatus();
        Log.v(this.getClass().toString(), "Stream open status: " + status);
        if (status != NabtoStatus.OK) {
            Log.v(this.getClass().toString(), "Failed to open stream!");
            return false;
        }
        return true;
    }

    /**
     * Send command to the device.
     * @param command Command to send
     * @return Returns TRUE if the command was accepted by the device.
     */
    private boolean sendCommand(final String command) {
        // Send command
        Log.v(this.getClass().toString(), "Send command '" + command + "'...");
        NabtoStatus status = nabtoApi.streamWrite(stream, command.getBytes());
        Log.v(this.getClass().toString(), "Command write status: " + status);
        if (status != NabtoStatus.OK) {
            Log.v(this.getClass().toString(), "Failed to send command!");
            return false;
        }
        // Verify command result
        final StreamReadResult result = nabtoApi.streamRead(stream);
        status = result.getStatus();
        Log.v(this.getClass().toString(), "Command result read status: " + status);
        if (status != NabtoStatus.OK) {
            Log.v(this.getClass().toString(), "Failed to read command result!");
            return false;
        }
        final String received = new String(result.getData());
        if (!received.trim().equals("+")) {
            Log.v(this.getClass().toString(), "Failed to invoke command. Result was: " + received);
            return false;
        }
        return true;
    }

    /**
     * Stop streaming.
     */
    private void stopStreaming() {
        new Thread(new Runnable(){
            @Override
            public void run() {
                runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        closeStreamButton.setEnabled(false);
                    }
                });
                streaming = false;
                closeStream();

                runOnUiThread(new Runnable(){
                    @Override
                    public void run() {
                        editText.setEnabled(true);
                        openStreamButton.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /**
     * Close stream.
     */
    private void closeStream() {
        if (stream != null && stream.getStatus() != NabtoStatus.STREAM_CLOSED) {
            NabtoStatus status = nabtoApi.streamClose(stream);
            Log.v(this.getClass().toString(), "Stream close status: " + status);
        }
    }

    /**
     * Start recording & sending thread.
     */
    private void startRecording() {
        adpcm.resetEncoder();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.v(this.getClass().toString(), "Start recording... ");

                int minBufferSize = AudioRecord.getMinBufferSize(
                        BITRATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);

                int bufferSizeInBytes = (int)(RECORD_BUFFER_TIME * 2 * 2 * BITRATE);
                if (bufferSizeInBytes < minBufferSize)
                    bufferSizeInBytes = minBufferSize;

                record = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        BITRATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeInBytes);

                record.startRecording();

                int pos = 0;
                short[] recordChunk = new short[2048]; // -> 1024kB send chunk after encoding
                //double startTime = System.currentTimeMillis();

                while (streaming) {
                    pos += record.read(recordChunk, pos, recordChunk.length - pos);
                    assert (pos <= recordChunk.length );

                    if(pos == recordChunk.length) {
                        pos = 0;

                        final byte[] bytes = adpcm.encode(recordChunk);
                        NabtoStatus status = nabtoApi.streamWrite(stream, bytes);

                        /*double endTime = System.currentTimeMillis();
                        int kilobytes = bytes.length / 1024;
                        double seconds = (endTime-startTime) / 1000.0;
                        double bandwidth = (kilobytes / seconds);  //kilobytes-per-second (kBs)
                        startTime = endTime;
                        Log.v(this.getClass().toString(), "sending = " + bandwidth + " kBs");*/

                        if (status == NabtoStatus.INVALID_STREAM || status == NabtoStatus.STREAM_CLOSED) {
                            stopStreaming();
                            break;
                        } else if (status != NabtoStatus.OK && status != NabtoStatus.BUFFER_FULL) {
                            Log.v(this.getClass().toString(), "Write error: " + status);
                            stopStreaming();
                            break;
                        }
                    }
                }

                record.stop();
                record.release();
                record = null;

                Log.v(this.getClass().toString(), "Stopped recording... ");
            }
        }).start();
    }

    /**
     * Start receiving and playing thread.
     */
    private void startPlaying() {
        adpcm.resetEncoder();

        new Thread(new Runnable() {
            @Override
            public void run() {
                int minBufferSize = AudioTrack.getMinBufferSize(
                        BITRATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);

                int bufferSizeInBytes = (int)(PLAY_BUFFER_TIME * 2 * 2 * BITRATE);
                if (bufferSizeInBytes < minBufferSize)
                    bufferSizeInBytes = minBufferSize;

                track = new AudioTrack(AudioManager.STREAM_MUSIC,
                        BITRATE,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeInBytes,
                        AudioTrack.MODE_STREAM);

                track.play();

                int bufferWriteFrame = 0;
                //double startTime = System.currentTimeMillis();

                while (streaming) {
                    StreamReadResult result = nabtoApi.streamRead(stream);
                    NabtoStatus status = result.getStatus();
                    if (status == NabtoStatus.INVALID_STREAM || status == NabtoStatus.STREAM_CLOSED) {
                        stopStreaming();
                        break;
                    } else if (status != NabtoStatus.OK) {
                        Log.v(this.getClass().toString(), "Read error: " + status);
                        stopStreaming();
                        break;
                    }

                    /*double endTime = System.currentTimeMillis();
                    int kilobytes = result.getData().length / 1024;
                    double seconds = (endTime-startTime) / 1000.0;
                    double bandwidth = (kilobytes / seconds);  //kilobytes-per-second (kBs)
                    startTime = endTime;
                    Log.v(this.getClass().toString(), "receiving = " + bandwidth + " kBs");*/

                    final short[] playChunk = adpcm.decode(result.getData());
                    track.write(playChunk, 0, playChunk.length);

                    bufferWriteFrame += playChunk.length / 2;
                    int bufferPlaybackFrame = track.getPlaybackHeadPosition();
                    double bufferFilled = (bufferWriteFrame - bufferPlaybackFrame) / (bufferSizeInBytes/4.0);
                    Log.v(this.getClass().toString(), "Play buffer filled: " + bufferFilled + "%");
                }

                track.stop();
                track.release();
                track = null;
            }
        }).start();
    }
}
