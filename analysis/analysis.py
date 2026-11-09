import pandas as pd
import numpy as np 
import matplotlib.pyplot as plt

# load recorded data
df = pd.read_csv("brightness_data.csv")
time = df["time_seconds"].to_numpy(); 
brightness = df["brightness"].to_numpy(); 

# print summary of recording 
print("Total samples:", len(df))
print("Duration:", df["time_seconds"].iloc[-1])
print("Minimum brightness:", df["brightness"].min())
print("Maximum brightness:", df["brightness"].max())

# plot brightness over time
plt.figure(figsize=(12, 5))

plt.plot(
    df["time_seconds"],
    df["brightness"],
    linewidth=1
)

plt.xlabel("Time (seconds)")
plt.ylabel("Average Brightness")
plt.title("Received Screen Brightness Signal - BFSK")
plt.grid(True)

plt.tight_layout()
plt.show()

# transmitter configuration 
transmitted_bits = "01011010" 
f0 = 2.0 
f1 = 4.0 
symbol_duration = 1.0 

# find tranmission start 
baseline = np.median(brightness[time < 1.0])
threshold = 15.0 
start_candidates = np.where(np.abs(brightness - baseline) > threshold)[0] 

if len(start_candidates) == 0: 
    raise ValueError("Transmission start not detected")

start_time = time[start_candidates[0]]
print(f"Detected start time: {start_time:.3f} s") 

# estimate amplitude at a target frequency 
# B(t) = c + acos(2pi*ft) + bsin(2pi * ft) 
# B(t) = measured brightness 
# c = average brightness level 
# f = frequency tested 
# a and b determine how strongly f appears in the signal 
def frequency_amplitude(t, y, frequency): 
    matrix = np.column_stack([
        np.ones(len(t)), 
        np.cos(2 * np.pi * frequency * t), 
        np.sin(2 * np.pi * frequency * t)
    ])

    coefficients, *_ = np.linalg.lstsq(matrix, y, rcond=None) 

    return np.hypot(coefficients[1], coefficients[2]) 

# decode each symbol 
decoded_bits = "" 
results = [] 
edge_margin = 0.08 

for i in range(len(transmitted_bits)): 
    symbol_start = start_time + i * symbol_duration 
    symbol_end = symbol_start + symbol_duration 

    # exclude samples near symbol boundaries 
    mask = ((time >= symbol_start + edge_margin) & (time < symbol_end - edge_margin))
    
    t_symbol = time[mask]
    y_symbol = brightness[mask] 

    if len(t_symbol) < 8: 
        raise ValueError(f"Insufficient samples for bit {i+1}")

    amp_2hz = frequency_amplitude(t_symbol, y_symbol, f0) 
    amp_4hz = frequency_amplitude(t_symbol, y_symbol, f1) 

    bit = "0" if amp_2hz > amp_4hz else "1"

    decoded_bits += bit
    results.append([i+1, amp_2hz, amp_4hz, bit])

# calculate performance 
correct_bits = sum(a == b for a, b, in zip(transmitted_bits, decoded_bits))

total_bits = len(transmitted_bits)
ber = 1 - correct_bits / total_bits 

transmission_time = total_bits * symbol_duration 
data_rate = correct_bits / transmission_time 

print("\nTransmitted:", transmitted_bits)
print("Decoded:    ", decoded_bits)
print("Correct bits:", correct_bits)
print(f"BER: {ber:.4f}")
print(f"Data rate: {data_rate:.2f} bits/s") 

# show frequency amplitudes for each symbol 
results_df = pd.DataFrame(results, columns=["Symbol", "2 Hz Amplitude", "4 Hz Amplitude", "Decoded Bit"])
print("\n", results_df.to_string(index=False))

# compare 2 Hz and 4 Hz amplitudes by symbol
x = np.arange(1, len(results_df) + 1)
width = 0.35

plt.figure(figsize=(10, 5))
plt.bar(x - width/2, results_df["2 Hz Amplitude"], width, label="2 Hz (Bit 0)")
plt.bar(x + width/2, results_df["4 Hz Amplitude"], width, label="4 Hz (Bit 1)")

plt.xticks(x)
plt.xlabel("Symbol Number")
plt.ylabel("Fitted Frequency Amplitude")
plt.title("BFSK Frequency Detection by Symbol")
plt.legend()
plt.grid(axis="y", alpha=0.3)
plt.tight_layout()
plt.show()