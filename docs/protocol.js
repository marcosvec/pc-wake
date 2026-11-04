// Shared with the relay (WakeProtocol.kt): the signed string is action|timestamp|nonce.
// This file has no secrets and does not touch the network.

function b64urlEncode(s) {
  return btoa(unescape(encodeURIComponent(s)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function b64urlDecode(s) {
  var pad = s.length % 4 === 0 ? '' : '='.repeat(4 - (s.length % 4));
  var b64 = s.replace(/-/g, '+').replace(/_/g, '/') + pad;
  return decodeURIComponent(escape(atob(b64)));
}

function wakeMessage(action, ts, nonce) {
  return action + '|' + ts + '|' + nonce;
}

async function hmacHex(secret, msg) {
  var enc = new TextEncoder();
  var key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  var sig = await crypto.subtle.sign('HMAC', key, enc.encode(msg));
  var bytes = new Uint8Array(sig);
  var out = '';
  for (var i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, '0');
  return out;
}

async function buildWakeCommand(secret, ts, nonce) {
  var action = 'wake';
  var sig = await hmacHex(secret, wakeMessage(action, ts, nonce));
  return { cmd: { action: action, ts: ts, nonce: nonce, sig: sig } };
}
