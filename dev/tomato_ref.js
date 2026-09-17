// 小番茄官网混淆逻辑的原文复刻（函数 z/p 与 loop 均取自官网内联脚本），用于兼容性对拍。
// 用法: node tomato_ref.js in.rgba out.rgba WIDTH HEIGHT
const fs = require('fs');

function z(t, n) { const e = []; return t >= n ? p(0, 0, t, 0, 0, n, e) : p(0, 0, 0, n, t, 0, e), e }
function p(t, n, e, o, c, a, r) {
    const m = Math.abs(e + o), l = Math.abs(c + a), u = Math.sign(e), d = Math.sign(o), L = Math.sign(c), s = Math.sign(a);
    if (l === 1) { for (let M = 0; M < m; M++) r.push([t, n]), t += u, n += d; return }
    if (m === 1) { for (let M = 0; M < l; M++) r.push([t, n]), t += L, n += s; return }
    let h = Math.floor(e / 2), g = Math.floor(o / 2), i = Math.floor(c / 2), f = Math.floor(a / 2);
    const S = Math.abs(h + g), _ = Math.abs(i + f);
    2 * m > 3 * l ? (S % 2 && m > 2 && (h += u, g += d), p(t, n, h, g, c, a, r), p(t + h, n + g, e - h, o - g, c, a, r))
        : (_ % 2 && l > 2 && (i += L, f += s), p(t, n, i, f, h, g, r), p(t + i, n + f, e, o, c - i, a - f, r),
            p(t + (e - u) + (i - L), n + (o - d) + (f - s), -i, -f, -(e - h), -(o - g), r))
}

const args = process.argv.slice(2);
const [inPath, outPath, W, H, mode] = args;
const decrypt = mode === 'dec';
const c = parseInt(W, 10), a = parseInt(H, 10);
const m = { data: fs.readFileSync(inPath) };
const u = z(c, a), d = c * a, L = Math.round((Math.sqrt(5) - 1) / 2 * d);
const out = Buffer.alloc(d * 4);
for (let s = 0; s < d; s++) {
    const h = u[s], g = u[(s + L) % d];
    const i = 4 * (h[0] + h[1] * c), f = 4 * (g[0] + g[1] * c);
    if (decrypt) m.data.copy(out, i, f, f + 4);  // 官网 dec 分支：out[h] = src[g]
    else m.data.copy(out, f, i, i + 4);          // 官网 enc 分支：out[g] = src[h]
}
fs.writeFileSync(outPath, out);
console.log('tomato_ref done, w=' + c + ' h=' + a + ' offset=' + L);
