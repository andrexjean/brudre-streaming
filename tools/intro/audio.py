"""Assinatura sonora original da vinheta BruDre (7 s, 48 kHz, estéreo). Síntese pura com numpy."""
import numpy as np, wave

SR, DUR = 48000, 7.0
n = int(SR * DUR)
t = np.arange(n) / SR
rng = np.random.default_rng(3)
L = np.zeros(n); R = np.zeros(n)

def env(a, d_attack, d_release, start, end):
    e = np.zeros(n)
    m = (t >= start) & (t < end)
    x = t[m] - start
    e[m] = np.minimum(1, x / max(d_attack, 1e-4)) * np.clip((end - t[m]) / max(d_release, 1e-4), 0, 1)
    return e * a

def lowpass(x, cutoff):
    # filtro de 1 polo com corte variável (array ou escalar)
    c = np.broadcast_to(np.asarray(cutoff, float), x.shape)
    a = 1 - np.exp(-2 * np.pi * c / SR)
    y = np.zeros_like(x); acc = 0.0
    for i in range(len(x)):
        acc += a[i] * (x[i] - acc); y[i] = acc
    return y

def add(sig, pan=0.0):
    global L, R
    L += sig * np.sqrt(0.5 * (1 - pan)); R += sig * np.sqrt(0.5 * (1 + pan))

# 1) Drone grave que nasce do silêncio (0–3 s) — Ré grave com leve batimento
f0 = 73.42
drone = (np.sin(2 * np.pi * f0 * t) + .6 * np.sin(2 * np.pi * f0 * 1.003 * t) + .35 * np.sin(2 * np.pi * f0 * 2 * t))
drone *= env(.10, 1.6, 1.2, 0, 4.4)
add(drone, -.1); add(drone * .9, .1)

# 2) Brilho dos feixes de luz: ruído filtrado subindo (0,3–2,8 s)
noise = rng.standard_normal(n)
sweep = lowpass(noise, 300 + 5200 * np.clip((t - .3) / 2.5, 0, 1) ** 2)
add(sweep * env(.045, 1.8, .5, .3, 3.0), -.3)
add(lowpass(rng.standard_normal(n), 300 + 5200 * np.clip((t - .3) / 2.5, 0, 1) ** 2) * env(.045, 1.8, .5, .3, 3.0), .3)

# 3) Faíscas das partículas (1,5–3,0 s): bipes curtos e agudos em notas de Ré maior
notes = [1174.7, 1318.5, 1480.0, 1760.0, 1975.5, 2349.3]
for k in range(26):
    st = rng.uniform(1.5, 3.0); f = rng.choice(notes); dur = .18
    m = (t >= st) & (t < st + dur); x = t[m] - st
    s = np.sin(2 * np.pi * f * x) * np.exp(-x * 28) * .035
    sig = np.zeros(n); sig[m] = s; add(sig, rng.uniform(-.8, .8))

# 4) Impacto suave quando o nome aparece (3,0 s)
m = t >= 3.0; x = t[m] - 3.0
boom = np.zeros(n)
boom[m] = (np.sin(2 * np.pi * (48 + 30 * np.exp(-x * 9)) * x) * np.exp(-x * 3.2) * .55 +
           lowpass(rng.standard_normal(m.sum()), 900) * np.exp(-x * 14) * .5)
add(boom)

# 5) Pad quente (3,0–7 s): Ré maior com nona (D3 A3 F#4 E5), ataque lento
pad = np.zeros(n)
for f, a in ((146.83, .5), (220.0, .38), (369.99, .3), (659.25, .16)):
    for det in (-.004, .004):
        ph = rng.uniform(0, 6.28)
        pad += a * (np.sin(2 * np.pi * f * (1 + det) * t + ph) + .25 * np.sin(2 * np.pi * 2 * f * (1 + det) * t + ph))
pad = lowpass(pad, 2200) * env(.11, 1.3, 1.1, 3.0, 7.0)
add(pad * .9, -.25); add(pad * .9, .25)

# 6) Arpejo de sinos na revelação completa (5,0–5,7 s): síntese FM
for i, f in enumerate((587.33, 739.99, 880.0, 1174.66)):
    st = 5.0 + i * .16; m = t >= st; x = t[m] - st
    mod = np.sin(2 * np.pi * f * 3.5 * x) * 2.2 * np.exp(-x * 2.5)
    bell = np.zeros(n); bell[m] = np.sin(2 * np.pi * f * x + mod) * np.exp(-x * 1.9) * .16
    add(bell, -.5 + i * .33)

# 7) Pulso luminoso (5,7 s): swell curto de ruído aberto
add(lowpass(rng.standard_normal(n), 3000) * np.exp(-((t - 5.7) / .18) ** 2) * .05)

# Eco estéreo leve
d = int(.19 * SR)
L[d:] += R[:-d] * .22; R[d:] += L[:-d] * .22

# Fade final para a transição (6,4–7,0 s) e normalização a -1 dBFS
fade = np.clip((7.0 - t) / .6, 0, 1) ** 1.5
L *= fade; R *= fade
peak = max(np.abs(L).max(), np.abs(R).max())
g = 10 ** (-3 / 20) / peak
data = (np.stack([L, R], 1) * g * 32767).astype(np.int16)
with wave.open("intro.wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())
print("ok", peak)
