/*
 * 零依赖软件渲染器：把 ModelInscriptionTable 的方块模型渲染成封面图
 *
 *   node tools/render_cover.js                       默认 1600x1200，yaw 155° / pitch 25°
 *   node tools/render_cover.js 1200 1200             自定义尺寸
 *   node tools/render_cover.js 1600 1200 200 10      自定义尺寸 + 机位（角度制）
 *
 * 产物：docs/cover.png（透明背景 + 接触阴影 + 铭文辉光，可叠到任意底色上）
 *
 * 实现要点：
 *   - 模型坐标、面亮度、UV 与 ModelInscriptionTable 完全一致（1 像素 = 1/16 方块），
 *     所以渲染结果和游戏里看到的是同一个形状、同一套贴图；
 *   - 正交投影 + 深度缓冲 + 2x 超采样，贴图用最近邻采样（保持像素风）；
 *   - 铭文面（TILE_RUNE）单独做一层泛光，叠在结果上。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT_DIR = path.join(__dirname, '..', 'docs');
const ATLAS = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets',
    'inscriptiontable', 'textures', 'blocks', 'inscription_table.png');

const W = parseInt(process.argv[2], 10) || 1600;
const H = parseInt(process.argv[3], 10) || 1200;
const SS = 3;                 // 超采样倍数
const YAW = (parseFloat(process.argv[4]) || 155) * Math.PI / 180;
const PITCH = (parseFloat(process.argv[5]) || 25) * Math.PI / 180;

// ---------------------------------------------------------------- PNG 编解码
const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        table[n] = c;
    }
    return table;
})();

function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

function pngChunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
}

function savePng(file, im) {
    const stride = im.w * 4;
    const raw = Buffer.alloc((stride + 1) * im.h);
    for (let y = 0; y < im.h; y++) {
        raw[y * (stride + 1)] = 0;
        Buffer.from(im.px.subarray(y * stride, (y + 1) * stride)).copy(raw, y * (stride + 1) + 1);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(im.w, 0);
    ihdr.writeUInt32BE(im.h, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    const png = Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        pngChunk('IHDR', ihdr),
        pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        pngChunk('IEND', Buffer.alloc(0))
    ]);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, png);
    console.log('  ' + file + '  ' + im.w + 'x' + im.h);
}

function paeth(a, b, c) {
    const p = a + b - c;
    const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    return pb <= pc ? b : c;
}

function readPng(file) {
    const buf = fs.readFileSync(file);
    let pos = 8, w = 0, h = 0, colorType = 0, bitDepth = 8;
    const idat = [];
    while (pos + 8 <= buf.length) {
        const len = buf.readUInt32BE(pos);
        const type = buf.toString('ascii', pos + 4, pos + 8);
        const data = buf.subarray(pos + 8, pos + 8 + len);
        if (type === 'IHDR') {
            w = data.readUInt32BE(0);
            h = data.readUInt32BE(4);
            bitDepth = data[8];
            colorType = data[9];
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            break;
        }
        pos += 12 + len;
    }
    if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) {
        throw new Error('只支持 8bit RGB/RGBA 的 PNG: ' + file);
    }
    const bpp = colorType === 6 ? 4 : 3;
    const stride = w * bpp;
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const data = Buffer.alloc(w * h * 4);
    let prev = Buffer.alloc(stride);
    for (let y = 0; y < h; y++) {
        const filter = raw[y * (stride + 1)];
        const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
        for (let i = 0; i < stride; i++) {
            const a = i >= bpp ? line[i - bpp] : 0;
            const b = prev[i];
            const c = i >= bpp ? prev[i - bpp] : 0;
            if (filter === 1) line[i] = (line[i] + a) & 255;
            else if (filter === 2) line[i] = (line[i] + b) & 255;
            else if (filter === 3) line[i] = (line[i] + ((a + b) >> 1)) & 255;
            else if (filter === 4) line[i] = (line[i] + paeth(a, b, c)) & 255;
        }
        for (let x = 0; x < w; x++) {
            const s = x * bpp, d = (y * w + x) * 4;
            data[d] = line[s];
            data[d + 1] = line[s + 1];
            data[d + 2] = line[s + 2];
            data[d + 3] = bpp === 4 ? line[s + 3] : 255;
        }
        prev = line;
    }
    return { w, h, data };
}

// ---------------------------------------------------------------- 向量
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0]
];
function norm(a) {
    const len = Math.sqrt(dot(a, a)) || 1;
    return [a[0] / len, a[1] / len, a[2] / len];
}

// ---------------------------------------------------------------- 模型
// 与 ModelInscriptionTable 保持一致：1 像素 = 1/16 方块，面亮度也照抄
const PX = 1 / 16;
const TILE_STONE = 0;
const TILE_POLISHED = 1;
const TILE_WOOD = 2;
const TILE_RUNE = 3;

function pushQuad(out, xf, a, b, c, d, tile, brightness) {
    const u0 = (tile & 1) * 0.5;
    const v0 = (tile >> 1) * 0.5;
    const u1 = u0 + 0.5;
    const v1 = v0 + 0.5;
    out.push({
        v: [xf(a), xf(b), xf(c), xf(d)],
        uv: [[u0, v1], [u0, v0], [u1, v0], [u1, v1]],
        b: brightness,
        rune: tile === TILE_RUNE
    });
}

function pushBox(out, xf, box, side, top, bottom, front, bright) {
    const B = bright || {};
    const ax = box[0] * PX, ay = box[1] * PX, az = box[2] * PX;
    const bx = box[3] * PX, by = box[4] * PX, bz = box[5] * PX;
    const pick = (k, d) => (B[k] === undefined ? d : B[k]);

    // 上 / 下 / 北(-z) / 南(+z) / 西(-x) / 东(+x)
    pushQuad(out, xf, [ax, by, az], [ax, by, bz], [bx, by, bz], [bx, by, az],
        top, pick('top', 1.00));
    pushQuad(out, xf, [ax, ay, az], [bx, ay, az], [bx, ay, bz], [ax, ay, bz],
        bottom, pick('bottom', 0.50));
    pushQuad(out, xf, [bx, ay, az], [ax, ay, az], [ax, by, az], [bx, by, az],
        front, pick('front', 0.88));
    pushQuad(out, xf, [ax, ay, bz], [bx, ay, bz], [bx, by, bz], [ax, by, bz],
        side, pick('side', 0.80));
    pushQuad(out, xf, [ax, ay, az], [ax, ay, bz], [ax, by, bz], [ax, by, az],
        side, pick('west', 0.62));
    pushQuad(out, xf, [bx, ay, bz], [bx, ay, az], [bx, by, az], [bx, by, bz],
        side, pick('east', 0.68));
}

function buildModel() {
    const out = [];
    const id = p => p;

    // 石基 / 木质束腰 / 中柱
    pushBox(out, id, [1, 0, 1, 15, 3, 15], TILE_STONE, TILE_STONE, TILE_STONE, TILE_STONE);
    pushBox(out, id, [2, 3, 2, 14, 4, 14], TILE_WOOD, TILE_WOOD, TILE_WOOD, TILE_WOOD);
    pushBox(out, id, [5, 4, 5, 11, 8, 11], TILE_WOOD, TILE_WOOD, TILE_WOOD, TILE_WOOD);
    // 背板：朝向玩家的 -Z 面是铭文面板
    pushBox(out, id, [2, 8, 13, 14, 15, 14], TILE_WOOD, TILE_WOOD, TILE_WOOD, TILE_RUNE);

    // 倾斜 -22° 的书写台面 + 台面上的铭文纸
    const tilt = -22 * Math.PI / 180;
    const cs = Math.cos(tilt);
    const sn = Math.sin(tilt);
    const xf = p => [
        p[0] + 0.5,
        0.5 + (p[1] * cs - p[2] * sn),
        0.46 + (p[1] * sn + p[2] * cs)
    ];
    pushBox(out, xf, [-7, 0, -6, 7, 1.5, 6], TILE_WOOD, TILE_POLISHED, TILE_WOOD, TILE_POLISHED);
    pushBox(out, xf, [-4, 1.5, -3.5, 4, 2.0, 3.5], TILE_POLISHED, TILE_RUNE, TILE_POLISHED,
        TILE_POLISHED, { top: 1.0, bottom: 0.90, front: 0.90, side: 0.85, west: 0.70, east: 0.75 });

    return out;
}

// ---------------------------------------------------------------- 渲染
function main() {
    const atlas = readPng(ATLAS);
    const quads = buildModel();

    // 包围盒中心（画面构图以它为原点）
    let mn = [1e9, 1e9, 1e9];
    let mx = [-1e9, -1e9, -1e9];
    for (const q of quads) {
        for (const p of q.v) {
            for (let i = 0; i < 3; i++) {
                mn[i] = Math.min(mn[i], p[i]);
                mx[i] = Math.max(mx[i], p[i]);
            }
        }
    }
    const center = [(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2];

    // 正交相机：从 -Z +X 的方向俯视，正好能同时看到铭文面板、右侧木纹和倾斜台面
    const dirToCam = [
        Math.sin(YAW) * Math.cos(PITCH),
        Math.sin(PITCH),
        Math.cos(YAW) * Math.cos(PITCH)
    ];
    const fwd = norm([-dirToCam[0], -dirToCam[1], -dirToCam[2]]);
    const right = norm(cross(fwd, [0, 1, 0]));
    const up = cross(right, fwd);

    const project = p => {
        const rel = sub(p, center);
        return [dot(rel, right), -dot(rel, up)];
    };
    const depthOf = p => dot(p, fwd);

    // 先算投影范围，再等比缩放居中
    let minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
    for (const q of quads) {
        for (const p of q.v) {
            const s = project(p);
            minX = Math.min(minX, s[0]);
            maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]);
            maxY = Math.max(maxY, s[1]);
        }
    }
    const fit = Math.min((W * 0.84) / (maxX - minX), (H * 0.80) / (maxY - minY));
    const offX = W / 2 - ((minX + maxX) / 2) * fit;
    const offY = H * 0.52 - ((minY + maxY) / 2) * fit;

    const SW = W * SS;
    const SH = H * SS;
    const color = new Uint8ClampedArray(SW * SH * 4); // 预乘 alpha
    const depth = new Float32Array(SW * SH).fill(Infinity);

    const tri = (p0, p1, p2, uv0, uv1, uv2, bright) => {
        const x0 = p0[0] * SS, y0 = p0[1] * SS;
        const x1 = p1[0] * SS, y1 = p1[1] * SS;
        const x2 = p2[0] * SS, y2 = p2[1] * SS;
        const det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(det) < 1e-12) return;

        const xa = Math.max(0, Math.floor(Math.min(x0, x1, x2)));
        const xb = Math.min(SW - 1, Math.ceil(Math.max(x0, x1, x2)));
        const ya = Math.max(0, Math.floor(Math.min(y0, y1, y2)));
        const yb = Math.min(SH - 1, Math.ceil(Math.max(y0, y1, y2)));
        if (xa > xb || ya > yb) return;

        const d0 = depthOf(p0[3]), d1 = depthOf(p1[3]), d2 = depthOf(p2[3]);
        const inv = 1 / det;

        for (let y = ya; y <= yb; y++) {
            const py = y + 0.5;
            for (let x = xa; x <= xb; x++) {
                const px = x + 0.5;
                const l0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) * inv;
                const l1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) * inv;
                const l2 = 1 - l0 - l1;
                if (l0 < -1e-6 || l1 < -1e-6 || l2 < -1e-6) continue;

                const d = l0 * d0 + l1 * d1 + l2 * d2;
                const idx = y * SW + x;
                if (d >= depth[idx]) continue;
                depth[idx] = d;

                const u = l0 * uv0[0] + l1 * uv1[0] + l2 * uv2[0];
                const v = l0 * uv0[1] + l1 * uv1[1] + l2 * uv2[1];
                const tx = Math.min(atlas.w - 1, Math.max(0, Math.floor(u * atlas.w)));
                const ty = Math.min(atlas.h - 1, Math.max(0, Math.floor(v * atlas.h)));
                const ti = (ty * atlas.w + tx) * 4;
                const o = idx * 4;
                color[o] = atlas.data[ti] * bright;
                color[o + 1] = atlas.data[ti + 1] * bright;
                color[o + 2] = atlas.data[ti + 2] * bright;
                color[o + 3] = 255;
            }
        }
    };

    for (const q of quads) {
        const s = q.v.map(p => {
            const xy = project(p);
            const p4 = [xy[0] * fit + offX, xy[1] * fit + offY, 0, p];
            p4[3] = p;
            return p4;
        });
        tri(s[0], s[1], s[2], q.uv[0], q.uv[1], q.uv[2], q.b);
        tri(s[0], s[2], s[3], q.uv[0], q.uv[2], q.uv[3], q.b);
    }

    // ------------------------------------------------------------ 超采样降采样
    const img = { w: W, h: H, px: new Uint8ClampedArray(W * H * 4) };
    for (let y = 0; y < H; y++) {
        for (let x = 0; x < W; x++) {
            let r = 0, g = 0, b = 0, a = 0;
            for (let sy = 0; sy < SS; sy++) {
                for (let sx = 0; sx < SS; sx++) {
                    const i = ((y * SS + sy) * SW + (x * SS + sx)) * 4;
                    r += color[i];
                    g += color[i + 1];
                    b += color[i + 2];
                    a += color[i + 3];
                }
            }
            const n = SS * SS;
            const o = (y * W + x) * 4;
            if (a > 0) {
                img.px[o] = (r / a) * 255;
                img.px[o + 1] = (g / a) * 255;
                img.px[o + 2] = (b / a) * 255;
                img.px[o + 3] = a / n;
            }
        }
    }

    // ------------------------------------------------------------ 铭文辉光
    const gw = Math.ceil(W / 4);
    const gh = Math.ceil(H / 4);
    let glow = new Float32Array(gw * gh);
    for (let y = 0; y < H; y++) {
        for (let x = 0; x < W; x++) {
            const o = (y * W + x) * 4;
            if (img.px[o + 3] < 200) continue;
            const r = img.px[o], g = img.px[o + 1], b = img.px[o + 2];
            if (g > 120 && b > 130 && g > r + 40) {
                glow[((y >> 2) * gw) + (x >> 2)] += (g - 120) / 135;
            }
        }
    }
    glow = blur(blur(glow, gw, gh, 5), gw, gh, 5);

    for (let y = 0; y < H; y++) {
        for (let x = 0; x < W; x++) {
            const o = (y * W + x) * 4;
            const a = img.px[o + 3] / 255;
            if (a <= 0) continue;
            const gx = x / 4, gy = y / 4;
            const x0 = Math.min(gw - 1, Math.floor(gx)), y0 = Math.min(gh - 1, Math.floor(gy));
            const x1 = Math.min(gw - 1, x0 + 1), y1 = Math.min(gh - 1, y0 + 1);
            const fx = gx - x0, fy = gy - y0;
            const gv = (glow[y0 * gw + x0] * (1 - fx) + glow[y0 * gw + x1] * fx) * (1 - fy)
                + (glow[y1 * gw + x0] * (1 - fx) + glow[y1 * gw + x1] * fx) * fy;
            if (gv <= 0.001) continue;
            const k = Math.min(0.85, gv * 0.22);
            img.px[o] = Math.min(255, img.px[o] + 44 * k);
            img.px[o + 1] = Math.min(255, img.px[o + 1] + 100 * k);
            img.px[o + 2] = Math.min(255, img.px[o + 2] + 118 * k);
        }
    }

    // 接触阴影：石基在地面上的投影四边形，模糊后当 alpha 用（比椭圆更贴合形状）
    const base = [[1, 1], [15, 1], [15, 15], [1, 15]].map(([px, pz]) => {
        const s = project([px * PX, 0, pz * PX]);
        return [s[0] * fit + offX, s[1] * fit + offY];
    });
    let bcx = 0, bcy = 0;
    for (const c of base) { bcx += c[0] / 4; bcy += c[1] / 4; }
    const shadowQuad = base.map(c => [bcx + (c[0] - bcx) * 1.06, bcy + (c[1] - bcy) * 1.06]);

    const sw = Math.ceil(W / 4);
    const sh = Math.ceil(H / 4);
    let mask = new Float32Array(sw * sh);
    for (let y = 0; y < sh; y++) {
        for (let x = 0; x < sw; x++) {
            if (insideQuad(shadowQuad, x * 4 + 2, y * 4 + 2)) mask[y * sw + x] = 1;
        }
    }
    mask = blur(blur(mask, sw, sh, 4), sw, sh, 4);

    const out = { w: W, h: H, px: new Uint8ClampedArray(W * H * 4) };
    for (let y = 0; y < H; y++) {
        for (let x = 0; x < W; x++) {
            const o = (y * W + x) * 4;
            const sa = 165 * bilinear(mask, sw, sh, x / 4, y / 4);
            const sr = 15, sg = 18, sb = 20;   // 冷灰的投影
            const ba = img.px[o + 3] / 255;
            const oa = ba + (sa / 255) * (1 - ba);
            if (oa <= 0) continue;
            out.px[o] = (img.px[o] * ba + sr * (sa / 255) * (1 - ba)) / oa;
            out.px[o + 1] = (img.px[o + 1] * ba + sg * (sa / 255) * (1 - ba)) / oa;
            out.px[o + 2] = (img.px[o + 2] * ba + sb * (sa / 255) * (1 - ba)) / oa;
            out.px[o + 3] = oa * 255;
        }
    }

    savePng(path.join(OUT_DIR, 'cover.png'), out);
    console.log('done');
}

/** 凸四边形内外测试（顶点顺时针/逆时针都行） */
function insideQuad(quad, px, py) {
    let sign = 0;
    for (let i = 0; i < 4; i++) {
        const a = quad[i], b = quad[(i + 1) % 4];
        const cr = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
        if (cr === 0) continue;
        const s = cr > 0 ? 1 : -1;
        if (sign === 0) sign = s;
        else if (s !== sign) return false;
    }
    return true;
}

