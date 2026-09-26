/*
 * The redesigned dashboard, as guards over the files that ship.
 *
 * `dashboard-catalog-editor.test.js` proves the model, and `dashboard-catalog-import.test.js` proves
 * the import rules. This file proves the *shell*: that the page is one screen with two sections and
 * no inline handlers, that the palette is tokens with a real dark theme, that the browser stores
 * nothing but the session, the theme and one dismissal flag, and - the point of W7 - that the Apps,
 * Kiosk and Installed-Apps surface is gone from every dashboard file.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const Theme = require('../app/src/main/assets/theme.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

/** Source with comments removed, so a guard cannot be satisfied by prose. */
function code(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');
}

function raw(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8');
}

// --- the theme is decided before the page paints ------------------------------------------

test('the remembered theme wins, and the phone decides only when there is no choice', () => {
    assert.equal(Theme.resolveTheme('dark', false), 'dark', 'a chosen dark stays dark on a light phone');
    assert.equal(Theme.resolveTheme('light', true), 'light', 'a chosen light stays light on a dark phone');
    assert.equal(Theme.resolveTheme(null, true), 'dark', 'no choice on a dark phone means dark');
    assert.equal(Theme.resolveTheme(null, false), 'light', 'no choice on a light phone means light');
    assert.equal(Theme.resolveTheme('blue', true), 'dark', 'a value that is not a theme is not a choice');
    assert.equal(Theme.resolveTheme('', false), 'light');
});

test('the page knows whether the parent chose the theme or the phone did', () => {
    assert.equal(Theme.themeSource('dark'), 'chosen');
    assert.equal(Theme.themeSource(null), 'system');
    assert.equal(Theme.themeSource('blue'), 'system');
});

test('the theme is applied from the head, before anything is painted', () => {
    const html = raw('index.html');
    const head = html.slice(0, html.indexOf('</head>'));

    assert.match(head, /<script src="theme\.js"><\/script>/, 'theme.js has to run before first paint');
    // And the script itself only reads: remembering the choice is the dashboard's job, not this one's.
    const themeSource = code('theme.js');
    assert.match(themeSource, /localStorage\.getItem\(THEME_KEY\)/);
    assert.doesNotMatch(themeSource, /setItem|removeItem/);
    assert.doesNotMatch(themeSource, /catalog/i, 'the theme script knows nothing about the library');
});

// --- the shell ------------------------------------------------------------------------------

