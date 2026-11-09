## Real-Time Screen-Camera Communication Using BFSK

### Project Overview
This project implements a real-time screen-camera communication system inspired by the [HiLight paper](https://www.cs.columbia.edu/~xia/publication/mobisys15-hilight/mobisys15-hilight.pdf).

The system transmits binary information from a computer screen to a smartphone camera using Binary Frequency Shift Keying (BFSK). A static background image is displayed on the computer screen while a transparent black overlay changes its alpha value over time to encode information.

The smartphone camera captures these brightness changes and decodes the transmitted binary message by identifying the modulation frequency.

### Implementation

**Transmitter — HTML, CSS, JavaScript**

- Displays a static background image with a transparent black overlay.
- Uses BFSK to encode binary information:
  - Bit `0`: 2 Hz modulation
  - Bit `1`: 4 Hz modulation
- Supports alpha differences of 0.1 and 0.5.
- Transmits the binary sequence `01011010`.
- Uses `requestAnimationFrame()` to update overlay transparency.

**Receiver — Android Studio (Java)**

- Uses CameraX to capture frames from the smartphone's rear camera.
- Extracts average luminance from the central region of each frame.
- Records timestamps and brightness measurements.
- Detects transmission start using changes in brightness.
- Compares the received signal amplitudes at 2 Hz and 4 Hz to decode each bit.
- Displays the decoded binary message in the Android application.

**Signal Analysis — Python**

- Visualizes received brightness over time.
- Analyzes modulation frequencies using sinusoidal fitting.
- Compares transmitted and received bit sequences.
- Calculates bit error rate (BER) and effective data rate.
- Generates plots for comparing results across experimental conditions.

### Experimental Setup

The system was evaluated using a computer screen as the transmitter and an Android smartphone as the receiver.

| Parameter | Value |
|---|---|
| Modulation | BFSK |
| Bit 0 frequency | 2 Hz |
| Bit 1 frequency | 4 Hz |
| Transmitted sequence | `01011010` |
| Alpha differences | 0.1, 0.5 |
| Distances | 20, 40, 80, 120 cm |
| Symbol duration | 1 second |
| Nominal transmission rate | 1 bit/s |
| Observed camera sampling rate | Approximately 25.55 fps |

### Results

The receiver successfully decoded the transmitted sequence in seven of the eight experimental configurations.

| Distance | Alpha Difference | BER | Effective Data Rate |
|---|---|---|---|
| 20 cm | 0.1 | 0% | 1.000 bits/s |
| 20 cm | 0.5 | 0% | 1.000 bits/s |
| 40 cm | 0.1 | 0% | 1.000 bits/s |
| 40 cm | 0.5 | 0% | 1.000 bits/s |
| 80 cm | 0.1 | 0% | 1.000 bits/s |
| 80 cm | 0.5 | 0% | 1.000 bits/s |
| 120 cm | 0.1 | 62.5% | 0.375 bits/s |
| 120 cm | 0.5 | 0% | 1.000 bits/s |

**Key Findings**

- Communication was error-free at 20, 40, and 80 cm for both alpha differences.
- At 120 cm, Δα = 0.1 produced a BER of 62.5%, indicating reduced reliability.
- Increasing Δα to 0.5 at 120 cm restored error-free reception in the recorded trial.
- Larger alpha differences produced stronger brightness changes, generally improving detection reliability.

### Running the Project

**Transmitter**

1. Open `transmitter.html` in a web browser.
2. Ensure `background.jpg` is in the same directory.
3. Select the desired alpha difference.
4. Click **Transmit** to begin sending the binary message.

**Android Receiver**

1. Open the receiver project in Android Studio.
2. Build and run the application on an Android smartphone.
3. Grant camera permission.
4. Point the rear camera toward the computer screen.
5. Select **Arm Decoder** and allow baseline calibration to finish.
6. Click **Transmit** on the computer.
7. View the decoded binary message in the Android application.

**Python Analysis**

The Python analysis code uses recorded camera brightness data to visualize the signal and calculate communication performance.

Install the required libraries:

```bash
pip install numpy pandas matplotlib
```

Run the analysis scripts using the recorded CSV files.

### Limitations

- The initial experiments used eight-bit messages, so each bit error has a relatively large effect on BER.
- The receiver relies on brightness-based transmission-start detection, which may be affected by ambient lighting and camera exposure.
- Display refresh rate and camera frame timing can affect the accuracy of frequency detection.
- The experiments were conducted using a simplified BFSK implementation rather than the complete HiLight communication protocol.
- The reported results describe individual recorded trials rather than long-term communication reliability.

### References

Xia et al., [*HiLight: Real-Time Screen-Camera Communication Behind Any Scene*](https://www.cs.columbia.edu/~xia/publication/mobisys15-hilight/mobisys15-hilight.pdf), MobiSys 2015.