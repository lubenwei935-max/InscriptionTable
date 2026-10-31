/*
 * 零依赖贴图生成器：node tools/generate_textures.js
 * 生成：
 *   assets/inscriptiontable/textures/blocks/inscription_table.png      32x32 (2x2 图集)
 *   assets/inscriptiontable/textures/blocks/inscription_table_icon.png 16x16 (物品图标)
 *   assets/inscriptiontable/textures/gui/inscription_table.png         214x240
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------------------------------------------------------------- PNG 编码
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

function chunk(type, data) {
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
        raw[y * (stride + 1)] = 0; // filter: none
        Buffer.from(im.px.subarray(y * stride, (y + 1) * stride)).copy(raw, y * (stride + 1) + 1);
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(im.w, 0);
    ihdr.writeUInt32BE(im.h, 4);
    ihdr[8] = 8;  // bit depth
    ihdr[9] = 6;  // RGBA
    const png = Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0))
    ]);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, png);
    console.log('  ' + file + '  ' + im.w + 'x' + im.h);
}

// ---------------------------------------------------------------- 画布
function img(w, h, r, g, b, a) {
    const im = { w, h, px: new Uint8Array(w * h * 4) };
    if (r !== undefined) fill(im, 0, 0, w, h, r, g, b, a === undefined ? 255 : a);
    return im;
}

function setPx(im, x, y, r, g, b, a) {
    if (x < 0 || y < 0 || x >= im.w || y >= im.h) return;
    const i = (y * im.w + x) * 4;
    im.px[i] = r; im.px[i + 1] = g; im.px[i + 2] = b;
    im.px[i + 3] = a === undefined ? 255 : a;
}

function blendPx(im, x, y, r, g, b, a) {
    if (x < 0 || y < 0 || x >= im.w || y >= im.h) return;
    const i = (y * im.w + x) * 4;
    const t = a / 255;
    im.px[i] = Math.round(im.px[i] * (1 - t) + r * t);
    im.px[i + 1] = Math.round(im.px[i + 1] * (1 - t) + g * t);
    im.px[i + 2] = Math.round(im.px[i + 2] * (1 - t) + b * t);
    im.px[i + 3] = 255;
}

function fill(im, x0, y0, x1, y1, r, g, b, a) {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) setPx(im, x, y, r, g, b, a);
}

function frameRect(im, x0, y0, x1, y1, r, g, b, a) {
    for (let x = x0; x < x1; x++) { setPx(im, x, y0, r, g, b, a); setPx(im, x, y1 - 1, r, g, b, a); }
    for (let y = y0; y < y1; y++) { setPx(im, x0, y, r, g, b, a); setPx(im, x1 - 1, y, r, g, b, a); }
}

function vstab(im, x0, x1, y0, y1, r, g, b, a) {
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) setPx(im, x, y, r, g, b, a);
}

// 确定性随机
function rng(seed) {
    let s = seed >>> 0;
    return function () {
        s = (s + 0x6D2B79F5) >>> 0;
        let t = s;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

function shade(c, delta) {
    return [
        Math.max(0, Math.min(255, c[0] + delta)),
        Math.max(0, Math.min(255, c[1] + delta)),
        Math.max(0, Math.min(255, c[2] + delta))
    ];
}

const OUT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'inscriptiontable', 'textures');

// ---------------------------------------------------------------- 方块图集 32x32
function buildAtlas() {
    const im = img(32, 32, 0, 0, 0, 255);
    const rand = rng(20261017);

    // 0: 石基 (0,0)
    const stone = [74, 79, 88];
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const n = Math.round((rand() - 0.5) * 12);
        const c = shade(stone, n);
        setPx(im, x, y, c[0], c[1], c[2], 255);
    }
    frameRect(im, 0, 0, 16, 16, 57, 62, 70, 255);
    frameRect(im, 1, 1, 15, 15, 88, 94, 104, 255);
    // 几道裂纹
    const cracks = [[4, 5, 9, 5], [9, 6, 9, 10], [5, 11, 8, 11], [3, 7, 3, 10], [11, 4, 13, 4]];
    for (const [x0, y0, x1, y1] of cracks) {
        for (let x = x0; x <= x1; x++) setPx(im, x, y0, 52, 56, 64, 255);
        for (let y = y0; y <= y1; y++) setPx(im, x0, y, 52, 56, 64, 255);
    }

    // 1: 抛光台面 (16,0)
    for (let y = 0; y < 16; y++) for (let x = 16; x < 32; x++) {
        const n = Math.round((rand() - 0.5) * 8);
        const c = shade([134, 142, 152], n - Math.round(y * 0.8));
        setPx(im, x, y, c[0], c[1], c[2], 255);
    }
    frameRect(im, 16, 0, 32, 16, 96, 104, 114, 255);
    frameRect(im, 17, 1, 31, 15, 152, 160, 170, 255);
    // 中心菱形徽记
    for (let y = 3; y <= 13; y++) {
        for (let x = 19; x <= 29; x++) {
            const d = Math.abs(x - 24) + Math.abs(y - 8);
            if (d <= 3) setPx(im, x, y, 108, 118, 130, 255);
            if (d <= 1) setPx(im, x, y, 168, 176, 186, 255);
        }
    }

    // 2: 深色木 (0,16)
    for (let y = 16; y < 32; y++) for (let x = 0; x < 16; x++) {
        const grain = Math.sin(x * 1.7) * 6 + Math.round((rand() - 0.5) * 6);
        const c = shade([74, 53, 39], grain);
        setPx(im, x, y, c[0], c[1], c[2], 255);
    }
    for (let x = 0; x < 16; x++) {
        setPx(im, x, 16, 48, 34, 25, 255);
        setPx(im, x, 31, 48, 34, 25, 255);
        setPx(im, x, 23, 61, 43, 31, 255);
        setPx(im, x, 27, 61, 43, 31, 255);
    }
    for (let x = 0; x < 16; x += 3) {
        setPx(im, x, 16, 46, 32, 23, 255);
        setPx(im, x, 17, 46, 32, 23, 255);
    }

    // 3: 发光铭文面板 (16,16)
    for (let y = 16; y < 32; y++) for (let x = 16; x < 32; x++) {
        const n = Math.round((rand() - 0.5) * 5);
        const c = shade([15, 21, 28], n);
        setPx(im, x, y, c[0], c[1], c[2], 255);
    }
    frameRect(im, 16, 16, 32, 32, 37, 50, 60, 255);
    frameRect(im, 17, 17, 31, 31, 26, 36, 45, 255);
    const runes = [
        [19, 19, 19, 27], [19, 19, 24, 19], [22, 22, 26, 22], [26, 22, 26, 27],
        [20, 25, 24, 25], [21, 28, 27, 28], [28, 19, 28, 24], [23, 19, 23, 21]
    ];
    for (const [x0, y0, x1, y1] of runes) {
        for (let x = x0; x <= x1; x++) {
            for (let y = y0; y <= y1; y++) setPx(im, x, y, 111, 224, 242, 255);
        }
        // 发光晕
        for (let x = x0 - 1; x <= x1 + 1; x++) {
            for (let y = y0 - 1; y <= y1 + 1; y++) {
                if (x >= x0 && x <= x1 && y >= y0 && y <= y1) continue;
                blendPx(im, x, y, 63, 168, 188, 70);
            }
        }
    }
    return im;
}

// ---------------------------------------------------------------- 物品图标 16x16
function buildIcon() {
    const im = img(16, 16, 0, 0, 0, 0);
    const rand = rng(7788);

    // 石基
    for (let y = 12; y < 16; y++) for (let x = 2; x < 14; x++) {
        const c = shade([74, 79, 88], Math.round((rand() - 0.5) * 10));
        setPx(im, x, y, c[0], c[1], c[2], 255);
    }
    // 两侧木柱
    vstab(im, 3, 5, 6, 12, 74, 53, 39, 255);
    vstab(im, 11, 13, 6, 12, 74, 53, 39, 255);
    // 倾斜台面
    for (let x = 3; x <= 12; x++) {
        const top = Math.round(10 - (x - 3) * 0.45);
        for (let y = top - 2; y <= top; y++) setPx(im, x, y, 134, 142, 152, 255);
        setPx(im, x, top - 2, 168, 176, 186, 255);
    }
    // 铭文条
    for (let x = 5; x <= 10; x++) {
        const top = Math.round(10 - (x - 3) * 0.45);
        setPx(im, x, top - 1, 111, 224, 242, 255);
        blendPx(im, x, top, 63, 168, 188, 90);
    }
    // 背板
    vstab(im, 12, 14, 2, 8, 58, 41, 30, 255);
    vstab(im, 2, 4, 2, 8, 58, 41, 30, 255);
    vstab(im, 3, 13, 2, 4, 74, 53, 39, 255);
    for (let x = 5; x <= 11; x++) setPx(im, x, 3, 63, 168, 188, 255);
    return im;
}

// ---------------------------------------------------------------- GUI 背景 214x240
// 尺寸与元素坐标必须和 GuiInscriptionTable / ContainerInscriptionTable 的常量一致：
//   标题 y=8 / 分隔线 y=24 / 标签 y=32 / 控件 y=46..64 / 按钮 y=70..90
//   箭头 (121,50)-(141,60) / 背包分隔线 y=150 / 背包 158..210 / 快捷栏 216..232
function buildGui() {
    const W = 214, H = 240;
    const im = img(W, H, 0, 0, 0, 255);

    // 竖渐变面板
    for (let y = 0; y < H; y++) {
        const t = y / (H - 1);
        const r = Math.round(35 * (1 - t) + 21 * t);
        const g = Math.round(42 * (1 - t) + 27 * t);
        const b = Math.round(50 * (1 - t) + 33 * t);
        for (let x = 0; x < W; x++) setPx(im, x, y, r, g, b, 255);
    }
    frameRect(im, 0, 0, W, H, 60, 71, 84, 255);
    frameRect(im, 1, 1, W - 1, H - 1, 32, 39, 47, 255);

    // 四角装饰
    const accent = [95, 211, 232];
    const corners = [[2, 2, 1, 1], [W - 3, 2, -1, 1], [2, H - 3, 1, -1], [W - 3, H - 3, -1, -1]];
    for (const [x, y, dx, dy] of corners) {
        for (let i = 0; i < 7; i++) {
            setPx(im, x + dx * i, y, accent[0], accent[1], accent[2], 200);
            setPx(im, x, y + dy * i, accent[0], accent[1], accent[2], 200);
        }
    }

    // 分隔线：标题下 / 背包上
    for (const dy of [24, 150]) {
        for (let x = 8; x < W - 8; x++) {
            setPx(im, x, dy, 44, 53, 63, 255);
            setPx(im, x, dy + 1, 56, 67, 78, 255);
        }
    }

    // 右箭头 (121,50)-(141,60)
    const bright = [111, 224, 242];
    const edge = [168, 246, 255];
    const dim = [62, 146, 163];
    // 箭杆
    for (let x = 121; x < 134; x++) {
        for (let y = 53; y < 57; y++) {
            const c = y === 53 ? edge : (y < 56 ? bright : dim);
            setPx(im, x, y, c[0], c[1], c[2], 255);
        }
    }
    // 箭头
    for (let i = 0; i <= 7; i++) {
        const x = 133 + i;
        for (let y = 50 + i; y <= 60 - i; y++) {
            const c = y === 50 + i ? edge : (y < 59 - i ? bright : dim);
            setPx(im, x, y, c[0], c[1], c[2], 255);
        }
    }

    return im;
}

const atlas = buildAtlas();
savePng(path.join(OUT, 'blocks', 'inscription_table.png'), atlas);
savePng(path.join(OUT, 'blocks', 'inscription_table_icon.png'), buildIcon());
savePng(path.join(OUT, 'gui', 'inscription_table.png'), buildGui());
console.log('done');
