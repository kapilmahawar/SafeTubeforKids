/*
 * Puts the example kids library on a TV, reproducibly.
 *
 * A parent does this by hand: Settings -> Library file -> choose the file -> Import. This does the
 * same three things the dashboard does when a parent clicks Import, in the same order, over the same
 * API - it just also does the step before it that a parent does once, allowing the sources the
 * library needs:
 *
 *   1. allow every YouTube source the file's items come from      POST /playlists
 *   2. replace the catalog with the file's nodes                  PUT  /catalog
 *   3. make the TV catch up                                       POST /catalog/refresh
 *
 * The file is read by `catalog-yaml.js`, the module the dashboard itself loads, so a file that loads
 * here is a file that loads there. Nothing is written to the file, and nothing here decides what
 * plays: allowing a source is what lets its videos play, exactly as when a parent pastes the link.
 *
 * Usage:
 *   node load-example-library.js --host 172.16.1.2 --pin 482913
 *   node load-example-library.js --host 172.16.1.2 --pin 482913 --replace-sources
 *
 * `--replace-sources` removes the sources the fixture does not name, which is what makes a run
 * deterministic - including removing the dead demo playlist an older build shipped.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const CatalogYaml = require(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'catalog-yaml.js'));

function arg(name, fallback) {
    const at = process.argv.indexOf('--' + name);
    return at >= 0 && process.argv[at + 1] ? process.argv[at + 1] : fallback;
}
const flag = (name) => process.argv.indexOf('--' + name) >= 0;

const HOST = arg('host', '172.16.1.2');
const PIN = arg('pin', '482913');
const FILE = arg('file', path.join(__dirname, 'example-kids-library.yaml'));
const BASE = 'http://' + HOST + ':8080';
const REPLACE = flag('replace-sources');
const WAIT_SECONDS = Number(arg('wait', '180'));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, route, bearer, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (bearer) headers.Authorization = 'Bearer ' + bearer;
    const response = await fetch(BASE + route, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let parsed = null;
    try { parsed = text ? JSON.parse(text) : null; } catch (_) { parsed = { raw: text }; }
    return { status: response.status, ok: response.ok, body: parsed };
}

async function main() {
    const text = fs.readFileSync(FILE, 'utf8');
    const read = CatalogYaml.readDocument(text);
    if (!read.ok) {
        throw new Error('the catalog file does not read: ' + read.problems.join('; '));
    }
    console.log('file: %s  (%d nodes, name "%s")', path.basename(FILE), read.nodes.length, read.name);

    const signIn = await call('POST', '/auth', null, { pin: PIN });
    if (!signIn.ok) throw new Error('sign-in failed: HTTP ' + signIn.status + ' ' + JSON.stringify(signIn.body));
    const bearer = signIn.body.token;

    // 1. The sources this file's items come from.
    //
    //    Read from the file itself, which is the only thing that decides what has to be allowed. The
    //    file can name every kind of source the app supports - a playlist, a channel or a single video
    //    - because W12 fixed the catalog contract to check an id against all three. (Until then a
    //    channel could not be written into a video node at all, and this loader had to read the
    //    manifest for it; the manifest is documentation now, not an input.)
    const sources = [];
    const addSource = (id, url) => {
        if (!id || sources.some((entry) => entry.id === id)) return;
        sources.push({
            id,
            url: url || (id.startsWith('UC') || id.startsWith('@')
                ? 'https://www.youtube.com/channel/' + id
                : 'https://www.youtube.com/playlist?list=' + id),
        });
    };
    read.nodes.forEach((node) => addSource(node.youtubePlaylistId, null));
    console.log('sources named by the file: %d', sources.length);

    const existing = await call('GET', '/playlists', bearer);
    const have = new Set((existing.body || []).map((entry) => entry.sourceId));
    console.log('allowed sources now: %d', have.size);

    for (const source of sources) {
        if (have.has(source.id)) {
            console.log('  keep   %s  (already allowed)', source.id);
            continue;
        }
        const added = await call('POST', '/playlists', bearer, { url: source.url });
        if (!added.ok) throw new Error('could not allow ' + source.id + ': HTTP ' + added.status + ' ' + JSON.stringify(added.body));
        console.log('  allow  %s  %s', source.id, source.url);
        have.add(source.id);
    }

    const wantedIds = sources.map((entry) => entry.id);
    if (REPLACE) {
        const all = await call('GET', '/playlists', bearer);
        for (const entry of all.body || []) {
            if (wantedIds.indexOf(entry.sourceId) >= 0) continue;
            const removed = await call('DELETE', '/playlists/' + entry.id, bearer);
            if (!removed.ok) throw new Error('could not remove ' + entry.sourceId + ': HTTP ' + removed.status);
            console.log('  remove %s  %s', entry.sourceId, entry.displayName);
        }
    } else {
        const all = await call('GET', '/playlists', bearer);
        for (const entry of all.body || []) {
            if (wantedIds.indexOf(entry.sourceId) < 0) {
                console.log('  note   %s is allowed and is not part of the fixture (use --replace-sources to drop it)', entry.sourceId);
            }
        }
    }

    // 2. The catalog itself: the whole document, in one write, exactly as the dashboard's Import does.
    const stored = await call('PUT', '/catalog', bearer, {
        schemaVersion: 2,
        nodes: read.nodes.map((node) => ({
            id: node.id,
            parentId: node.parentId,
            nodeType: node.nodeType,
            title: node.title,
            position: node.position,
            enabled: node.enabled,
            youtubeVideoId: node.youtubeVideoId,
            youtubePlaylistId: node.youtubePlaylistId,
            thumbnailMode: node.thumbnailMode,
            thumbnailVideoId: node.thumbnailVideoId,
            thumbnailUrl: node.thumbnailUrl,
        })),
    });
    if (!stored.ok) throw new Error('the catalog was refused: HTTP ' + stored.status + ' ' + JSON.stringify(stored.body));
    console.log('catalog stored at version %d (%d nodes)', stored.body.catalogVersion, (stored.body.nodes || []).length);

    // 3. Make the TV catch up. The refresh resolves every allowed source, which is also what fills
    //    the cache that decides which of these videos can play.
    const refreshed = await call('POST', '/catalog/refresh', bearer);
    console.log('refresh: HTTP %d %s', refreshed.status, JSON.stringify(refreshed.body));

    const wanted = stored.body.catalogVersion;
    const deadline = Date.now() + WAIT_SECONDS * 1000;
    let installed = 0;
    while (Date.now() < deadline) {
        const state = await call('GET', '/catalog/artwork', bearer);
        installed = state.body && typeof state.body.installedCatalogVersion === 'number'
            ? state.body.installedCatalogVersion : 0;
        if (installed >= wanted) break;
        await sleep(3000);
    }
    console.log('TV reports installed catalog version %d (server has %d)', installed, wanted);
    if (installed < wanted) {
        throw new Error('the TV did not reach the catalog version the server stored');
    }

    const sources2 = await call('GET', '/playlists', bearer);
    (sources2.body || []).forEach((entry) => {
        console.log('  source %s  %s  %d videos  %s',
            entry.sourceId, String(entry.sourceType).padEnd(11), entry.videoCount, entry.displayName);
    });
    console.log('LOADED');
}

main().catch((error) => {
    console.error('FAILED: ' + error.message);
    process.exit(1);
});

