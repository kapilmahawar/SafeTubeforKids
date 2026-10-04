'use strict';

/*
 * Allowing a source, from the dashboard's side.
 *
 * The TV answers 201 Created when it stores a source. The dashboard accepted only 200 and 409, so the
 * first click on "add source" reported a failure for a source the TV had just stored - and the second
 * click appeared to work only because the retry answered 409, the one refusal the page knew how to
 * read. These tests pin the decision itself (source-add.js) and guard the call sites that use it.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const SourceAdd = require('../app/src/main/assets/source-add.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

/** Source with comments removed, so a guard cannot be satisfied by prose. */
function code(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*$/, ''))
        .join('\n');
}

test('a source the TV just created is a success, not a failure', () => {
    assert.equal(SourceAdd.outcome(201), 'allowed');
    assert.equal(SourceAdd.accepted(201), true);
});

test('every answer that means allowed is accepted, and 409 means already allowed', () => {
    assert.equal(SourceAdd.outcome(200), 'allowed');
    assert.equal(SourceAdd.outcome(201), 'allowed');
    assert.equal(SourceAdd.outcome(409), 'already');
    assert.equal(SourceAdd.accepted(200), true);
    assert.equal(SourceAdd.accepted(409), true);
});

test('a genuine refusal is still a refusal', () => {
    for (const status of [400, 401, 403, 404, 422]) {
        assert.equal(SourceAdd.outcome(status), 'failed', `status ${status}`);
        assert.equal(SourceAdd.accepted(status), false, `status ${status}`);
    }
});

test('an answer that says nothing about the write is treated as unknown, not as failure', () => {
    for (const status of [0, 500, 502, 503, 504]) {
        assert.equal(SourceAdd.uncertain(status), true, `status ${status}`);
    }
    for (const status of [200, 201, 409, 400, 401, 404]) {
        assert.equal(SourceAdd.uncertain(status), false, `status ${status}`);
    }
});

test('the add flow can be entered once at a time, and releases afterwards', () => {
    assert.equal(SourceAdd.isInFlight(), false);
    assert.equal(SourceAdd.begin(), true, 'the first add is allowed');
    assert.equal(SourceAdd.begin(), false, 'a second click while the first is pending is not a second add');
    assert.equal(SourceAdd.isInFlight(), true);
    SourceAdd.end();
    assert.equal(SourceAdd.isInFlight(), false);
    assert.equal(SourceAdd.begin(), true, 'a retry after a real failure is allowed');
    SourceAdd.end();
});

test('no call site still accepts only 200 and 409 from the source routes', () => {
    const source = code('app.js');
    assert.equal(
        /status\s*!==\s*200\s*&&\s*[\w.]+\.status\s*!==\s*409/.test(source), false,
        'a 201 from the TV must not be treated as a failure',
    );
    assert.equal(
        /status\s*===\s*200\s*\|\|\s*[\w.]+\.status\s*===\s*409/.test(source), false,
        'a 201 must count as allowed in the bulk allow flow too',
    );
});

test('the dashboard decides through the module, not by status number', () => {
    const source = code('app.js');
    for (const call of ['SourceAdd.outcome(', 'SourceAdd.accepted(', 'SourceAdd.uncertain(']) {
        assert.ok(source.includes(call), `app.js must decide with ${call}`);
    }
});

test('the add flow is gated and always releases the gate', () => {
    const source = code('app.js');
    assert.ok(
        source.includes('if (!SourceAdd.begin()) return;'),
        'the add flow must refuse to start twice',
    );
    assert.match(
        source, /finally\s*\{[\s\S]*?SourceAdd\.end\(\)/,
        'the gate must be released even when the request throws',
    );
});

test('the dashboard loads the module before the script that uses it', () => {
    const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
    const moduleAt = html.indexOf('<script src="source-add.js"');
    const appAt = html.indexOf('<script src="app.js"');
    assert.ok(moduleAt > -1, 'index.html must load source-add.js');
    assert.ok(moduleAt < appAt, 'source-add.js must load before app.js');
});
