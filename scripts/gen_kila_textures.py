"""Generates the procedural VFX textures in assets/photon/textures/kila (deterministic, tileable noise)."""
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

SEED = 20261006
OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/photon/textures/kila"


def rng(tag):
    return np.random.default_rng([SEED, zlib.crc32(tag.encode())])


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def norm(a):
    return (a - a.min()) / (a.max() - a.min())


def uv(w, h):
    return np.meshgrid((np.arange(w) + 0.5) / w, (np.arange(h) + 0.5) / h)


def polar(n):
    u, v = uv(n, n)
    x, y = 2 * u - 1, 2 * v - 1
    return x, y, np.hypot(x, y)


def lattice(size, period, r):
    p = np.arange(size) / size * period + r.uniform(0, period)
    i = np.floor(p).astype(int)
    return i % period, (i + 1) % period, p - i


def perlin(size, period, r):
    a = r.uniform(0, 2 * np.pi, (period, period))
    g = np.stack([np.cos(a), np.sin(a)], -1)
    x0, x1, fx = lattice(size, period, r)
    y0, y1, fy = lattice(size, period, r)
    fx, fy = fx[None, :], fy[:, None]

    def dot(yi, xi, dx, dy):
        c = g[yi[:, None], xi[None, :]]
        return c[..., 0] * dx + c[..., 1] * dy

    sx, sy = (t * t * t * (t * (t * 6 - 15) + 10) for t in (fx, fy))
    top = dot(y0, x0, fx, fy) * (1 - sx) + dot(y0, x1, fx - 1, fy) * sx
    bot = dot(y1, x0, fx, fy - 1) * (1 - sx) + dot(y1, x1, fx - 1, fy - 1) * sx
    return top * (1 - sy) + bot * sy


def fbm(size, period, octaves, r, gain=0.5):
    return sum(gain ** o * perlin(size, period * 2 ** o, r) for o in range(octaves))


def voronoi_f1(size, cells, r):
    pts = r.random((cells, cells, 2))
    p = (np.arange(size) + 0.5) / size * cells
    px, py = p[None, :], p[:, None]
    cx, cy = np.floor(px).astype(int), np.floor(py).astype(int)
    d = np.full((size, size), np.inf)
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            nx, ny = cx + dx, cy + dy
            f = pts[ny % cells, nx % cells]
            d = np.minimum(d, np.hypot(nx + f[..., 0] - px, ny + f[..., 1] - py))
    return d


def value_noise3(n, cells, r):
    """Tileless 3D value noise on an n^3 grid in [-1, 1]^3, trilinear between random lattice values."""
    lattice = r.random((cells + 1,) * 3)
    p = np.linspace(0, cells, n, endpoint=False) + 0.5 * cells / n
    i = np.floor(p).astype(int)
    f = p - i
    f = f * f * (3 - 2 * f)
    ix, iy, iz = np.meshgrid(i, i, i, indexing="ij")
    fx, fy, fz = np.meshgrid(f, f, f, indexing="ij")
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * lattice[ix + dx, iy + dy, iz + dz]
    return out


def rotate(image, angle):
    """image rotated about its centre by angle (radians, counter-clockwise on screen), bilinear, 0 outside."""
    h, w = image.shape
    yy, xx = np.meshgrid(np.arange(h) + 0.5, np.arange(w) + 0.5, indexing="ij")
    cx, cy = w / 2, h / 2
    c, s_ = np.cos(angle), np.sin(angle)
    sx = c * (xx - cx) - s_ * (yy - cy) + cx - 0.5
    sy = s_ * (xx - cx) + c * (yy - cy) + cy - 0.5
    x0, y0 = np.floor(sx).astype(int), np.floor(sy).astype(int)
    fx, fy = sx - x0, sy - y0

    def at(y, x):
        inside = (x >= 0) & (x < w) & (y >= 0) & (y < h)
        return np.where(inside, image[np.clip(y, 0, h - 1), np.clip(x, 0, w - 1)], 0.0)

    return ((at(y0, x0) * (1 - fx) + at(y0, x0 + 1) * fx) * (1 - fy)
            + (at(y0 + 1, x0) * (1 - fx) + at(y0 + 1, x0 + 1) * fx) * fy)


