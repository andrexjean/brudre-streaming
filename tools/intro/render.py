"""Vinheta BruDre Streaming — 7 s, 1920x1080, 30 fps. Renderiza quadros em numpy/cv2 e envia ao ffmpeg por pipe."""
import math, random, subprocess, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

W, H, FPS, DUR = 1920, 1080, 30, 7.0
N = int(FPS * DUR)
import os
HERE = os.path.dirname(os.path.abspath(__file__))

def hexc(h): return np.array([int(h[i:i+2], 16) / 255 for i in (1, 3, 5)], np.float32)
BG = hexc("#050814"); NAVY = hexc("#0A1533"); CYAN = hexc("#37D8FF"); NEON = hexc("#2F7BFF"); LILAC = hexc("#9D76FF")
WHITE = np.ones(3, np.float32); DIM = hexc("#B4C1E0")

def clamp(x, a=0.0, b=1.0): return max(a, min(b, x))
def seg(t, a, b): return clamp((t - a) / (b - a))
def ease_out(x): return 1 - (1 - x) ** 3
def ease_in_out(x): return 3 * x * x - 2 * x * x * x
def ease_back(x, s=1.4): x -= 1; return x * x * ((s + 1) * x + s) + 1

# ----------------------------------------------------------- camadas fixas
font_big = ImageFont.truetype(f"{HERE}/Poppins-Black.ttf", 230)
font_small = ImageFont.truetype(f"{HERE}/Poppins-SemiBold.ttf", 46)

def text_mask(text, font, spacing=0):
    if spacing == 0:
        bb = font.getbbox(text)
        img = Image.new("L", (bb[2] - bb[0] + 40, bb[3] - bb[1] + 40), 0)
        ImageDraw.Draw(img).text((20 - bb[0], 20 - bb[1]), text, font=font, fill=255)
        return np.asarray(img, np.float32) / 255
    widths = [font.getlength(c) for c in text]
    total = int(sum(widths) + spacing * (len(text) - 1)) + 40
    bb = font.getbbox(text)
    img = Image.new("L", (total, bb[3] - bb[1] + 40), 0)
    d = ImageDraw.Draw(img); x = 20
    for c, w in zip(text, widths):
        d.text((x, 20 - bb[1]), c, font=font, fill=255); x += w + spacing
    return np.asarray(img, np.float32) / 255

bru = text_mask("Bru", font_big); dre = text_mask("Dre", font_big)
gap_bd = -33  # as máscaras têm margem de 20 px de cada lado
word_w = bru.shape[1] + dre.shape[1] + gap_bd
word_h = max(bru.shape[0], dre.shape[0])
streaming = text_mask("STREAMING", font_small, spacing=22)

TV = 250  # lado da caixa do ícone
def tv_paths(ox, oy, s):
    """Pontos do contorno da TV (antenas + retângulo arredondado), na ordem de desenho."""
    pts_ant = [[(ox + s * .5, oy + s * .30), (ox + s * .28, oy + s * .04)], [(ox + s * .5, oy + s * .30), (ox + s * .74, oy + s * .02)]]
    st = s * .075; x0, y0, x1, y1 = ox + st / 2, oy + s * .30, ox + s - st / 2, oy + s * .98; r = s * .16
    rect = []
    def arc(cx, cy, a0, a1):
        for i in range(13):
            a = math.radians(a0 + (a1 - a0) * i / 12); rect.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    rect.append(((x0 + x1) / 2, y0))
    arc(x1 - r, y0 + r, -90, 0); arc(x1 - r, y1 - r, 0, 90); arc(x0 + r, y1 - r, 90, 180); arc(x0 + r, y0 + r, 180, 270)
    rect.append(((x0 + x1) / 2, y0))
    return pts_ant, rect, st

def polyline_partial(pts, frac):
    if frac <= 0: return []
    L = [0.0]
    for a, b in zip(pts, pts[1:]): L.append(L[-1] + math.dist(a, b))
    target = L[-1] * frac; out = [pts[0]]
    for i in range(1, len(pts)):
        if L[i] <= target: out.append(pts[i])
        else:
            a, b = pts[i - 1], pts[i]; k = (target - L[i - 1]) / max(1e-6, L[i] - L[i - 1])
            out.append((a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k)); break
    return out

