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
 *
 * W13.9 added the second half of the same concern. The credential tests were never what erased the family
 * TV's provisioning: the connected-test task uninstalls the app it instruments when it finishes, whatever
 * ran inside it (docs/W13_9_RECOVERY_AND_TEST_SAFETY.md, section A). That uninstall is not preventable from
 * a test, so the app module refuses to run a connected or uninstall task at all unless the run opts in
 * explicitly. These checks hold that refusal in place, and hold it to the two properties that make it
 * real rather than decorative: it must run before the task does its work, and it must name no device.
 */

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const appDir = path.join(__dirname, '..', 'app');
const androidTestDir = path.join(appDir, 'src', 'androidTest');
const appBuildFile = path.join(appDir, 'build.gradle.kts');
const guardedName = 'CredentialTokenInstrumentedTest.kt';
const optInArgument = 'runDestructiveCredentialTests';
const optInEnvironmentVariable = 'SAFETUBE_ALLOW_DEVICE_INSTRUMENTATION';

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
const appBuild = fs.readFileSync(appBuildFile, 'utf8');

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

test('the app module refuses a connected or uninstall task unless the run opts in', () => {
  assert.ok(
    appBuild.includes(optInEnvironmentVariable),
    `build.gradle.kts must gate device instrumentation on ${optInEnvironmentVariable}`,
  );
  assert.match(appBuild, /tasks\.matching\s*\{/, 'the refusal must be attached to the tasks it protects');
  assert.match(appBuild, /startsWith\("connected"\)/, 'connected-test tasks must be guarded');
  assert.match(appBuild, /startsWith\("uninstall"\)/, 'uninstall tasks must be guarded');
  assert.match(
    appBuild,
    /startsWith\("device"\)/,
    'deviceAndroidTest runs instrumentation through the device providers and must be guarded too',
  );
});

test('the refusal happens before the device work and fails the build', () => {
  const guard = appBuild.indexOf('tasks.matching');
  const doFirst = appBuild.indexOf('doFirst', guard);
  const readEnv = appBuild.indexOf('System.getenv', guard);
  assert.ok(doFirst > guard, 'doFirst is what puts the check ahead of the task own actions');
  assert.ok(
    readEnv > doFirst,
    'the opt-in must be read while the task runs, not baked into the configuration',
  );
  assert.match(appBuild.slice(guard), /GradleException/, 'a guard that only warns is not a guard');
  for (const weakener of ['logger.warn', 'println(', 'logger.lifecycle']) {
    assert.ok(
      !appBuild.slice(guard).includes(weakener),
      `${weakener} inside the guard means it reports trouble instead of preventing it`,
    );
  }
});

test('the guard does not depend on a device identity', () => {
  const guard = appBuild.slice(appBuild.indexOf('tasks.matching'));
  assert.ok(
    !/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/.test(guard),
    'the guard must not recognize a device by its address',
  );
  assert.ok(!guard.includes(':5555'), 'the guard must not recognize a device by a serial or port');
  assert.ok(
    !/deviceSerial|ANDROID_SERIAL/.test(guard),
    'the guard must not key off a serial: undocumented identity is not a safety property',
  );
});