def sample_wrap(image, u, v):
    """image at (u, v) in [0, 1), wrapping, bilinear; v runs down the rows."""
    h, w = image.shape
    sx, sy = u * w - 0.5, v * h - 0.5
    x0, y0 = np.floor(sx).astype(int), np.floor(sy).astype(int)
    fx, fy = sx - x0, sy - y0

    def at(y, x):
        return image[y % h, x % w]

    return ((at(y0, x0) * (1 - fx) + at(y0, x0 + 1) * fx) * (1 - fy)
            + (at(y0 + 1, x0) * (1 - fx) + at(y0 + 1, x0 + 1) * fx) * fy)


def seg_dist(x, y, a, b):
    d = b - a
    t = np.clip(((x - a[0]) * d[0] + (y - a[1]) * d[1]) / (d @ d), 0, 1)
    return np.hypot(x - a[0] - t * d[0], y - a[1] - t * d[1])


def save(name, *channels):
    shape = np.broadcast_shapes(*(np.shape(c) for c in channels))
    q = [np.broadcast_to(np.round(np.clip(c, 0, 1) * 255).astype(np.uint8), shape) for c in channels]
    Image.fromarray(np.stack(q, -1)).save(OUT / name)


def save_shape(name, alpha):
    save(name, 1.0, 1.0, 1.0, alpha)


