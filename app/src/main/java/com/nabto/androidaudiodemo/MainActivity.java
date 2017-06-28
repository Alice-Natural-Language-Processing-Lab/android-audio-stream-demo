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
    private AudioRecord record = null;
    private AudioTrack track = null;

    private static final int CHUNK_SIZE = 1024;

    private boolean streaming = false;

    private ADPCM encoder;
    private ADPCM decoder;

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

        encoder = new ADPCM();
        decoder = new ADPCM();
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


    private void closeStream() {
        if (stream != null && stream.getStatus() != NabtoStatus.STREAM_CLOSED) {
            NabtoStatus status = nabtoApi.streamClose(stream);
            Log.v(this.getClass().toString(), "Stream close status: " + status);
        }
    }

    private void startRecording() {
        encoder.reset();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.v(this.getClass().toString(), "Start recording... ");

                int bitrate = 16000;
                int minBufferSize = AudioRecord.getMinBufferSize(
                        bitrate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);

                float bufferTime = 4; // seconds
                int bufferSizeInBytes = (int)(bufferTime * 2 * 2 * 16000);
                if (bufferSizeInBytes < minBufferSize) bufferSizeInBytes = minBufferSize;

                record = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        bitrate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeInBytes);

                record.startRecording();


                int pos = 0;

                double startTime = System.currentTimeMillis();

                while (streaming) {
                    final short[] recordChunk = new short[2048]; // -> 1024kB send chunk after encoding
                    pos += record.read(recordChunk, pos, recordChunk.length - pos);

                    assert (pos <= recordChunk.length );
                    if(pos == recordChunk.length) {
                        pos = 0;

                        final byte[] bytes = encoder.encode(recordChunk);
                        NabtoStatus status = nabtoApi.streamWrite(stream, bytes);

                        double endTime = System.currentTimeMillis();
                        int kilobytes = bytes.length / 1024; //Kilobytes
                        double seconds = (endTime-startTime) / 1000.0;
                        double bandwidth = (kilobytes / seconds);  //kilobytes-per-second (kBs)
                        startTime = endTime;

                        //Log.v(this.getClass().toString(), "sending = " + bandwidth + " kBs");



                        if (status == NabtoStatus.INVALID_STREAM || status == NabtoStatus.STREAM_CLOSED) {
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


    private void startPlaying() {
        decoder.reset();

        new Thread(new Runnable() {
            @Override
            public void run() {
                int bitrate = 16000;
                int minBufferSize = AudioTrack.getMinBufferSize(
                        bitrate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);

                float bufferTime = 4; // seconds
                int bufferSizeInBytes = (int)(bufferTime * 2 * 2 * 16000);
                if (bufferSizeInBytes < minBufferSize) bufferSizeInBytes = minBufferSize;
                track = new AudioTrack(AudioManager.STREAM_MUSIC,
                        bitrate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSizeInBytes,
                        AudioTrack.MODE_STREAM);

                Log.v(this.getClass().toString(), "buffer size " + bufferSizeInBytes);

                //track.setPlaybackRate(15000);
                track.play();
                double startTime = System.currentTimeMillis();

                int bufferWriteFrame = 0;

                double bufferFilledAvg  = 0.5;

                while (streaming) {

                    StreamReadResult result = nabtoApi.streamRead(stream);
                    NabtoStatus status = result.getStatus();
                    if (status == NabtoStatus.INVALID_STREAM || status == NabtoStatus.STREAM_CLOSED) {
                        break;
                    } else if (status != NabtoStatus.OK) {
                        Log.v(this.getClass().toString(), "Read error: " + status);
                        stopStreaming();
                        break;
                    }

                    final short[] tmp = decoder.decode(result.getData());
                    track.write(tmp, 0, tmp.length);

                    double endTime = System.currentTimeMillis();
                    int kilobytes = result.getData().length / 1024; //Kilobytes
                    double seconds = (endTime-startTime) / 1000.0;
                    double bandwidth = (kilobytes / seconds);  //kilobytes-per-second (kBs)
                    startTime = endTime;

                    bufferWriteFrame += tmp.length / 2;
                    int bufferPlaybackFrame = track.getPlaybackHeadPosition();

                    double bufferFilled = (bufferWriteFrame - bufferPlaybackFrame) / (bufferSizeInBytes/4.0);

                    if((bufferWriteFrame - bufferPlaybackFrame) < 5000) {
                        //track.setPlaybackRate(15000);
                        //track.play();
                    } else if((bufferWriteFrame - bufferPlaybackFrame) > 50000) {
                        //track.setPlaybackRate(8000);
                        //track.play();
                    }

                    bufferFilledAvg = bufferFilledAvg * 0.9 + 0.1 * bufferFilled;

                    //track.setPlaybackRate((int)(15000 + 1000 * bufferFilledAvg));
                    //Log.v(this.getClass().toString(), "receiving = " + bufferFilledAvg  + " kBs" + (15000 + 1000 * bufferFilledAvg));
                    Log.v(this.getClass().toString(), ""+bufferFilled);

                }

                track.stop();
                track.release();
                track = null;
            }
        }).start();
    }
}

