const fs = require('fs'), path = require('path'), z = require('zlib');
const root = 'C:/Users/HONOR/.dsh/sessions';
let files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (p.endsWith('.zstd')) files.push({ p, t: fs.statSync(p).mtimeMs });
  }
})(root);
files.sort((a, b) => b.t - a.t);
const magic = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
for (const f of files.slice(0, 6)) {
  const b = fs.readFileSync(f.p);
  let offs = [], i = 0;
  while ((i = b.indexOf(magic, i)) !== -1) { offs.push(i); i += 4; }
  let out = [];
  for (const o of offs) { try { out.push(z.zstdDecompressSync(b.subarray(o)).toString('utf8')); } catch (e) {} }
  const t = out.join('\n');
  const dfa = (t.match(/danger-full-access/g) || []).length;
  const ww = (t.match(/workspace-write/g) || []).length;
  console.log(new Date(f.t).toISOString(), path.basename(path.dirname(f.p)), 'DFA=' + dfa, 'WW=' + ww);
  const idx = t.indexOf('danger-full-access');
  if (idx >= 0) console.log('   ctx:', t.slice(Math.max(0, idx - 260), idx + 120).replace(/\s+/g, ' '));
}