test('the page is a shell: no screens, no inline handlers, no data', () => {
    const html = raw('index.html');

    assert.match(html, /<meta name="viewport" content="width=device-width, initial-scale=1/);
    assert.match(html, /<link rel="stylesheet" href="style\.css">/);
    assert.match(html, /<main class="view" id="view"/);
    assert.match(html, /<script src="catalog-editor\.js"><\/script>/);
    assert.match(html, /<script src="app\.js"><\/script>/);

    // No inline handler anywhere: the server's content policy no longer exempts them, so one would
    // be dead markup rather than a working control.
    assert.doesNotMatch(html, /\son[a-z]+\s*=/i);

    // The page carries no library data: it is fetched at runtime or not at all.
    assert.doesNotMatch(html, /"nodes"\s*:/);
    assert.doesNotMatch(html, /catalog-tree\.js/, 'the old tree renderer is gone');
});

test('there are exactly two sections, and the library is the first one', () => {
    const html = raw('index.html');

    const actions = [...html.matchAll(/data-action="([a-z-]+)"/g)].map((m) => m[1]);
    assert.deepEqual(actions, ['go-library', 'toggle-theme', 'go-library', 'go-settings'],
        'the shell wires one theme control and two sections');

    assert.match(html, /id="tab-library"[\s\S]*?id="tab-settings"/, 'the library comes before the settings');
    assert.match(html, />Library</);
    assert.match(html, />Settings</);
});

test('the words on the page are a parent’s words, not the project’s', () => {
    // Only what the parent can read: file names carry the project's vocabulary and always will.
    const visible = raw('index.html')
        .replace(/<!--[\s\S]*?-->/g, ' ')
        .replace(/<[^>]*>/g, ' ');

    [/catalog/i, /subcategor/i, /thumbnail/i, /schema/i, /playlist id/i, /node/i, /\bapi\b/i]
        .forEach((pattern) => {
            assert.doesNotMatch(visible, pattern, 'index.html shows the parent a word from the code');
        });
});

// --- the stylesheet -------------------------------------------------------------------------

test('the palette is custom properties, and the dark theme is one of them', () => {
    const css = raw('style.css');

    const root = css.match(/:root\s*\{([\s\S]*?)\n\}/);
    const dark = css.match(/\[data-theme="dark"\]\s*\{([\s\S]*?)\n\}/);
    assert.ok(root, 'the light palette is a token block');
    assert.ok(dark, 'the dark palette is a token block');

    const define = (block) => new Set([...block.matchAll(/(--[a-z0-9-]+)\s*:/g)].map((m) => m[1]));
    const light = define(root[1]);
    const darkTokens = define(dark[1]);

    assert.ok(light.size >= 15, 'the palette is shared, not repeated per rule');

    // Every token the dark theme sets must exist in the light one, so a component can never be
    // styled for one theme only.
    [...darkTokens].forEach((token) => {
        assert.ok(light.has(token), token + ' is set for dark but never for light');
    });

    // And every token that is used is one that exists.
    const used = new Set([...css.matchAll(/var\((--[a-z0-9-]+)/g)].map((m) => m[1]));
    [...used].forEach((token) => {
        assert.ok(light.has(token) || darkTokens.has(token), token + ' is used but never defined');
    });

    assert.match(css, /color-scheme:\s*dark/, 'the browser is told, so form controls follow');
});

test('the stylesheet is mobile-first and only widens for a bigger screen', () => {
    const css = raw('style.css');
    const firstQuery = css.search(/@media/);
    const firstWide = css.indexOf('@media (min-width: 760px)');

    assert.ok(firstQuery > 0, 'there is a media query');
    assert.ok(firstWide > 0, 'the wide layout is the query that exists');
    assert.ok(css.indexOf('.cards') < firstWide, 'the phone layout is the base, not a special case');
    assert.match(css, /min-width:\s*760px/);
    assert.doesNotMatch(css, /max-width:\s*480px\s*;/, 'the old fixed phone column is gone');
});

test('nothing is smaller than a fingertip, and nothing depends on hover', () => {
    const css = raw('style.css');

    const tap = css.match(/--tap:\s*(\d+)px/);
    assert.ok(tap, 'the tap target is a token');
    assert.ok(Number(tap[1]) >= 40, 'a tap target is at least 40px');
    assert.match(css, /\.btn\s*\{[\s\S]*?min-height:\s*var\(--tap\)/);
    assert.match(css, /:focus-visible/, 'a keyboard or TV remote can see where it is');

    assert.doesNotMatch(css, /:hover/, 'a phone has no pointer to hover with');
    assert.doesNotMatch(css, /@import|fonts\.googleapis|url\(http/i, 'no external font or image: the policy blocks it');
});

// --- the script -----------------------------------------------------------------------------

test('the browser stores the session, the theme and one dismissal flag - never the library', () => {
    const app = code('app.js');

    const storageLines = app.split('\n')
        .map((line, index) => ({ line: line.trim(), number: index + 1 }))
        .filter((entry) => /localStorage|sessionStorage/.test(entry.line));

    assert.ok(storageLines.length > 0, 'the session token has to live somewhere');

    storageLines.forEach((entry) => {
        assert.match(entry.line, /TOKEN_KEY|THEME_KEY|HOMESCREEN_KEY|removeItem/,
            'app.js:' + entry.number + ' stores something else: ' + entry.line);
        assert.doesNotMatch(entry.line, /catalog|nodes|version/i,
            'app.js:' + entry.number + ' puts the library in browser storage: ' + entry.line);
    });
});

test('the library is read and written through the one document endpoint', () => {
    const app = code('app.js');

    assert.match(app, /apiCall\('GET', '\/catalog'\)/);
    assert.match(app, /apiCall\('PUT', '\/catalog', body\)/);
    assert.match(app, /CatalogEditor\.reload\(/);
    assert.match(app, /CatalogEditor\.save\(/);

    // There is no second way in: every request goes through apiCall, and the only raw fetch calls
    // are the two that cannot have a session yet.
    const fetches = [...app.matchAll(/fetch\(([^)]*)/g)].map((m) => m[1]);
    assert.ok(fetches.length <= 2, 'the requests go through apiCall');
    fetches.forEach((call) => {
        assert.ok(!/catalog/i.test(call), 'a raw fetch must never touch the library');
    });

    // And the API surface is exactly the endpoints that already existed, plus the two the redesign
    // needs: nothing here invents a second way to change the library.
    const allowed = new Set([
        "'/status'", "'/auth/refresh'", "'/catalog'", "'/catalog/artwork'", "'/catalog/refresh'",
        "'/catalog/import/resolve'", "'/playlists'", "'/playlists/'", "'/time-limits'",
        "'/time-limits/lock'", "'/time-limits/bonus'", "'/stats'", "'/stats/recent'", "'/crash-log'",
        "'/playback/'", "'/sources/export'", "'/sources/import'",
    ]);

    const paths = [...app.matchAll(/apiCall\('[A-Z]+', ('[^']+')/g)].map((m) => m[1]);
    assert.ok(paths.length > 10, 'the dashboard still talks to the server');
    [...new Set(paths)].forEach((used) => {
        assert.ok(allowed.has(used), used + ' is an endpoint this redesign did not have to invent');
    });
});

test('the dashboard renders text, never markup', () => {
    const app = code('app.js');

    ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'eval(', 'new Function']
        .forEach((forbidden) => {
            assert.ok(!app.includes(forbidden),
                'app.js must build elements, so a name the parent typed cannot become markup (' + forbidden + ')');
        });
});

test('the script publishes nothing onto window', () => {
    const app = code('app.js');
    assert.doesNotMatch(app, /window\.[a-zA-Z_$]+\s*=(?!=)/,
        'the page has no inline handlers, so the script has no reason to publish globals');
});

// --- W7's removal, guaranteed ---------------------------------------------------------------

test('the Apps, Kiosk and Installed-Apps surface is gone from the dashboard', () => {
    const files = ['index.html', 'app.js', 'style.css', 'theme.js'];

    files.forEach((file) => {
        const source = code(file);
        [
            /kiosk/i,
            /installed apps/i,
            /whitelist/i,
            /\/apps\b/,
            /device owner/i,
            /app_whitelist/i,
            /setup-kiosk/,
        ].forEach((pattern) => {
            assert.doesNotMatch(source, pattern, file + ' still carries the removed surface: ' + pattern);
        });
    });
});

test('the removed surface leaves no dangling call, route or asset behind', () => {
    const html = raw('index.html');
    const app = code('app.js');

    // Every asset the page loads exists in the directory.
    const loaded = [...html.matchAll(/(?:src|href)="([a-z0-9.-]+\.(?:js|css|svg))"/g)].map((m) => m[1]);
    assert.ok(loaded.length >= 4);
    loaded.forEach((file) => {
        assert.ok(fs.existsSync(path.join(assets, file)), 'the page loads ' + file + ', which does not exist');
    });

    // And the script names no asset that is not loaded by the page.
    const named = [...new Set([...app.matchAll(/'([a-z0-9-]+\.(?:js|css))'/g)].map((m) => m[1]))];
    named.forEach((file) => {
        assert.ok(loaded.includes(file), 'app.js names ' + file + ', which the page never loads');
    });
});
