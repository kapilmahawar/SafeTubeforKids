'use strict';

/**
 * The two debug intents that *replace* or *clear* a parent credential must stay in the one instrumentation
 * class that is skipped unless a caller opts in explicitly (W13.8-A).
 *
 * The class fixture installs an in-memory credential store, so a run cannot touch a device's own PIN - but
 * that protection is one deleted line away from being absent, and the test that would prove it is the test
 * that clears a credential. This check is the assertion that does not need a device: it fails the moment a
 * credential-replacing intent appears somewhere the default test path would execute, and it also fails if the
 * opt-in guard or the isolated fixture is removed from the guarded class.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const androidTestDir = path.join(__dirname, '..', 'app', 'src', 'androidTest');
const guardedName = 'CredentialTokenInstrumentedTest.kt';
const optInArgument = 'runDestructiveCredentialTests';

/** Intents that install or clear a parent credential. */
const destructiveIntents = ['DEBUG_SET_PIN', 'DEBUG_RESET_PIN'];

function kotlinSources(dir) {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...kotlinSources(full));
    } else if (entry.name.endsWith('.kt')) {
      found.push({ name: entry.name, full, text: fs.readFileSync(full, 'utf8') });
    }
  }
  return found;
}

const sources = kotlinSources(androidTestDir);
const guarded = sources.find((file) => file.name === guardedName);

test('the instrumentation sources are where this check expects them', () => {
  assert.ok(sources.length > 0, `no Kotlin instrumentation sources under ${androidTestDir}`);
  assert.ok(guarded, `${guardedName} is missing: the opt-in boundary has been removed`);
});

test('a credential-replacing intent appears only in the opt-in class', () => {
  const offenders = [];
  for (const file of sources) {
    if (file.name === guardedName) continue;
    for (const intent of destructiveIntents) {
      if (file.text.includes(intent)) offenders.push(`${file.name} uses ${intent}`);
    }
  }
  assert.deepStrictEqual(
    offenders,
    [],
    `a credential-replacing debug intent is reachable from the default instrumentation run:\n  ${offenders.join('\n  ')}`,
  );
});

test('the opt-in class still covers both intents', () => {
  for (const intent of destructiveIntents) {
    assert.ok(guarded.text.includes(intent), `${guardedName} no longer exercises ${intent}`);
  }
});

test('the opt-in class skips itself unless the argument is explicitly present', () => {
  assert.match(guarded.text, /assumeTrue\(/, 'without a JUnit assumption the tests would run by default');
  assert.ok(
    guarded.text.includes(optInArgument),
    `the guard must name the opt-in argument '${optInArgument}'`,
  );
  assert.ok(
    guarded.text.includes('getArguments()'),
    'the guard must read the instrumentation arguments rather than a constant',
  );
});

test('the opt-in class still isolates the credential it mutates', () => {
  assert.match(guarded.text, /getInMemoryInstance/, 'the database must be the in-memory one');
  assert.match(guarded.text, /InMemoryParentCredentialStore/, 'the credential store must be the in-memory one');
  assert.match(guarded.text, /initForTest/, 'the fixture must install those through ServiceLocator.initForTest');
});