function bilinear(buf, w, h, gx, gy) {
    const x0 = Math.min(w - 1, Math.max(0, Math.floor(gx)));
    const y0 = Math.min(h - 1, Math.max(0, Math.floor(gy)));
    const x1 = Math.min(w - 1, x0 + 1);
    const y1 = Math.min(h - 1, y0 + 1);
    const fx = gx - x0, fy = gy - y0;
    return (buf[y0 * w + x0] * (1 - fx) + buf[y0 * w + x1] * fx) * (1 - fy)
        + (buf[y1 * w + x0] * (1 - fx) + buf[y1 * w + x1] * fx) * fy;
}

function blur(src, w, h, radius) {
    const tmp = new Float32Array(w * h);
    const dst = new Float32Array(w * h);
    const span = radius * 2 + 1;

    for (let y = 0; y < h; y++) {
        let sum = 0;
        for (let x = -radius; x <= radius; x++) sum += src[y * w + clamp(x, 0, w - 1)];
        for (let x = 0; x < w; x++) {
            tmp[y * w + x] = sum / span;
            sum += src[y * w + clamp(x + radius + 1, 0, w - 1)];
            sum -= src[y * w + clamp(x - radius, 0, w - 1)];
        }
    }
    for (let x = 0; x < w; x++) {
        let sum = 0;
        for (let y = -radius; y <= radius; y++) sum += tmp[clamp(y, 0, h - 1) * w + x];
        for (let y = 0; y < h; y++) {
            dst[y * w + x] = sum / span;
            sum += tmp[clamp(y + radius + 1, 0, h - 1) * w + x];
            sum -= tmp[clamp(y - radius, 0, h - 1) * w + x];
        }
    }
    return dst;
}

function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

main();
