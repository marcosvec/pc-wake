// PC Wake control page. No secrets in this file: token, passphrase and gist id
// live only in this browser's localStorage (set via the form or a #setup= link).
const LS = 'pcwake.cfg';
const $ = (id) => document.getElementById(id);
let statusEtag = null;
let statusCache = null;

function load() { try { return JSON.parse(localStorage.getItem(LS)); } catch { return null; } }
function store(c) { try { localStorage.setItem(LS, JSON.stringify(c)); } catch {} }

function readForm() {
  const c = { token: $('token').value.trim(), secret: $('secret').value.trim(), gist_id: $('gist').value.trim() };
  if (!c.token || !c.secret || !c.gist_id) return null;
  return c;
}

// One-time setup link: #setup=<base64url json>. Saved, then wiped from the URL.
if (location.hash.startsWith('#setup=')) {
  try {
    const c = JSON.parse(b64urlDecode(location.hash.slice(7)));
    if (c.token && c.secret && c.gist_id) store(c);
    else alert('Setup link invalid');
  } catch (e) { alert('Setup link invalid'); }
  history.replaceState(null, '', location.pathname + location.search);
}

function fillForm() {
  const c = load() || {};
  $('token').value = c.token || '';
  $('secret').value = c.secret || '';
  $('gist').value = c.gist_id || '';
  $('setup').open = !(c.token && c.secret && c.gist_id);
}

function authHeaders(c) {
  return {
    Authorization: `Bearer ${c.token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
}

function githubError(status) {
  if (status === 401) return 'Token expired or revoked. Make a new one, then redo setup.';
  if (status === 403) return 'Token cannot read or write this gist.';
  if (status === 404) return 'Gist not found, or the token lost gist access.';
  if (status === 429) return 'GitHub rate limit. Wait a few minutes.';
  return `GitHub ${status}`;
}

async function api(method, body) {
  const c = load();
  if (!c) throw new Error('Not set up on this device');
  const headers = authHeaders(c);
  if (body) headers['Content-Type'] = 'application/json';
  const r = await fetch(`https://api.github.com/gists/${encodeURIComponent(c.gist_id)}`, {
    method, cache: 'no-store', headers, body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(githubError(r.status));
  return r.json();
}

async function readStatus() {
  const c = load();
  if (!c) throw new Error('Not set up on this device');
  const headers = authHeaders(c);
  if (statusEtag) headers['If-None-Match'] = statusEtag;
  const r = await fetch(`https://api.github.com/gists/${encodeURIComponent(c.gist_id)}`, {
    method: 'GET', cache: 'no-store', headers,
  });
  if (r.status === 304 && statusCache) return statusCache;
  if (!r.ok) throw new Error(githubError(r.status));
  const etag = r.headers.get('ETag');
  if (etag) statusEtag = etag;
  const g = await r.json();
  const file = g.files && g.files['status.json'];
  if (!file || typeof file.content !== 'string' || !file.content.trim()) {
    throw new Error('status.json is missing from the gist');
  }
  statusCache = JSON.parse(file.content);
  return statusCache;
}

function ago(ms) { const s = Math.round((Date.now() - ms) / 1000); if (s < 60) return `${s}s ago`; const m = Math.round(s / 60); return m < 120 ? `${m} min ago` : `${Math.round(m / 60)} h ago`; }

function setPill(id, cls, text) {
  const el = $(id);
  el.className = cls;
  el.replaceChildren();
  const dot = document.createElement('i');
  dot.className = 'dot';
  el.appendChild(dot);
  el.appendChild(document.createTextNode(text));
}

function renderUnset() { setPill('relay', 'warn', 'not set up'); setPill('pc', '', 'unknown'); }

function render(s) {
  if (!s || !s.relay_seen) { setPill('relay', 'bad', 'never seen'); setPill('pc', '', 'unknown'); return; }
  const age = Date.now() - s.relay_seen;
  setPill('relay', age < 20 * 60e3 ? 'ok' : 'bad', `seen ${ago(s.relay_seen)}`);
  setPill('pc', s.pc === 'awake' ? 'ok' : 'warn', s.pc || 'unknown');
}

async function refresh() {
  if (!load()) { renderUnset(); return; }
  try { render(await readStatus()); }
  catch (e) { setPill('relay', 'bad', e.message); setPill('pc', '', 'unknown'); }
}

function randomNonce() {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, '0');
  return out;
}

async function wake() {
  const c = load();
  if (!c) { $('setup').open = true; $('result').textContent = 'Set up this device first.'; return; }
  if (!window.crypto || !crypto.subtle) {
    $('result').textContent = 'Open this page over HTTPS. Signing needs a secure page.';
    return;
  }
  $('wake').disabled = true; $('result').textContent = 'Sending signed command...';
  try {
    const nonce = randomNonce();
    const ts = Date.now();
    const command = await buildWakeCommand(c.secret, ts, nonce);
    await api('PATCH', { files: { 'command.json': { content: JSON.stringify(command) } } });
    statusEtag = null;
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
$('save').onclick = () => {
  const c = readForm();
  if (!c) { $('result').textContent = 'Token, secret, and gist ID are all required.'; return; }
  store(c);
  $('setup').open = false;
  $('result').textContent = '';
  refresh();
};
$('forget').onclick = () => { localStorage.removeItem(LS); fillForm(); $('qr').innerHTML = ''; statusEtag = null; statusCache = null; refresh(); };
$('showqr').onclick = () => {
  const c = readForm();
  if (!c) { $('result').textContent = 'Add the token, secret, and gist ID first.'; return; }
  store(c);
  const link = `${location.origin}${location.pathname}${location.search}#setup=${b64urlEncode(JSON.stringify(c))}`;
  const qr = qrcode(0, 'L'); qr.addData(link); qr.make();
  $('qr').innerHTML = qr.createImgTag(5, 8);
};
fillForm();
refresh();
setInterval(refresh, 15000);