def draw_tv(layer_line, layer_fill, ox, oy, s, frac_line, fill_alpha):
    ants, rect, st = tv_paths(ox, oy, s)
    th = max(2, int(st))
    sc = 4  # subpixel
    for seq, f in ((ants[0], frac_line), (ants[1], frac_line), (rect, frac_line)):
        p = polyline_partial(seq, f)
        if len(p) >= 2:
            cv2.polylines(layer_line, [np.int32(np.array(p) * (1 << sc))], False, 1.0, th, cv2.LINE_AA, shift=sc)
    if fill_alpha > 0:
        cx, cy = ox + s * .53, oy + s * .64
        tri = np.array([(cx - s * .13, cy - s * .16), (cx - s * .13, cy + s * .16), (cx + s * .17, cy)])
        tmp = np.zeros_like(layer_fill)
        cv2.fillPoly(tmp, [np.int32(tri * (1 << sc))], 1.0, cv2.LINE_AA, shift=sc)
        np.maximum(layer_fill, tmp * fill_alpha, out=layer_fill)

# Composição final: [Bru|Dre] + espaço + TV, centralizado; STREAMING abaixo do texto.
GAP = 30
group_w = word_w + GAP + TV
GX = (W - group_w) // 2
GY = 300
TEXT_X, TEXT_Y = GX, GY
TV_X, TV_Y = GX + word_w + GAP, GY + 20
ST_X = TEXT_X + (word_w - streaming.shape[1]) // 2 + 10
ST_Y = TEXT_Y + word_h + 6

# Alvos das partículas: pontos da máscara do texto e do contorno da TV.
rng = np.random.default_rng(7)
def mask_points(m, ox, oy, n):
    ys, xs = np.nonzero(m > 0.6); idx = rng.choice(len(xs), n)
    return np.stack([xs[idx] + ox, ys[idx] + oy], 1).astype(np.float32)
_, rect_pts, _ = tv_paths(TV_X, TV_Y, TV)
P_TEXT = np.concatenate([mask_points(bru, TEXT_X, TEXT_Y, 260), mask_points(dre, TEXT_X + bru.shape[1] + gap_bd, TEXT_Y, 260)])
P_TV = np.array([rect_pts[i] for i in rng.integers(0, len(rect_pts), 140)], np.float32) + rng.normal(0, 2, (140, 2)).astype(np.float32)
P_TV_REL = (P_TV - np.array([TV_X, TV_Y], np.float32)) / TV
TARGETS = np.concatenate([P_TEXT, P_TV])
N_TEXT = len(P_TEXT)
NP = len(TARGETS)
ang = rng.uniform(0, 2 * math.pi, NP); rad = rng.uniform(500, 1300, NP)
STARTS = np.stack([W / 2 + np.cos(ang) * rad, H / 2 + np.sin(ang) * rad * .7], 1).astype(np.float32)
DELAY = rng.uniform(0, .5, NP).astype(np.float32)
PCOL = np.where(rng.random(NP)[:, None] < .55, CYAN, np.where(rng.random(NP)[:, None] < .5, NEON, LILAC)).astype(np.float32)
PSIZE = rng.uniform(1.6, 3.6, NP)

# Fundo: gradiente + vinheta (pré-calculado).
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
vign = 1 - 0.55 * (((xx - W / 2) / (W * .62)) ** 2 + ((yy - H / 2) / (H * .62)) ** 2)
vign = np.clip(vign, 0, 1)[..., None]
grad = (NAVY * (1 - yy / H)[..., None] * .9 + BG * (yy / H)[..., None] + BG * .1)
BASE = (grad * (.35 + .65 * vign)).astype(np.float32)
# Fundo do app (destino da transição final), igual ao AppBackground: navy -> bg.
APPBG = np.where((yy / H < .5)[..., None], NAVY + (BG - NAVY) * (yy / H * 2)[..., None], BG).astype(np.float32)

sw, sh = W // 4, H // 4
sy, sx = np.mgrid[0:sh, 0:sw].astype(np.float32)

