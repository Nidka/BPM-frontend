import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGoogleAuthController } from './google-auth-controller.mjs';

const config = { firebaseApiKey: 'public-test-key', firebaseProjectId: 'test-project',
  firebaseAuthDomain: 'test-project.firebaseapp.com' };

function fixture(popup = async () => ({ user: { uid: 'uid-test', getIdToken: async () => 'firebase-test-token' } })) {
  const calls = [];
  const memory = {};
  const sdk = {
    inMemoryPersistence: memory, browserPopupRedirectResolver: {},
    initializeApp: value => { calls.push(['app', value]); return {}; },
    initializeAuth: (_, options) => { assert.equal(options.persistence, memory); return {}; },
    GoogleAuthProvider: class { setCustomParameters(value) { calls.push(['parameters', value]); } },
    signInWithPopup: (...args) => { calls.push(['popup']); return popup(...args); },
    signOut: async () => { calls.push(['signOut']); },
  };
  return { sdk, calls, controller: createGoogleAuthController({ sdk, loadConfig: async () => config }) };
}

test('opens popup synchronously and returns only Firebase credentials, with memory persistence', async () => {
  const { calls, controller } = fixture();
  await controller.initialization;
  const result = controller.signIn();
  assert.equal(calls.filter(([name]) => name === 'popup').length, 1);
  assert.deepEqual(JSON.parse(await result), { idToken: 'firebase-test-token', uid: 'uid-test', displayName: 'Usuario BPM' });
  assert.deepEqual(calls.find(([name]) => name === 'parameters')[1], { prompt: 'select_account' });
  assert.equal(calls.at(-1)[0], 'signOut');
});

test('cancellation and popup errors never expose provider messages or email', async () => {
  for (const code of ['auth/popup-closed-by-user', 'auth/popup-blocked', 'auth/account-exists-with-different-credential']) {
    const { controller, calls } = fixture(async () => { throw Object.assign(new Error('private@example.com secret'), { code }); });
    await controller.initialization;
    await assert.rejects(controller.signIn(), error => error.code === code && !error.message.includes('secret'));
    assert.equal(calls.at(-1)[0], 'signOut');
  }
});

test('clear rejects late credentials instead of recreating a logged-out session', async () => {
  let complete;
  const { controller } = fixture(() => new Promise(resolve => { complete = resolve; }));
  await controller.initialization;
  const result = controller.signIn();
  controller.clear();
  complete({ user: { uid: 'uid-test', getIdToken: async () => 'firebase-test-token' } });
  await assert.rejects(result, { code: 'auth/cancelled' });
});

test('does not open concurrent popups', async () => {
  let complete;
  const { controller, calls } = fixture(() => new Promise(resolve => { complete = resolve; }));
  await controller.initialization;
  const first = controller.signIn();
  await assert.rejects(controller.signIn(), { code: 'auth/cancelled-popup-request' });
  assert.equal(calls.filter(([name]) => name === 'popup').length, 1);
  complete({ user: { uid: 'uid-test', getIdToken: async () => 'firebase-test-token' } });
  await first;
});

test('missing config and untrusted auth domains cannot initialize Firebase', async () => {
  for (const override of [{ firebaseApiKey: '' }, { firebaseAuthDomain: 'attacker.example' }]) {
    const { sdk, calls, controller: configured } = fixture();
    await configured.initialization;
    calls.length = 0;
    const controller = createGoogleAuthController({ sdk, loadConfig: async () => ({ ...config, ...override }) });
    await controller.initialization;
    await assert.rejects(controller.signIn(), { code: 'auth/not-configured' });
    assert.equal(calls.length, 0);
  }
});
