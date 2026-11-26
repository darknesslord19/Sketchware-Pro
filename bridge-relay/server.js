'use strict';
// Sketchware köprü sunucusu.
// Claude --MCP (HTTP POST /mcp/<TOKEN>)--> bu sunucu <--uzun yoklama-- Sketchware uygulaması
// Bağımlılık yok, Node 18+.

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const TOKEN = process.env.TOKEN || '';
if (TOKEN.length < 24) {
  console.error('TOKEN ortam değişkeni en az 24 karakter olmalı.');
  process.exit(1);
}

const ONLINE_MS = 75000;
const queue = [];           // cihaza gidecek komutlar
const pending = new Map();  // id -> {resolve, timer}
let waiter = null;          // bekleyen yoklama isteği
let lastSeen = 0;

function same(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(obj === undefined ? '' : JSON.stringify(obj));
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > limit) { reject(new Error('Gövde çok büyük')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function dispatch() {
  if (waiter && queue.length) {
    const w = waiter; waiter = null; clearTimeout(w.timer);
    send(w.res, 200, queue.shift());
  }
}

function callDevice(action, args) {
  return new Promise((resolve, reject) => {
    if (Date.now() - lastSeen > ONLINE_MS) {
      reject(new Error('Sketchware bağlı değil. Uygulamada "Claude bağlantısı" açık olmalı.'));
      return;
    }
    const id = crypto.randomBytes(8).toString('hex');
    const timer = setTimeout(() => {
      pending.delete(id);
      // Süresi dolan komut sonradan çalışmasın.
      const i = queue.findIndex(c => c.id === id);
      if (i >= 0) queue.splice(i, 1);
      reject(new Error('Cihaz zamanında yanıt vermedi.'));
    }, 40000);
    pending.set(id, { resolve, timer });
    queue.push({ id, action, args });
    dispatch();
  });
}

const projectId = { type: 'string', description: 'Proje numarası (sc_id), ör. "601"' };
const area = { type: 'string', enum: ['data', 'generated'],
  description: 'data: projenin kayıtlı verileri. generated: son derlemede üretilen kaynaklar.' };

const TOOLS = [
  { name: 'sk_list_projects', description: 'Telefondaki Sketchware projelerini listeler.',
    inputSchema: { type: 'object', properties: {} } },
  { name: 'sk_list_files', description: 'Bir projedeki klasörün içeriğini listeler.',
    inputSchema: { type: 'object', properties: { projectId, area, path: { type: 'string' } }, required: ['projectId', 'area'] } },
  { name: 'sk_read_file', description: 'Bir proje dosyasını metin olarak okur. view, logic, file, library ve resource dosyalarının şifresi çözülür. Uzun dosyalar offset ile parça parça okunur.',
    inputSchema: { type: 'object', properties: { projectId, area, path: { type: 'string' }, offset: { type: 'number' } }, required: ['projectId', 'area', 'path'] } },
  { name: 'sk_get_compile_log', description: 'Projenin kaydedilmiş son derleme hatasını döndürür.',
    inputSchema: { type: 'object', properties: { projectId, offset: { type: 'number' } }, required: ['projectId'] } }
];

const ACTION = {
  sk_list_projects: 'list_projects', sk_list_files: 'list_files',
  sk_read_file: 'read_file', sk_get_compile_log: 'compile_log'
};

async function callTool(name, args) {
  if (!Object.prototype.hasOwnProperty.call(ACTION, name)) throw new Error('Bilinmeyen araç: ' + name);
  const r = await callDevice(ACTION[name], args || {});
  if (!r.ok) return { content: [{ type: 'text', text: 'Hata: ' + (r.error || 'bilinmiyor') }], isError: true };
  return { content: [{ type: 'text', text: String(r.data) }] };
}

async function handleRpc(m) {
  if (!m || typeof m !== 'object' || m.id === undefined) return null; // bildirim
  const id = m.id;
  const ok = result => ({ jsonrpc: '2.0', id, result });
  const fail = (code, message) => ({ jsonrpc: '2.0', id, error: { code, message } });
  try {
    switch (m.method) {
      case 'initialize':
        return ok({ protocolVersion: (m.params && m.params.protocolVersion) || '2025-03-26',
          capabilities: { tools: {} }, serverInfo: { name: 'sketchware-bridge', version: '0.1.0' } });
      case 'ping': return ok({});
      case 'tools/list': return ok({ tools: TOOLS });
      case 'tools/call': return ok(await callTool(m.params && m.params.name, m.params && m.params.arguments));
      default: return fail(-32601, 'Yöntem yok: ' + m.method);
    }
  } catch (e) {
    return ok({ content: [{ type: 'text', text: 'Hata: ' + e.message }], isError: true });
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const path = req.url.split('?')[0];
    const parts = path.split('/').filter(Boolean);
    if (path === '/healthz') return send(res, 200, { ok: true });

    if (parts[0] === 'mcp' && parts.length === 2 && same(parts[1], TOKEN)) {
      if (req.method !== 'POST') return send(res, 405, { error: 'Yalnızca POST' });
      const raw = await readBody(req, 1 << 20);
      let msg;
      try { msg = JSON.parse(raw); } catch (e) {
        return send(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Geçersiz JSON' } });
      }
      if (Array.isArray(msg)) {
        const out = (await Promise.all(msg.map(handleRpc))).filter(Boolean);
        return out.length ? send(res, 200, out) : send(res, 202);
      }
      const out = await handleRpc(msg);
      return out ? send(res, 200, out) : send(res, 202);
    }

    if (parts[0] === 'device' && parts.length === 3 && same(parts[1], TOKEN)) {
      if (parts[2] === 'poll' && req.method === 'GET') {
        lastSeen = Date.now();
        if (waiter) { const old = waiter; waiter = null; clearTimeout(old.timer); send(old.res, 204); }
        if (queue.length) return send(res, 200, queue.shift());
        const timer = setTimeout(() => {
          if (waiter && waiter.res === res) waiter = null;
          lastSeen = Date.now();
          send(res, 204);
        }, 25000);
        waiter = { res, timer };
        res.on('close', () => { if (waiter && waiter.res === res) { clearTimeout(timer); waiter = null; } });
        return;
      }
      if (parts[2] === 'result' && req.method === 'POST') {
        lastSeen = Date.now();
        const body = JSON.parse(await readBody(req, 4 << 20));
        const p = pending.get(body.id);
        if (p) { clearTimeout(p.timer); pending.delete(body.id); p.resolve(body); }
        return send(res, 200, { ok: true });
      }
    }
    send(res, 404, { error: 'yok' });
  } catch (e) {
    if (!res.headersSent) send(res, 500, { error: 'sunucu hatası' });
  }
});

server.listen(PORT, () => console.log('Dinleniyor: ' + PORT));
