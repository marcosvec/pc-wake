const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const context = {
  crypto: globalThis.crypto,
  TextEncoder,
  Uint8Array,
  btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  atob: (s) => Buffer.from(s, 'base64').toString('binary'),
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'protocol.js'), 'utf8'), context);

const HMAC = 'c49d1a2c5bdccdfb81db4776cf092efad64de81531b6cb4278cc866750e4c0e2';
const UTF8_HMAC = 'a09fe18709bb105b5e1e8d2e7d81cc2bacbe8f18f4595e3fb513af7a19c18883';

async function test(name, fn) {
  try {
    await fn();
    console.log('ok ' + name);
  } catch (e) {
    console.error('FAIL ' + name);
    console.error(e);
    process.exitCode = 1;
  }
}

(async () => {
  await test('wake message matches the relay', () => {
    assert.strictEqual(context.wakeMessage('wake', 1700000000000, '0011223344556677'), 'wake|1700000000000|0011223344556677');
  });

  await test('hmac vector matches the relay', async () => {
    const hex = await context.hmacHex('secret', context.wakeMessage('wake', 1700000000000, '0011223344556677'));
    assert.strictEqual(hex, HMAC);
  });

  await test('hmac is utf-8', async () => {
    const hex = await context.hmacHex('påss', context.wakeMessage('wake', 1, 'abcdefgh'));
    assert.strictEqual(hex, UTF8_HMAC);
  });

  await test('wake command wire shape', async () => {
    const cmd = await context.buildWakeCommand('secret', 1700000000000, '0011223344556677');
    assert.strictEqual(cmd.cmd.action, 'wake');
    assert.strictEqual(cmd.cmd.ts, 1700000000000);
    assert.strictEqual(cmd.cmd.nonce, '0011223344556677');
    assert.strictEqual(cmd.cmd.sig, HMAC);
    const wire = JSON.stringify(cmd);
    assert.strictEqual(wire, '{"cmd":{"action":"wake","ts":1700000000000,"nonce":"0011223344556677","sig":"' + HMAC + '"}}');
  });

  await test('setup link round-trips without padding', () => {
    const samples = ['', 'a', 'ab', 'abc', 'hello world', 'påss', '{"token":"github_pat_abc+/","secret":"s+/=","gist_id":"abcdef0123456789"}'];
    for (const sample of samples) {
      const enc = context.b64urlEncode(sample);
      assert.strictEqual(enc.includes('='), false);
      assert.strictEqual(enc.includes('+'), false);
      assert.strictEqual(enc.includes('/'), false);
      assert.strictEqual(context.b64urlDecode(enc), sample);
    }
  });

  if (process.exitCode) process.exit(process.exitCode);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