def beams(t):
    """Feixes de luz azul/ciano que surgem e cruzam a tela (meia-resolução + blur)."""
    out = np.zeros((sh, sw, 3), np.float32)
    specs = [(-28, .30, CYAN, 0.0, 1.0), (22, .62, NEON, .25, 1.2), (-12, .48, LILAC, .5, .7), (35, .20, CYAN, .8, .6)]
    for i, (deg, pos, col, t0, k) in enumerate(specs):
        a = seg(t, t0, t0 + 1.0) * (1 - .75 * seg(t, 2.6, 4.6)) * .62
        if a <= 0: continue
        th = math.radians(deg)
        nx, ny = -math.sin(th), math.cos(th)
        drift = (t - t0) * 30 * (1 if i % 2 else -1)
        cx, cy = sw * (pos + drift / sw), sh * .5
        d = (sx - cx) * nx + (sy - cy) * ny
        core = np.exp(-(d / (3.0 + i)) ** 2) * .8; halo = np.exp(-(d / 34) ** 2) * .3
        along = (sx - cx) * math.cos(th) + (sy - cy) * math.sin(th)
        fall = np.exp(-(along / (sw * .55)) ** 2)
        out += ((core + halo) * fall * a * k)[..., None] * col
    return cv2.resize(out, (W, H), interpolation=cv2.INTER_LINEAR)