def save_scalar(name, v, alpha=1.0):
    save(name, v, v, v, alpha)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    save_scalar("white.png", np.ones((4, 4)))

    _, _, r = polar(128)
    save_shape("soft_circle.png", np.clip(1 - r * r, 0, 1) ** 2)
    save_shape("ring.png", np.where(r < 1, np.exp(-4 * np.log(2) * ((r - 0.75) / 0.12) ** 2), 0))
    e = np.exp(-6.0)
    glow = (np.exp(-6 * r * r) - e) / (1 - e) + 0.25 * np.exp(-3 * r) * (1 - r)
    save_shape("glow.png", np.where(r < 1, np.clip(glow, 0, 1), 0))

    save_scalar("noise_perlin.png", norm(fbm(256, 4, 5, rng("perlin"))))
    cr = rng("cloud")
    cloud = np.abs(perlin(256, 3, cr)) + 0.5 * fbm(256, 6, 5, cr)
    save_scalar("noise_cloud.png", norm(np.tanh(0.85 * (cloud - cloud.mean()) / cloud.std())))
    save_scalar("noise_cells.png", norm(voronoi_f1(256, 8, rng("cells"))))
    flow = [fbm(256, 4, 4, rng(tag)) for tag in ("flow_r", "flow_g")]
    flow = [0.5 + 0.5 * (f - f.mean()) / np.abs(f - f.mean()).max() for f in flow]
    save("noise_flow.png", *flow, 0.5, 1.0)
    # a swirl flow map, still at the centre and outside the circle
    x, y, r = polar(128)
    tx, ty = -y / np.maximum(r, 1e-6), x / np.maximum(r, 1e-6)
    k = smoothstep(0.0, 0.2, r) * (1 - smoothstep(0.85, 1.0, r))
    fx, fy = (0.9 * tx - 0.35 * x / np.maximum(r, 1e-6)) * k, (0.9 * ty - 0.35 * y / np.maximum(r, 1e-6)) * k
    save("flow_swirl.png", 0.5 + 0.5 * fx, 0.5 + 0.5 * fy, 0.5, 1.0)

    lin = np.tile(np.linspace(0, 1, 256), (16, 1))
    save_scalar("gradient_linear.png", lin, lin)
    _, _, r = polar(256)
    rad = 1 - np.clip(r, 0, 1)
    save_scalar("gradient_radial.png", rad, rad)

    u = np.linspace(0, 1, 256)[None, :]
    v = (np.arange(64)[:, None] + 0.5) / 64
    save_shape("trail.png", np.exp(-2 * ((v - 0.5) / 0.18) ** 2) * (1 - u) ** 0.6 * smoothstep(0, 0.04, u))

    u, v = uv(256, 256)
    co, ro, ci, ri = 0.62, 0.42, 0.70, 0.40
    horn = np.arccos((co - (ro ** 2 - ri ** 2 + ci ** 2 - co ** 2) / (2 * (ci - co))) / ro)
    taper = smoothstep(0, 1, np.clip(1 - np.abs(np.arctan2(u - 0.5, co - v)) / horn, 0, 1)) ** 0.5
    edge_out = smoothstep(-0.005, 0.005, ro - np.hypot(u - 0.5, v - co))
    edge_in = smoothstep(-0.005, 0.005, np.hypot(u - 0.5, v - ci) - ri)
    save_shape("slash.png", edge_out * edge_in * taper)

    x, y, r = polar(256)
    aa = 1.5 * 2 / 256

    def stroke(d, w):
        return 1 - smoothstep(w / 2 - aa / 2, w / 2 + aa / 2, d)

    def at(deg, rad):
        return rad * np.array([np.cos(np.radians(deg)), np.sin(np.radians(deg))])

    strokes = [stroke(np.abs(r - 0.92), 0.015), stroke(np.abs(r - 0.80), 0.015)]
    for start in (-90, 90):
        tri = [at(start + 120 * k, 0.78) for k in range(3)]
        strokes += [stroke(seg_dist(x, y, tri[k], tri[(k + 1) % 3]), 0.012) for k in range(3)]
    strokes += [stroke(seg_dist(x, y, at(15 * k, 0.82), at(15 * k, 0.90)), 0.012) for k in range(24)]
    save_shape("rune_circle.png", np.max(strokes, axis=0))

    x, y, r = polar(64)
    ax, ay = np.abs(x), np.abs(y)
    rays = np.maximum(np.exp(-3 * ax - 40 * ay), np.exp(-3 * ay - 40 * ax))
    save_shape("spark.png", np.clip(rays + np.exp(-(r / 0.1) ** 2), 0, 1) * smoothstep(1.0, 0.7, r))

    # a tangent-space normal map (OpenGL: green up) from a tileable height
    h = norm(fbm(256, 6, 4, rng("bump")))
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5 * 256 / 24
    dy_up = -(np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5 * 256 / 24
    n = np.stack([-dx, -dy_up, np.ones_like(h)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    save("normal_noise.png", *(0.5 + 0.5 * n[..., i] for i in range(3)), 1.0)

    # matcap: a glassy blue sphere lit from the upper left
    x, y, r = polar(256)
    rr = np.minimum(r, 0.999)
    nx, ny, nz = x / np.maximum(r, 1e-6) * rr, -y / np.maximum(r, 1e-6) * rr, np.sqrt(1 - rr * rr)
    lx, ly, lz = np.array([-0.5, 0.6, 0.62]) / np.linalg.norm([-0.5, 0.6, 0.62])
    diffuse = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)
    hx, hy, hz = np.array([lx, ly, lz + 1]) / np.linalg.norm([lx, ly, lz + 1])
    spec = np.clip(nx * hx + ny * hy + nz * hz, 0, 1) ** 60
    rim = (1 - nz) ** 3
    base = np.array([0.05, 0.12, 0.25])
    cols = [np.clip(base[i] + diffuse * [0.25, 0.45, 0.7][i] + rim * [0.5, 0.8, 1.0][i] + spec, 0, 1) for i in range(3)]
    save("matcap_glass.png", *cols, 1.0)

    # six-way lightmaps of a smoke puff, Unity's layout: positive = right, top, back + alpha;
    # negative = left, bottom, front + the same alpha. Z runs from the viewer (front) to the back.
    N = 96
    g = np.linspace(-1, 1, N)
    X, Y, Z = np.meshgrid(g, g, g, indexing="ij")  # X right, Y up, Z back
    R3 = np.sqrt(X * X + Y * Y + Z * Z)
    noise = value_noise3(N, 6, rng("smoke6")) * 0.65 + value_noise3(N, 13, rng("smoke6b")) * 0.35
    density = np.clip(smoothstep(1.0, 0.35, R3) * (0.25 + 1.5 * noise) - 0.25, 0, None) * 9
    step = 2.0 / N
    absorb = density * step

    def light_from(axis, positive):
        a = absorb if positive else np.flip(absorb, axis)
        t = np.exp(-(np.flip(np.cumsum(np.flip(a, axis), axis), axis) - a * 0.5))
        return t if positive else np.flip(t, axis)

    view_t = np.exp(-(np.cumsum(absorb, 2) - absorb * 0.5))  # from the front (z = -1) in
    weight = view_t * (1 - np.exp(-absorb))
    alpha = 1 - np.exp(-absorb.sum(2))

    def shade(axis, positive):
        c = (weight * light_from(axis, positive)).sum(2) / np.maximum(alpha, 1e-4)
        # image rows run down, columns right: [x, y] -> [row = -y, column = x]
        return np.flipud(c.T)

    a_img = np.flipud(alpha.T)
    save("smoke6_pos.png", shade(0, True), shade(1, True), shade(2, True), a_img)
    save("smoke6_neg.png", shade(0, False), shade(1, False), shade(2, False), a_img)

    # a 4 x 4 flipbook of a puff turning, and its motion vectors: rg = 0.5 + 0.5 * the cell-uv step to the next
    cell = 64
    turn = 2 * np.pi / 16
    x, y, r = polar(cell)
    # crisp detail, so a crossfade's double image would show
    sparks = sum(np.exp(-((x - 0.55 * np.cos(a)) ** 2 + (y - 0.55 * np.sin(a)) ** 2) / 0.004)
                 for a in np.linspace(0, 2 * np.pi, 6, endpoint=False) + 0.3)
    detail = smoothstep(0.35, 0.75, norm(fbm(cell, 5, 5, rng("flip"))))
    puff = np.clip(np.clip(1 - r, 0, 1) ** 1.2 * (0.25 + 0.75 * detail) + sparks, 0, 1)
    sheet = np.zeros((cell * 4, cell * 4))
    for k in range(16):
        row, col = divmod(k, 4)
        sheet[row * cell:(row + 1) * cell, col * cell:(col + 1) * cell] = rotate(puff, -k * turn)
    u, v = uv(cell, cell)
    du, dv = u - 0.5, v - 0.5
    # the same turn on screen, clockwise in image coordinates (v down)
    mu = np.cos(turn) * du - np.sin(turn) * dv - du
    mv = np.sin(turn) * du + np.cos(turn) * dv - dv
    save("smoke_flip.png", 1.0, 1.0, 1.0, sheet)
    save("smoke_flip_mv.png", np.tile(0.5 + 0.5 * mu, (4, 4)), np.tile(0.5 + 0.5 * mv, (4, 4)), 0.5, 1.0)

    # an 8 x 4 flipbook: a row per puff, each billowing out and thinning over eight frames; its motion vectors are
    # each frame's growth to the next, about the cell's centre
    cell, frames, puffs = 96, 8, 4
    x, y, r = polar(cell)
    du, dv = x / 2, y / 2

    def size(k):
        return 0.35 + 0.55 * (1 - (1 - k / (frames - 1)) ** 2)

    burst = np.zeros((cell * puffs, cell * frames, 2))
    motion = np.full((cell * puffs, cell * frames, 2), 0.5)
    for row in range(puffs):
        noise = norm(fbm(256, 4, 5, rng("burst%d" % row)))
        for k in range(frames):
            t, s = k / (frames - 1), size(k)
            n = sample_wrap(noise, (x / s * 0.5 + 0.5) % 1, (y / s * 0.5 + 0.5) % 1)
            # a lumpy rim, and holes opening as it thins
            body = np.clip(1 - r / s * (0.8 + 0.4 * n), 0, 1) ** 0.6
            erode = smoothstep(0.5 * t - 0.1, 0.35 + 0.45 * t, n)
            alpha = np.clip(body * (0.35 + 0.65 * erode) * 1.6, 0, 1) * (1 - 0.55 * t)
            shade = np.clip(0.45 + 0.5 * n - 0.15 * y, 0, 1)
            rows, cols = slice(row * cell, (row + 1) * cell), slice(k * cell, (k + 1) * cell)
            burst[rows, cols] = np.stack([shade, alpha], -1)
            if k < frames - 1:
                grow = size(k + 1) / s - 1
                motion[rows, cols] = np.stack([0.5 + 0.5 * grow * du, 0.5 + 0.5 * grow * dv], -1)
    save("smoke_burst.png", burst[..., 0], burst[..., 0], burst[..., 0], burst[..., 1])
    save("smoke_burst_mv.png", motion[..., 0], motion[..., 1], 0.5, 1.0)

    # the inspector's transparency backdrop
    checker = np.array([[0.34, 0.22], [0.22, 0.34]])
    (OUT / "ui").mkdir(exist_ok=True)
    save("ui/checker.png", checker, checker, checker, 1.0)


if __name__ == "__main__":
    main()
