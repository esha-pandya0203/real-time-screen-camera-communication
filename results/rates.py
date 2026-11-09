import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("results.csv")
df = df[df["nominal_bit_rate"] == 1].copy()

# BER vs delta alpha, grouped by distance
plt.figure(figsize=(8, 5))

for distance, group in df.groupby("distance_cm"):
    group = group.sort_values("delta_alpha")
    plt.plot(group["delta_alpha"], group["ber"], marker="o", label=f"{distance} cm")

plt.xlabel("Alpha Difference (Δα)")
plt.ylabel("Bit Error Rate")
plt.title("Bit Error Rate vs Alpha Difference")
plt.xticks([0.1, 0.5])
plt.ylim(0, 1.05)
plt.legend(title="Distance")
plt.grid(alpha=0.3)
plt.tight_layout()
plt.savefig("ber_vs_alpha.png", dpi=300)
plt.show()

# effective data rate vs delta alpha
plt.figure(figsize=(8, 5))

for distance, group in df.groupby("distance_cm"):
    group = group.sort_values("delta_alpha")
    plt.plot(group["delta_alpha"], group["effective_data_rate"], marker="o", label=f"{distance} cm")

plt.xlabel("Alpha Difference (Δα)")
plt.ylabel("Correctly Received Bits per Second")
plt.title("Effective Data Rate vs Alpha Difference")
plt.xticks([0.1, 0.5])
plt.legend(title="Distance")
plt.grid(alpha=0.3)
plt.tight_layout()
plt.savefig("data_rate_vs_alpha.png", dpi=300)
plt.show()