def glow(img, r1=9, r2=41):
    small = cv2.resize(img, (W // 2, H // 2), interpolation=cv2.INTER_AREA)
    g = cv2.GaussianBlur(small, (0, 0), r1 / 2) * .9 + cv2.GaussianBlur(small, (0, 0), r2 / 2) * .7
    return cv2.resize(g, (W, H), interpolation=cv2.INTER_LINEAR)

def place(dst_a, m, x, y, scale=1.0, alpha=1.0):
    """Cola máscara m (escalada em torno do centro) na camada dst_a."""
    if alpha <= 0: return
    if abs(scale - 1) > 1e-3:
        h, w = m.shape; nh, nw = max(1, int(h * scale)), max(1, int(w * scale))
        m2 = cv2.resize(m, (nw, nh), interpolation=cv2.INTER_LINEAR); x += (w - nw) // 2; y += (h - nh) // 2; m = m2
    h, w = m.shape
    x0, y0 = max(0, x), max(0, y); x1, y1 = min(W, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0: return
    sub = m[y0 - y:y1 - y, x0 - x:x1 - x] * alpha
    np.maximum(dst_a[y0:y1, x0:x1], sub, out=dst_a[y0:y1, x0:x1])

def frame(t):
    img = BASE.copy()
    img += beams(t)

    tv_settle = ease_back(seg(t, 3.0, 4.2), 1.2)
    # A TV nasce no centro, maior, e assume a posição original ao lado do nome.
    cx0, cy0, s0 = W / 2 - TV * .8, H / 2 - TV * .8 - 20, TV * 1.6
    s_now = s0 + (TV - s0) * tv_settle
    ox = cx0 + (TV_X - cx0) * tv_settle; oy = cy0 + (TV_Y - cy0) * tv_settle
    tgt = TARGETS.copy(); tgt[N_TEXT:] = np.array([ox, oy], np.float32) + P_TV_REL * s_now

    # ---- partículas convergindo (1,4 s – 3,4 s)
    pa = seg(t, 1.2, 1.6) * (1 - seg(t, 3.1, 3.7))
    plyr = np.zeros((H, W, 3), np.float32)
    if pa > 0:
        k = np.clip((t - 1.4 - DELAY) / 1.4, 0, 1); k = 1 - (1 - k) ** 3
        swirl = (1 - k) * 1.2
        P = STARTS + (tgt - STARTS) * k[:, None]
        c, s = np.cos(swirl), np.sin(swirl)
        rel = P - np.array([W / 2, H / 2], np.float32)
        P = np.stack([rel[:, 0] * c - rel[:, 1] * s, rel[:, 0] * s + rel[:, 1] * c], 1) * 1 + np.array([W / 2, H / 2], np.float32)
        P = tgt + (P - tgt) * (1 - k[:, None]) ** .5
        for (x, y), col, r in zip(P, PCOL, PSIZE):
            cv2.circle(plyr, (int(x * 4), int(y * 4)), int(r * 4), col.tolist(), -1, cv2.LINE_AA, shift=2)
        plyr *= pa
        img += plyr + glow(plyr, 5, 25) * 1.4

    # ---- contorno da TV desenhado por linhas luminosas (1,5 s – 3,0 s)
    line = np.zeros((H, W), np.float32); fill = np.zeros((H, W), np.float32)
    draw_tv(line, fill, ox, oy, s_now, ease_in_out(seg(t, 1.5, 3.0)), ease_out(seg(t, 2.7, 3.4)))

    # ---- nome BruDre com profundidade (3,0 s – 4,6 s) e STREAMING (4,0 s – 5,0 s)
    ta = ease_out(seg(t, 3.0, 4.0)); tscale = 1.0 + .22 * (1 - ease_out(seg(t, 3.0, 4.4)))
    lift = int(36 * (1 - ease_out(seg(t, 3.0, 4.4))))
    bru_a = np.zeros((H, W), np.float32); dre_a = np.zeros((H, W), np.float32)
    place(bru_a, bru, TEXT_X, TEXT_Y + lift, tscale, ta)
    place(dre_a, dre, TEXT_X + bru.shape[1] + gap_bd, TEXT_Y + lift, tscale, ease_out(seg(t, 3.25, 4.2)))
    if t < 4.4 and ta > 0:  # desfoque de profundidade diminuindo
        b = (1 - ease_out(seg(t, 3.0, 4.4))) * 14 + .01
        bru_a = cv2.GaussianBlur(bru_a, (0, 0), b); dre_a = cv2.GaussianBlur(dre_a, (0, 0), b)
    st_a = np.zeros((H, W), np.float32)
    sp = ease_out(seg(t, 4.0, 5.0))
    place(st_a, streaming, ST_X, ST_Y + int(20 * (1 - sp)), 1.0 + .08 * (1 - sp), sp)

    logo = (bru_a[..., None] * WHITE + dre_a[..., None] * CYAN + line[..., None] * CYAN + fill[..., None] * NEON + st_a[..., None] * DIM)
    alpha = np.clip(bru_a + dre_a + line + fill + st_a, 0, 1)

    # ---- brilho especular passando pela marca (5,0 s – 6,1 s) e pulsação (5,7 s)
    sh_k = seg(t, 5.0, 6.1)
    if 0 < sh_k < 1:
        xpos = GX - 300 + (group_w + 600) * ease_in_out(sh_k)
        band = np.exp(-(((xx - xpos) + (yy - H / 2) * .45) / 70) ** 2)
        logo = logo + band[..., None] * alpha[..., None] * .7
    pulse = math.exp(-((t - 5.7) / .35) ** 2)
    g_amt = .55 + .35 * seg(t, 3.0, 5.0) + .9 * pulse

    # ---- reflexo no "chão"
    refl = np.zeros_like(logo)
    top = ST_Y + streaming.shape[0] + 10
    if t > 3.2:
        src = logo[GY:top]
        flip = src[::-1]
        hgt = min(flip.shape[0], H - top)
        fade = np.linspace(.13, 0, hgt, dtype=np.float32)[:, None, None] * seg(t, 3.6, 4.8)
        refl[top:top + hgt] = cv2.GaussianBlur(flip[:hgt], (0, 0), 3) * fade

    comp = img * (1 - alpha[..., None] * .92) + logo
    comp += glow(logo, 8, 50) * g_amt * .38 + refl
    # Luz ambiente que "acende" atrás da marca
    halo = np.exp(-(((xx - W / 2) / 700) ** 2 + ((yy - (GY + 150)) / 260) ** 2))[..., None]
    comp += halo * NEON * .16 * seg(t, 3.0, 5.0) * (1 + .6 * pulse)

    # ---- transição suave para a tela inicial (6,5 s – 7,0 s)
    tr = ease_in_out(seg(t, 6.45, 7.0))
    if tr > 0: comp = comp * (1 - tr) + APPBG * tr
    # fade-in inicial
    comp *= ease_out(seg(t, 0, .35)) * .97 + .03 if t < .35 else 1
    # leve grão para evitar banding
    comp += rng.normal(0, .004, (H, W, 1)).astype(np.float32)
    return (np.clip(comp, 0, 1) ** (1 / 1.0) * 255).astype(np.uint8)

if __name__ == "__main__":
    if sys.argv[1] == "stills":
        for t in map(float, sys.argv[2:]):
            cv2.imwrite(f"{HERE}/still_{t:.1f}.jpg", cv2.cvtColor(frame(t), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
        sys.exit(0)
    out = sys.argv[2]
    p = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                          "-c:v", "libx264", "-preset", "medium", "-crf", "21", "-profile:v", "main", "-level", "4.0", "-pix_fmt", "yuv420p",
                          "-movflags", "+faststart", "-g", "60", out], stdin=subprocess.PIPE)
    for i in range(N):
        p.stdin.write(frame(i / FPS).tobytes())
        if i % 30 == 0: print("frame", i, flush=True)
    p.stdin.close(); sys.exit(p.wait())
