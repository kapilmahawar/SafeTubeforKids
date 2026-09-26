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

// --- W8: the product polish, as guards ------------------------------------------------------

test('the shell is painted before the first request, so a cold load is never blank', () => {
    const app = code('app.js');
    const boot = app.slice(app.indexOf('async function boot()'), app.indexOf('boot();'));

    const firstRender = boot.indexOf('render();');
    const firstAwait = boot.indexOf('await ');

    assert.ok(firstRender !== -1, 'boot has to paint the shell');
    assert.ok(firstAwait !== -1, 'boot still has to load things');
    assert.ok(firstRender < firstAwait,
        'the loading state must be on screen before the first request is awaited');
    assert.match(boot, /await loadStatus\(\)/);
});

test('a loading screen says what it is doing and shows the shape of what is coming', () => {
    const app = code('app.js');

    assert.match(app, /function screenNotLoaded\(/);
    assert.match(app, /skeleton skeleton--row/, 'a loading library shows the rows it is waiting for');
    assert.match(app, /Loading what your child can read|Loading what your child can watch/);
    assert.match(app, /class: 'spinner'/);
});

test('the words a parent reads are the product’s, not the project’s', () => {
    const app = code('app.js');
    const html = raw('index.html');

    // The vocabulary W8 settled on: category, collection, allowed source, update the TV.
    ['Your library', 'New category', 'New collection', 'Add to library', 'Allowed YouTube sources',
        'Update the TV now', 'Reload the library', 'Check for problems', 'Remove from library',
        'Hide from my child', 'Edit name', 'Move to the top', 'Move to the bottom']
        .forEach((phrase) => {
            assert.ok(app.includes(phrase) || html.includes(phrase),
                'the interface should say "' + phrase + '"');
        });

    // And the words W8 replaced are gone from anything a parent reads.
    [/New shelf/, /New folder/, /Send to TV now/, /Add from YouTube/, /Delete “/, /Delete '/,
        /Check my library/, /Reload from the TV/, /cannot play yet/]
        .forEach((pattern) => {
            assert.doesNotMatch(app, pattern, 'the interface still says ' + pattern);
        });
});

test('“cannot play yet” warns only about what the child can actually see', () => {
    const app = code('app.js');
    const helper = app.slice(app.indexOf('function unplayableHere'), app.indexOf('function unplayableBanner'));

    assert.match(helper, /video\.enabled !== false && !canPlay\(video, maps\)/,
        'a hidden video is not a problem to report');
});

test('a collection never offers to hold another collection', () => {
    const app = code('app.js');
    const builder = app.slice(app.indexOf('function addActions(parent)'), app.indexOf('function unplayableHere'));

    assert.match(builder, /parent\.nodeType === CatalogEditor\.CATEGORY/,
        'only a category can hold a collection');
    assert.match(builder, /Add to library/);
});

test('reordering is one tap on the row and still reachable from the menu', () => {
    const app = code('app.js');

    assert.match(app, /actionButton\('▲', 'move-up'/);
    assert.match(app, /actionButton\('▼', 'move-down'/);
    assert.match(app, /'move-up': function \(element\) \{ moveNode\(/);
    assert.match(app, /'move-down': function \(element\) \{ moveNode\(/);

    // The four journeys a parent asked for, and no drag-and-drop anywhere.
    ['Move up', 'Move down', 'Move to the top', 'Move to the bottom', 'Move to another category…']
        .forEach((option) => assert.ok(app.includes(option), 'the menu should offer ' + option));
    assert.doesNotMatch(app, /draggable|dragstart|pointerdown/,
        'reordering stays deterministic: no drag, so nothing depends on a steady hand');
});

test('removing something says exactly what it does, and what it does not', () => {
    const app = code('app.js');
    const confirm = app.slice(app.indexOf('async function askDelete'), app.indexOf('// --- settings'));

    assert.match(confirm, /Remove “' \+ node\.title \+ '” from your library\?/);
    assert.match(confirm, /It does not delete anything from YouTube\./);
    assert.match(confirm, /confirmLabel: 'Remove'/);
    assert.doesNotMatch(confirm, /Deleted|deletes it/);
});

test('a video can be renamed, and the video itself is untouched', () => {
    const app = code('app.js');
    const sheet = app.slice(app.indexOf('async function openItemSheet'), app.indexOf('async function runItemAction'));

    // The rename option is offered before any type test, so it is offered for every kind of node.
    const renameAt = sheet.indexOf("id: 'rename'");
    const firstTypeTest = sheet.indexOf('node.nodeType');
    assert.ok(renameAt !== -1 && renameAt < firstTypeTest,
        'renaming is offered for videos too');
    assert.match(sheet, /title: 'Edit name'/);
    assert.match(app, /The video itself is unchanged; only the name your child sees\./);
});

test('a failure the parent cannot act on never reaches the screen raw', () => {
    const app = code('app.js');

    assert.match(app, /function humanError\(/, 'server failures are translated');
    assert.match(app, /function isParentReadable\(/);

    // No screen prints a server message without passing it through the translation.
    assert.doesNotMatch(app, /textContent = [^;]*\.error\b/,
        'an error message must go through humanError first');
    assert.doesNotMatch(app, /toast\(\([a-z]+\.data && [a-z]+\.data\.error\)/,
        'a toast must go through humanError first');

    // And the translations do not leak implementation words either: the *patterns* have to match the
    // extractor's own text, but what a parent reads must not contain it.
    const says = [...app.slice(app.indexOf('var FRIENDLY_FAILURES'), app.indexOf('function isParentReadable'))
        .matchAll(/says: '([^']*)'/g)].map((m) => m[1]);
    assert.ok(says.length >= 4, 'the translator still has its sentences');
    says.forEach((phrase) => assert.doesNotMatch(phrase, /exception|extractor|json|null|undefined/i,
        'a translation must not contain the message it translates: ' + phrase));
});

test('settings are grouped, and every group holds real controls', () => {
    const app = code('app.js');

    ['TV', 'Time limits', 'Content', 'What has been watched', 'Appearance', 'Troubleshooting']
        .forEach((group) => assert.ok(app.includes("settingsGroup('" + group + "'"),
            'settings should have a ' + group + ' group'));

    assert.match(app, /function settingsGroup\(/);
    // Every panel still exists inside a group: nothing was dropped for being awkward to place.
    ['tvPanel', 'screenTimePanel', 'allowedSourcesPanel', 'watchHistoryPanel', 'lookPanel',
        'libraryPanel', 'helpPanel']
        .forEach((builder) => assert.match(app, new RegExp(builder + '\\(\\)'),
            builder + ' must still be rendered'));
});

test('a picture carries the item’s name and degrades to the placeholder', () => {
    const app = code('app.js');
    const artwork = app.slice(app.indexOf('function pictureNode'), app.indexOf('function sectionHead'));

    assert.match(artwork, /alt: title \|\| ''/, 'the picture is the item, so it has a name');
    assert.match(artwork, /addEventListener\('error'/, 'a picture that stops resolving is replaced');
    assert.match(artwork, /loading: 'lazy'/);
    assert.match(artwork, /decoding: 'async'/);
});

// --- W8.1: what the TV is playing right now --------------------------------------------------

test('now playing reads the TV’s own live state, not the watch history', () => {
    const app = code('app.js');
    const from = app.indexOf('function nowPlayingModel');
    const to = app.indexOf('function retunePolling');
    const block = app.slice(from, to);

    // The same endpoint the dashboard has always used for this, answered from the player itself.
    assert.match(app, /apiCall\('GET', '\/status'\)/);
    assert.match(block, /state\.status\.currentlyPlaying/);

    // And never the history list: "what was watched" is a different question.
    assert.doesNotMatch(block, /state\.recent|loadStats|play_events/,
        'Now Playing must not be built out of watch history');
});

test('the five states are distinct, and a TV that cannot be reached is not a TV playing nothing', () => {
    const app = code('app.js');
    const model = app.slice(app.indexOf('function nowPlayingModel'), app.indexOf('function noteNowPlaying'));

    assert.match(model, /return \{ state: 'connecting' \}/);
    assert.match(model, /state\.reachable === false/);
    assert.match(model, /return \{ state: 'unreachable' \}/);
    assert.match(model, /return \{ state: 'idle' \}/);
    assert.match(model, /state: playing\.playing \? 'playing' : 'paused'/);

    // The reachability check comes first, so a failed poll can never be read as "nothing playing".
    assert.ok(model.indexOf("state: 'unreachable'") < model.indexOf("state: 'idle'"),
        'unreachable must be decided before idle');

    ['Playing on TV', 'Paused on TV', 'Nothing is playing right now', 'Connecting…',
        'Unable to reach the TV.']
        .forEach((copy) => assert.ok(app.includes(copy), 'the card should be able to say "' + copy + '"'));

    // A card that has stopped being updated says so, rather than pretending.
    assert.ok(app.includes('The TV stopped reporting what it is playing.'));
});

test('a playhead that stops moving means the TV stopped reporting', () => {
    const app = code('app.js');

    assert.match(app, /var NOW_PLAYING_STALE_POLLS = 3;/);
    assert.match(app, /state\.nowPlayingFrozen = moved \? 0 : \(state\.nowPlayingFrozen \|\| 0\) \+ 1;/);
    assert.match(app, /state\.nowPlayingFrozen >= NOW_PLAYING_STALE_POLLS/);

    // A new video, a new position or a pause all count as the TV still reporting.
    assert.match(app, /seen\.videoId !== playing\.videoId/);
    assert.match(app, /seen\.positionSec !== playing\.positionSec/);
    assert.match(app, /seen\.playing !== playing\.playing/);
});

test('the playhead is advanced locally between polls, and only while playing', () => {
    const app = code('app.js');
    const playhead = app.slice(app.indexOf('function playheadNow'), app.indexOf('function nowPlayingCard'));

    assert.match(playhead, /playing\.playing && state\.nowPlayingFrozen === 0 && state\.nowPlayingAt/);
    assert.match(playhead, /Date\.now\(\) - state\.nowPlayingAt/);
    assert.match(playhead, /Math\.min\(position, playing\.durationSec\)/,
        'the readout never runs past the end of the video');

    // One timer, one second, and only while there is something moving to show.
    assert.match(app, /playheadTimer = setInterval\(function \(\) \{/);
    assert.match(app, /\}, 1000\);/);
    const ticker = app.slice(app.indexOf('function retunePlayheadTicker'), app.indexOf('function retunePolling'));
    assert.match(ticker, /if \(wanted && !playheadTimer\)/);
    assert.match(ticker, /nowPlayingModel\(\)\.state === 'playing' && !document\.hidden/);
});

test('polling is retuned, never duplicated, and stops while the page is hidden', () => {
    const app = code('app.js');
    const retune = app.slice(app.indexOf('function retunePolling'), app.indexOf('async function boot'));

    assert.match(retune, /model\.state === 'playing' \? 5000 : \(model\.state === 'paused' \? 10000 : 20000\)/);
    assert.match(retune, /if \(wanted === pollIntervalMs && statusTimer\) return;/,
        'the timer is only restarted when the cadence actually changes');
    assert.match(retune, /stopPolling\(\);\s*\n\s*startPolling\(\);/);

    const polling = app.slice(app.indexOf('function startPolling'), app.indexOf('// --- startup'));
    assert.ok((polling.match(/setInterval\(/g) || []).length === 1,
        'there is one status timer');
    assert.match(polling, /if \(document\.hidden\) return;/);

    // Coming back to the page asks immediately instead of waiting out the interval.
    assert.match(app, /document\.addEventListener\('visibilitychange'/);
});

test('now playing is informational: it reuses the playback controls and grants nothing', () => {
    const app = code('app.js');

    // The card's controls are the ones the dashboard already had, by the same endpoint.
    assert.match(app, /actionButton\('Stop', 'playback-stop'/);
    assert.match(app, /actionButton\(model\.state === 'playing' \? 'Pause' : 'Resume', 'playback-pause'/);
    assert.match(app, /'playback-stop': function \(\) \{ playback\('stop'/);

    // Nothing here can start playback: there is no route to play a video, and no way to name one.
    assert.doesNotMatch(app, /\/playback\/play|playback-start|startPlayback/);
    assert.doesNotMatch(app, /apiCall\('POST', '\/playback\/(pause|stop|skip)', *\{/, 'the controls take no body');
});

test('the card gets its picture from the TV first, and never invents a fact about it', () => {
    const app = code('app.js');
    const artwork = app.slice(app.indexOf('function nowPlayingArtwork'), app.indexOf('function formatClock'));

    assert.match(artwork, /state\.artwork\.videos\[playing\.videoId\]/,
        'the TV\'s own artwork comes first');
    // Read from the raw source: the comment-stripping helper eats everything after "//", which is
    // exactly what a URL literal starts with.
    assert.match(raw('app.js'), /i\.ytimg\.com\/vi\//, 'and YouTube\'s thumbnail for that id is the fallback');
    assert.match(artwork, /\^\[A-Za-z0-9_-\]\{6,20\}\$/,
        'only something shaped like a YouTube id is turned into a URL');
});

// --- W8.2: the corrections, as guards --------------------------------------------------------

test('F1: the breadcrumb is a control a parent can hit', () => {
    const css = raw('style.css');
    const crumb = css.slice(css.indexOf('.crumb {'), css.indexOf('.crumb--here'));

    assert.match(crumb, /min-height:\s*40px/, 'the way back is at least 40px tall');
    assert.match(crumb, /display:\s*inline-flex/);
    assert.match(crumb, /align-items:\s*center/);
    // The text stays the size it was: the target grew, the look did not.
    assert.match(crumb, /font-size:\s*var\(--fs-sm\)/);
});

test('F2: removing something says how many videos go with it', () => {
    const app = code('app.js');
    const sheet = app.slice(app.indexOf('async function openItemSheet'), app.indexOf('async function runItemAction'));
    const confirm = app.slice(app.indexOf('async function askDelete'), app.indexOf('function panel('));

    // One count, and it is the reachable one - not the number of rows in the document. A collection
    // is counted the way its own heading counts it, which for an imported playlist is the TV's count.
    assert.match(sheet, /var removable = removableVideoCount\(node\);/);
    assert.match(sheet, /'Removes this and the ' \+ words\(removable, 'video'\) \+ ' inside it'/);
    assert.match(confirm, /var inside = removableVideoCount\(node\);/);
    assert.match(confirm, /'This removes it and the ' \+ words\(inside, 'video'\) \+ ' inside it from your child/);

    const helper = app.slice(app.indexOf('function removableVideoCount'), app.indexOf('function nodeById'));
    assert.match(helper, /if \(node\.nodeType === CatalogEditor\.VIDEO\) return 0;/);
    assert.match(helper, /return isContainer\(node\) \? containerVideoCount\(node\) : reachableVideoCount\(node\);/,
        'a collection is counted by the TV-aware helper, a category by what is inside it');

    // And never the document-row count, which said "0 items" for a fifty-video collection.
    assert.doesNotMatch(sheet, /descendantsOf\(node\.id\)\.length/, 'the old row count is gone');
    assert.doesNotMatch(confirm, /descendantsOf\(node\.id\)\.length/, 'the old row count is gone');

    // The sentence that must survive a destructive confirmation.
    assert.match(confirm, /It does not delete anything from YouTube\./);
    assert.match(confirm, /confirmLabel: 'Remove'/);
});

test('F4: an unknown TV version is never reported as a stale one', () => {
    const app = code('app.js');

    assert.match(app, /artworkKnown: false/);
    assert.match(app, /state\.artworkKnown = true;/);
    assert.match(app, /state\.artworkKnown = false;/);

    const freshness = app.slice(app.indexOf('function tvFreshness'), app.indexOf('function screenHead'));
    assert.match(freshness, /if \(state\.reachable === false\) return 'unreachable';/);
    assert.match(freshness, /if \(!state\.artworkKnown\) return 'unknown';/,
        'a failed read is unknown, which is checked before any comparison');
    assert.match(freshness, /return state\.artwork\.installedCatalogVersion >= state\.session\.catalogVersion \? 'current' : 'behind';/);

    // The banner and the settings line are gated on the same classification, and only "behind" may
    // ever claim it.
    const library = app.slice(app.indexOf('function screenLibrary'), app.indexOf('function homescreenHintWanted'));
    assert.match(library, /if \(freshness === 'behind'\) \{/);
    assert.match(library, /var freshness = tvFreshness\(\);/);

    const panel = app.slice(app.indexOf('function libraryPanel'), app.indexOf('function watchHistoryPanel'));
    assert.match(panel, /var freshness = tvFreshness\(\);/);
    assert.match(panel, /freshness === 'current' \? 'Your TV has this version\.'/);
    assert.match(panel, /freshness === 'behind' \? 'Your TV is on an older version/);
    assert.match(panel, /'What version your TV has could not be checked just now\.'/);

    // Nothing anywhere compares the installed version to zero and calls it behind.
    assert.doesNotMatch(app, /installedCatalogVersion < state\.session\.catalogVersion/,
        'the bare comparison that produced the false claim is gone');
});

test('F5: every screen counts the same videos', () => {
    const app = code('app.js');
    const counts = app.slice(app.indexOf('function countsFor'), app.indexOf('function containerVideoCount'));

    assert.match(counts, /videos: reachableVideoCount\(nodeId\)/);
    assert.match(app, /function reachableVideoCount\(nodeId\) \{/);

    // The library summary, the category heading and the collection heading all read the same number.
    assert.match(app, /collections \+= counts\.collections;/);
    assert.match(app, /videos \+= counts\.videos;/);
    assert.match(app, /var videos = isContainer\(node\) \? containerVideoCount\(node\) : counts\.videos;/);

    // A hidden video is counted as hidden, not as reachable - so the two numbers never double-count.
    const helper = app.slice(app.indexOf('function reachableVideoCount'), app.indexOf('// --- the parent'));
    assert.match(helper, /if \(child\.enabled !== false\) total \+= 1;/);
});

test('F6: the conflict dialog names no version', () => {
    const app = code('app.js');
    const dialog = app.slice(app.indexOf('function openConflictDialog'), app.indexOf('var TYPE_WORDS'));

    assert.doesNotMatch(dialog, /version/i, 'no catalog version reaches a parent');
    assert.doesNotMatch(dialog, /serverVersion/, 'and the outcome is no longer read for one');
    assert.match(dialog, /Another phone or browser saved a change first, so this change was not saved\./);
    assert.match(dialog, /Nothing on the TV changed\./);
    // Both safe paths stay.
    assert.match(dialog, /'Load the latest and start again'/);
    assert.match(dialog, /'Keep my change and save again'/);
    assert.match(app, /openConflictDialog\(reason\)/);
});

test('F7: a row states its state once', () => {
    const app = code('app.js');
    const meta = app.slice(app.indexOf('function nodeMeta'), app.indexOf('function parseHash'));

    assert.match(meta, /return sourceNameFor\(node\);/, 'the meta line is where it came from, once');
    assert.doesNotMatch(meta, /parts\.push\('hidden'\)/);
    assert.doesNotMatch(meta, /can\\'t play yet'\)\);/, 'the badge says it, not the meta line');

    // The badges themselves are untouched.
    const row = app.slice(app.indexOf('function itemRow'), app.indexOf('function emptyState'));
    assert.match(row, /badge--hidden', text: 'Hidden'/);
    assert.match(row, /badge--blocked', text: 'Can\\'t play yet'/);
});

test('F8: the move description works for a video and for a collection', () => {
    const app = code('app.js');
    assert.ok(app.includes("meta: 'Keeps everything inside it'"));
    assert.doesNotMatch(app, /Keeps the video and everything inside it/);
});

test('F15: a link with nothing behind it says so, and other failures keep their own words', () => {
    const app = code('app.js');
    const failures = app.slice(app.indexOf('var FRIENDLY_FAILURES'), app.indexOf('function isParentReadable'));

    // The sentence the device's own extractor messages have to reach.
    assert.match(failures, /json response is too short/);
    assert.match(failures, /Nothing was found at that link\./);
    // The unavailable family no longer assumes a playlist.
    assert.match(failures, /That video or playlist is not available on YouTube\./);
    assert.doesNotMatch(failures, /That playlist is private/);
    // Network failures are still described as network failures, and are checked first.
    assert.ok(failures.indexOf('could not reach YouTube') < failures.indexOf('Nothing was found'),
        'a network problem must not be reported as a missing video');
    assert.ok(failures.indexOf('did not answer') < failures.indexOf('Nothing was found'));
    // And the translator is still the only way an error reaches the screen.
    assert.match(app, /function humanError\(data, fallback\)/);

    // A short technical message is still technical: the extractor's own "Got error ERROR: ..." reads
    // as English, so the readability filter has to reject it for the translation to happen at all.
    const readable = app.slice(app.indexOf('function isParentReadable'), app.indexOf('function humanError'));
    assert.match(readable, /got error/);
    assert.match(readable, /error\\b\\s\*\[:"'\]/);
    assert.match(readable, /too short/);
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
