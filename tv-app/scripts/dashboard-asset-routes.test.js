'use strict';

/*
 * Every asset the dashboard page asks for must be one the TV will actually serve.
 *
 * DashboardRoutes.kt serves assets from a hand-written map, while index.html names its scripts and
 * stylesheets itself. On 2026-10-04 those two drifted: a new module was added to the page and to the
 * APK but not to the map, so the live dashboard referenced a file the TV answered 404 for - an add
 * button that would throw. No unit test saw it, because in a JVM test the assets are not on the
 * classpath at all, which is why the existing route tests can only assert 404s there.
 *
 * This check needs no device and no browser: it compares what the page loads against what the TV is
 * willing to serve, in both directions.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const appDir = path.join(__dirname, '..', 'app');
const assetsDir = path.join(appDir, 'src', 'main', 'assets');
const routesFile = path.join(
    appDir, 'src', 'main', 'java', 'tv', 'safetubeforkids', 'app', 'server', 'DashboardRoutes.kt');

const html = fs.readFileSync(path.join(assetsDir, 'index.html'), 'utf8');
const routes = fs.readFileSync(routesFile, 'utf8');

/** The local files the page asks the browser to load. */
function referencedAssets() {
    const found = new Set();
    for (const match of html.matchAll(/(?:src|href)="([^":]+)"/g)) {
        const name = match[1];
        if (name && !name.startsWith('#')) found.add(name);
    }
    return [...found];
}

/** The files the TV serves, read out of the hand-written map. */
function servedAssets() {
    const from = routes.indexOf('val assetFiles = mapOf(');
    const to = routes.indexOf('for ((fileName, contentType) in assetFiles)');
    assert.ok(from > -1 && to > from, 'the asset map in DashboardRoutes.kt could not be located');
    return [...routes.slice(from, to).matchAll(/"([\w.-]+\.\w+)"\s+to\s/g)].map((m) => m[1]);
}

test('the page asks for assets and the route map can be read', () => {
    assert.ok(referencedAssets().length > 0, 'index.html references no local assets');
    assert.ok(servedAssets().length >= 5, `only ${servedAssets().length} served assets were found`);
});

test('every asset the page loads is one the TV serves', () => {
    const served = new Set(servedAssets());
    const missing = referencedAssets().filter((name) => !served.has(name));
    assert.deepStrictEqual(
        missing, [],
        `index.html loads files the TV answers 404 for:\n  ${missing.join('\n  ')}`,
    );
});

test('every asset the TV serves exists', () => {
    for (const name of servedAssets()) {
        assert.ok(fs.existsSync(path.join(assetsDir, name)), `${name} is served but is not in assets/`);
    }
});
