// PC Wake control page. No secrets in this file: token, passphrase and gist id
// live only in this browser's localStorage (set via the form or a #setup= link).
const LS = 'pcwake.cfg';
const $ = (id) => document.getElementById(id);

function load() { try { return JSON.parse(localStorage.getItem(LS)); } catch { return null; } }
function store(c) { try { localStorage.setItem(LS, JSON.stringify(c)); } catch {} }
const b64u = { enc: (s) => btoa(unescape(encodeURIComponent(s))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''),
               dec: (s) => decodeURIComponent(escape(atob(s.replace(/-/g,'+').replace(/_/g,'/')))) };

// One-time setup link: #setup=<base64url json>. Saved, then wiped from the URL.
if (location.hash.startsWith('#setup=')) {
  try { const c = JSON.parse(b64u.dec(location.hash.slice(7))); if (c.token && c.secret && c.gist_id) { store(c); } }
  catch (e) { alert('Setup link invalid'); }
  history.replaceState(null, '', location.pathname);
}

function fillForm() { const c = load() || {}; $('token').value = c.token || ''; $('secret').value = c.secret || ''; $('gist').value = c.gist_id || ''; $('setup').open = !(c.token && c.secret && c.gist_id); }

async function api(method, body) {
  const c = load();
  const r = await fetch(`https://api.github.com/gists/${c.gist_id}`, {
    method, cache: 'no-store', body: body ? JSON.stringify(body) : undefined,
    headers: { Authorization: `Bearer ${c.token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' } });
  if (r.status === 401) throw new Error('Token expired or revoked. Make a new one, then redo setup.');
  if (r.status === 404) throw new Error('Gist not found, or the token lost gist access.');
  if (!r.ok) throw new Error(`GitHub ${r.status}`);
  return r.json();
}

async function readStatus() {
  const g = await api('GET');
  return JSON.parse(g.files['status.json'].content);
}

function ago(ms) { const s = Math.round((Date.now() - ms) / 1000); if (s < 60) return `${s}s ago`; const m = Math.round(s / 60); return m < 120 ? `${m} min ago` : `${Math.round(m / 60)} h ago`; }
function setPill(id, cls, text) { $(id).className = cls; $(id).innerHTML = `<i class="dot"></i>${text}`; }

function render(s) {
  if (!s || !s.relay_seen) { setPill('relay', 'bad', 'never seen'); setPill('pc', '', 'unknown'); return; }
  const age = Date.now() - s.relay_seen;
  setPill('relay', age < 20 * 60e3 ? 'ok' : 'bad', `seen ${ago(s.relay_seen)}`);
  setPill('pc', s.pc === 'awake' ? 'ok' : 'warn', s.pc);
}

async function refresh() { if (!load()) return; try { render(await readStatus()); } catch (e) { setPill('relay', 'bad', e.message); } }

async function hmacHex(secret, msg) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(msg));
  return [...new Uint8Array(sig)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function wake() {
  const c = load(); if (!c) { $('setup').open = true; return; }
  $('wake').disabled = true; $('result').textContent = 'Sending signed command...';
  try {
    const nonce = [...crypto.getRandomValues(new Uint8Array(8))].map(b => b.toString(16).padStart(2, '0')).join('');
    const ts = Date.now();
    const sig = await hmacHex(c.secret, `wake|${ts}|${nonce}`);
    await api('PATCH', { files: { 'command.json': { content: JSON.stringify({ cmd: { action: 'wake', ts, nonce, sig } }) } } });
    $('result').textContent = 'Sent. Waiting for the relay...';
    const t0 = Date.now();
    while (Date.now() - t0 < 120e3) {
      await new Promise(r => setTimeout(r, 3000));
      let s; try { s = await readStatus(); } catch { continue; }
      render(s);
      if (s.last && s.last.nonce === nonce) {
        const ok = s.last.result === 'awake' || s.last.result === 'already awake';
        $('result').textContent = ok ? `PC is ${s.last.result}${s.last.secs ? ` (${s.last.secs}s)` : ''}` : `Relay: ${s.last.result}`;
        return;
      }
      $('result').textContent = `Waiting for the relay... ${Math.round((Date.now() - t0) / 1000)}s`;
    }
    $('result').textContent = 'No answer from the relay in 2 min. Is the PC Wake app running on the TV?';
  } catch (e) { $('result').textContent = `Error: ${e.message}`; }
  finally { $('wake').disabled = false; }
}

$('wake').onclick = wake;
$('save').onclick = () => { store({ token: $('token').value.trim(), secret: $('secret').value.trim(), gist_id: $('gist').value.trim() }); $('setup').open = false; refresh(); };
$('forget').onclick = () => { localStorage.removeItem(LS); fillForm(); $('qr').innerHTML = ''; };
$('showqr').onclick = () => {
  const c = load(); if (!c) return;
  const link = `${location.origin}${location.pathname}#setup=${b64u.enc(JSON.stringify(c))}`;
  const qr = qrcode(0, 'L'); qr.addData(link); qr.make();
  $('qr').innerHTML = qr.createImgTag(5, 8);
};
fillForm(); refresh(); setInterval(refresh, 15000);