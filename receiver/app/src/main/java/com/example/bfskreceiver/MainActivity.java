package com.example.bfskreceiver;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private PreviewView previewView;
    private TextView measurementText;
    private ExecutorService cameraExecutor;
    private long previousTimestamp = 0;
    private long lastUiUpdate = 0;
    private Button startButton;
    private Button stopButton;
    // stores timestamp and brightness for every frame
    private final List<double[]> recordedSamples = new ArrayList<>();
    private final Object recordingLock = new Object();
    private boolean isRecording = false;
    private long recordingStartTimestamp = -1;
    // stores recent camera brightness measurements
    private final ArrayDeque<double[]> recentSamples = new ArrayDeque<>();

    // length of analysis window: 1 second
    private static final long WINDOW_NS = 1_000_000_000L;
    // update frequency estimate every 250 ms
    private static final long DETECTION_INTERVAL_NS = 250_000_000L;
    private long lastDetectionTimestamp = 0;
    // latest result to display
    private String liveFrequencyText = "Waiting for signal...";
    // decoder states
    private static final int CALIBRATING = 0; // measure unchanged screen brightness
    private static final int WAITING = 1; // watch for the beginning of transmission
    private static final int RECEIVING = 2; // collect 8 seconds of brightness samples
    private static final int FINISHED = 3; // display decoded message
    private int decoderState = FINISHED;
    // transmitter settings
    private static final int MESSAGE_LENGTH = 8;
    private static final double SYMBOL_SECONDS = 1.0;
    private static final double START_THRESHOLD = 5.0;
    private final List<double[]> baselineSamples = new ArrayList<>();
    private final List<double[]> messageSamples = new ArrayList<>();
    private long calibrationStartNs = -1;
    private long transmissionStartNs = -1;
    private double baselineBrightness = 0;
    private volatile String decoderStatus = "Decoder not armed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        measurementText = findViewById(R.id.measurementText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());

        Button armDecoderButton = findViewById(R.id.armDecoderButton);
        armDecoderButton.setOnClickListener(v -> {
            // keep decoder state changes on the camera thread
            cameraExecutor.execute(() -> {
                baselineSamples.clear();
                messageSamples.clear();

                calibrationStartNs = -1;
                transmissionStartNs = -1;

                decoderState = CALIBRATING;
                decoderStatus = "Calibrating brightness...";
            });
        });

        cameraExecutor = Executors.newSingleThreadExecutor();

        // check camera permission
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE
            );
        }
    }
    private void startRecording() {
        synchronized (recordingLock) {
            recordedSamples.clear();
            recordingStartTimestamp = -1;
            isRecording = true;
        }

        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
    }
    private void stopRecording() {
        List<double[]> samplesToSave;

        synchronized (recordingLock) {
            isRecording = false;
            samplesToSave = new ArrayList<>(recordedSamples);
        }

        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        // use background thread for file writing
        cameraExecutor.execute(() -> saveSamples(samplesToSave));
    }

    private void saveSamples(List<double[]> samples) {
        File outputFile = new File(getExternalFilesDir(null),"brightness_data.csv"
        );

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("time_seconds,brightness\n");

            for (double[] sample : samples) {
                writer.write(String.format(Locale.US,"%.9f,%.4f\n", sample[0], sample[1]));
            }

            runOnUiThread(() -> {
                startButton.setEnabled(true);

                Toast.makeText(
                        this,
                        "Saved " + samples.size() +
                                " samples to brightness_data.csv",
                        Toast.LENGTH_LONG
                ).show();
            });

        } catch (IOException e) {
            runOnUiThread(() -> {
                startButton.setEnabled(true);
                Toast.makeText(this,"Error saving CSV: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    @SuppressLint("SetTextI18n")
    private void startCamera() {

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // camera preview
                Preview preview = new Preview.Builder().build();

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // camera frame analysis
                ImageAnalysis imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                // use rear camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> measurementText.setText("Camera error: " + e.getMessage()));
            }

        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {

        try {
            // average brightness of central image region
            double brightness = calculateBrightness(image);

            // camera timestamp in nanoseconds
            long timestamp = image.getImageInfo().getTimestamp();

            detectLiveFrequency(timestamp, brightness);
            processDecoderFrame(timestamp, brightness);

            // store the time and brightness of each frame
            synchronized (recordingLock) {
                if (isRecording) {
                    if (recordingStartTimestamp < 0) {
                        recordingStartTimestamp = timestamp;
                    }

                    double elapsedSeconds = (timestamp - recordingStartTimestamp) / 1e9;
                    recordedSamples.add(new double[]{elapsedSeconds, brightness});
                }
            }


            // estimate the frame rate
            double fps = 0;

            if (previousTimestamp > 0) {
                double deltaSeconds = (timestamp - previousTimestamp) / 1e9;

                if (deltaSeconds > 0) {
                    fps = 1.0 / deltaSeconds;
                }
            }

            previousTimestamp = timestamp;

            // update displayed measurements at most four times per second
            if (timestamp - lastUiUpdate >= 250_000_000L) {
                lastUiUpdate = timestamp;

                final double currentBrightness = brightness;
                final double currentFps = fps;

                runOnUiThread(() -> {
                    String text = String.format(
                            java.util.Locale.US,
                            "Brightness: %.2f\nFPS: %.2f\n\n%s\n\n%s",
                            currentBrightness,
                            currentFps,
                            liveFrequencyText,
                            decoderStatus
                    );

                    measurementText.setText(text);
                });
            }

        } finally {
            // always release camera frame
            image.close();
        }
    }

    private double calculateBrightness(ImageProxy image) {

        // Y plane contains luminance (brightness)
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];

        ByteBuffer buffer = yPlane.getBuffer();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        // analyze central 50% of the frame
        int startX = width / 4;
        int endX = 3 * width / 4;
        int startY = height / 4;
        int endY = 3 * height / 4;

        long sum = 0;
        int count = 0;

        // sample every 4 pixels to reduce processing
        for (int y = startY; y < endY; y += 4) {
            for (int x = startX; x < endX; x += 4) {
                int index = y * rowStride + x * pixelStride;
                int brightness = buffer.get(index) & 0xFF;

                sum += brightness;
                count++;
            }
        }

        return count > 0 ? (double) sum / count : 0;
    }

    private double calculateFrequencyAmplitude(List<double[]> samples, double frequency) {
        int n = samples.size();

        if (n == 0) {
            return 0;
        }

        // calculate mean brightness
        double mean = 0;
        for (double[] sample : samples) {
            mean += sample[1];
        }
        mean /= n;

        // measure correlation with target frequency
        double cosineSum = 0;
        double sineSum = 0;

        double startTime = samples.get(0)[0];

        for (double[] sample : samples) {
            double t = sample[0] - startTime;
            double centeredBrightness = sample[1] - mean;

            double angle = 2 * Math.PI * frequency * t;

            cosineSum += centeredBrightness * Math.cos(angle);
            sineSum += centeredBrightness * Math.sin(angle);
        }

        // calculate frequency amplitude
        return 2.0 * Math.hypot(cosineSum, sineSum) / n;
    }

    private void detectLiveFrequency(long timestamp, double brightness) {

        // convert timestamp to seconds
        double timeSeconds = timestamp / 1e9;

        // add newest sample
        recentSamples.addLast(new double[]{timeSeconds, brightness});

        // remove measurements older than 1 second
        double windowStart = (timestamp - WINDOW_NS) / 1e9;

        while (!recentSamples.isEmpty() && recentSamples.peekFirst()[0] < windowStart) {
            recentSamples.removeFirst();
        }

        // limit how often frequency detection runs
        if (timestamp - lastDetectionTimestamp < DETECTION_INTERVAL_NS) {
            return;
        }

        lastDetectionTimestamp = timestamp;

        // need enough camera samples
        if (recentSamples.size() < 16) {
            liveFrequencyText = "Collecting samples...";
            return;
        }

        List<double[]> samples = new ArrayList<>(recentSamples);

        double duration = samples.get(samples.size() - 1)[0] - samples.get(0)[0];

        if (duration < 0.8) {
            liveFrequencyText = "Collecting samples...";
            return;
        }

        // compare BFSK frequencies
        double amp2 = calculateFrequencyAmplitude(samples, 2.0);

        double amp4 = calculateFrequencyAmplitude(samples, 4.0);

        // determine dominant frequency
        double strongest = Math.max(amp2, amp4);
        double weakest = Math.min(amp2, amp4);

        // preliminary signal-strength criteria - avoid reporting bias
        double minAmplitude = 3.0;
        double minRatio = 1.5;

        if (strongest < minAmplitude || strongest / Math.max(weakest, 0.001) < minRatio) {
            liveFrequencyText = String.format(
                    java.util.Locale.US,
                    "No clear BFSK signal\n2 Hz: %.1f | 4 Hz: %.1f",
                    amp2, amp4
            );

        } else {
            int detectedBit = amp2 > amp4 ? 0 : 1;
            int detectedFrequency = detectedBit == 0 ? 2 : 4;

            liveFrequencyText = String.format(
                    java.util.Locale.US,
                    "Detected: %d Hz (Bit %d)\n2 Hz: %.1f | 4 Hz: %.1f",
                    detectedFrequency, detectedBit,
                    amp2, amp4
            );
        }
    }

    private void processDecoderFrame(long timestamp, double brightness) {
        double timeSeconds = timestamp / 1e9;

        if (decoderState == CALIBRATING) {
            if (calibrationStartNs < 0) {
                calibrationStartNs = timestamp;
            }

            baselineSamples.add(new double[]{timeSeconds, brightness});

            double elapsed = (timestamp - calibrationStartNs) / 1e9;
            if (elapsed >= 1.0) {
                double sum = 0;
                for (double[] sample : baselineSamples) {
                    sum += sample[1];
                }

                baselineBrightness = sum / baselineSamples.size();
                decoderState = WAITING;
                decoderStatus = String.format(
                        java.util.Locale.US,
                        "Ready! Baseline: %.1f",
                        baselineBrightness
                );
            }

        } else if (decoderState == WAITING) {
            // detect a sudden decrease in brightness
            double brightnessDrop = baselineBrightness - brightness;
            if (brightnessDrop > START_THRESHOLD) {
                transmissionStartNs = timestamp;
                messageSamples.clear();

                decoderState = RECEIVING;
                decoderStatus = "Receiving message...";

                messageSamples.add(new double[]{timeSeconds, brightness});
            }

        } else if (decoderState == RECEIVING) {
            messageSamples.add(new double[]{timeSeconds, brightness});

            double elapsed = (timestamp - transmissionStartNs) / 1e9;
            decoderStatus = String.format(
                    java.util.Locale.US,
                    "Receiving: %.1f / %.1f seconds",
                    Math.min(elapsed, 8.0),
                    8.0
            );

            // decode after all eight symbol windows
            if (elapsed >= MESSAGE_LENGTH * SYMBOL_SECONDS) {
                decodeMessage();
                decoderState = FINISHED;
            }
        }
    }

    private void decodeMessage() {
        StringBuilder decoded = new StringBuilder();

        double startSeconds = transmissionStartNs / 1e9;
        double edgeMargin = 0.08;

        for (int i = 0; i < MESSAGE_LENGTH; i++) {
            double symbolStart = startSeconds + i * SYMBOL_SECONDS;
            double symbolEnd = symbolStart + SYMBOL_SECONDS;

            List<double[]> symbolSamples = new ArrayList<>();
            for (double[] sample : messageSamples) {
                double t = sample[0];

                if (t >= symbolStart + edgeMargin && t < symbolEnd - edgeMargin) {
                    symbolSamples.add(sample);
                }
            }

            if (symbolSamples.size() < 8) {
                decoderStatus = "Decoding failed: insufficient samples";
                return;
            }

            double amp2 = calculateFrequencyAmplitude(symbolSamples, 2.0);
            double amp4 = calculateFrequencyAmplitude(symbolSamples, 4.0);
            char bit = amp2 > amp4 ? '0' : '1';

            decoded.append(bit);
        }

        String receivedBits = decoded.toString();

        decoderStatus = "Decoded: " + receivedBits;

        android.util.Log.d("BFSK","Received message: " + receivedBits);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            startCamera();
        } else {
            measurementText.setText("Camera permission denied");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}