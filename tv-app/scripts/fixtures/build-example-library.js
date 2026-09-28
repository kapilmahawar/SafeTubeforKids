/*
 * The example kids library: where the fixture comes from.
 *
 * `example-kids-library.yaml` is not typed by hand. It is produced by this script, and this script
 * asks YouTube - through the TV app's own resolver, over the same HTTP API the parent dashboard
 * uses - what is actually inside each official playlist before it writes anything down. Nothing here
 * invents a video id, a title or an ordering: an item exists in the fixture only because the
 * resolver returned it, or because it is listed in `PINNED` below with the source it was read from.
 *
 * The YAML it writes is written by `catalog-yaml.js` - the module the dashboard itself loads - and
 * then read back with the dashboard's own `readDocument`, so the committed file is round-tripped
 * through the real parser before it is trusted. A file this script produces is therefore exactly a
 * file the parent dashboard would export and re-import.
 *
 * Usage:
 *   node build-example-library.js --host 172.16.1.2 --pin 482913 [--out <dir>]
 *
 * Nothing is written to the device: this only reads `/catalog/import/resolve`.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const CatalogYaml = require(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'catalog-yaml.js'));

function arg(name, fallback) {
    const at = process.argv.indexOf('--' + name);
    return at >= 0 && process.argv[at + 1] ? process.argv[at + 1] : fallback;
}

const HOST = arg('host', '172.16.1.2');
const PIN = arg('pin', '482913');
const OUT = arg('out', __dirname);
const BASE = 'http://' + HOST + ':8080';
const VERIFIED = new Date().toISOString().slice(0, 10);

/**
 * The official sources the fixture is built from.
 *
 * Every playlist id below was read out of the channel's own RSS feed or its official site, not
 * guessed: see docs/EXAMPLE_KIDS_LIBRARY.md for the channel each one belongs to and how it was
 * confirmed. The counts are what the resolver returned when the fixture was built; they are printed
 * and recorded in the manifest so a later drift is visible rather than silent.
 */
const SOURCES = {
    cocomelonAnimal: {
        playlistId: 'PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE',
        channel: 'CoComelon - Nursery Rhymes',
        channelId: 'UCbCmjCuTUZos6Inko4u57UQ',
        url: 'https://www.youtube.com/playlist?list=PLb8WrhcvGhOjFm2xrfaUZq3ytKuWQL5wE',
    },
    peppaBirthday: {
        playlistId: 'PLFEgnf4tmQe-SPm9PXEZSKldOCzzBVby9',
        channel: 'Peppa Pig - Official Channel',
        channelId: 'UCAOtE1V7Ots4DjM8JLlrYgg',
        url: 'https://www.youtube.com/playlist?list=PLFEgnf4tmQe-SPm9PXEZSKldOCzzBVby9',
    },
    peppaBest: {
        playlistId: 'PLFEgnf4tmQe8dyOm2mZ8fSrZnwwIdoTAG',
        channel: 'Peppa Pig - Official Channel',
        channelId: 'UCAOtE1V7Ots4DjM8JLlrYgg',
        url: 'https://www.youtube.com/playlist?list=PLFEgnf4tmQe8dyOm2mZ8fSrZnwwIdoTAG',
    },
    chuchuClassics: {
        playlistId: 'PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ',
        channel: 'ChuChu TV Nursery Rhymes & Kids Songs',
        channelId: 'UCBnZ16ahKA2DZ_T5W0FPUXg',
        url: 'https://www.youtube.com/playlist?list=PLV-cxl3VSwWHa83pPHIealm1vg0Cdp3aZ',
    },
    // Bluey's official channel publishes no playlist this script could confirm as the channel's own,
    // so Bluey items are approved through the channel itself (a channel is a source like any other).
    blueyChannel: {
        playlistId: 'UCVzLLZkDuFGAE2BGdBuBNBg',
        channel: 'Bluey - Official Channel',
        channelId: 'UCVzLLZkDuFGAE2BGdBuBNBg',
        url: 'https://www.youtube.com/channel/UCVzLLZkDuFGAE2BGdBuBNBg',
    },
};

/**
 * The three Bluey items, pinned rather than resolved.
 *
 * A channel link is deliberately refused by the catalog resolver ("A whole channel can't be added
 * here"), so these were read from the channel's own RSS feed
 * (https://www.youtube.com/feeds/videos.xml?channel_id=UCVzLLZkDuFGAE2BGdBuBNBg) and each one is
 * confirmed in the manifest with that provenance. They are livestream-free uploads: a livestream
 * never reaches an end, which would make "what happens at the end of the queue" untestable.
 */
const PINNED_BLUEY = [
    { videoId: '7PBDE0051wc', title: 'FULL EPISODES 💙 🍼 Baby Bluey Rolls Over! 🥹 | Bluey', position: 0 },
    { videoId: 'mNt8QH-fyyY', title: 'FULL EPISODES 💙 🖍️ Creating a Hotel with Bluey and Bingo ✨ | Bluey', position: 1 },
    { videoId: 'rX0NbuympHQ', title: "Bluey and Bingo Can't Speak! 🤫 | Sibling Games 💙🧡 | Bluey", position: 2 },
];

/**
 * The one item that must not play.
 *
 * It is a real, public, resolvable Peppa Pig video that is simply **not** in any approved source, so
 * a refusal here can only be the security boundary - never a broken link.
 */
const UNAPPROVED = {
    videoId: 'hyacEIXP3oc',
    title: 'Peppa Pig Tales 2026 🍎 Peppa Learns to PACK Her SCHOOL LUNCHBOX! 🍱 BRAND NEW Peppa Pig Episodes',
    channel: 'Peppa Pig - Official Channel',
    url: 'https://www.youtube.com/watch?v=hyacEIXP3oc',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function token() {
    const response = await fetch(BASE + '/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: PIN }),
    });
    if (!response.ok) throw new Error('sign-in failed: HTTP ' + response.status);
    return (await response.json()).token;
}

/** What YouTube says is inside one of the approved playlists, through the app's own resolver. */
async function resolvePlaylist(bearer, source) {
    const response = await fetch(BASE + '/catalog/import/resolve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + bearer },
        body: JSON.stringify({ url: source.url }),
    });
    const body = await response.json();
    if (!response.ok || body.error) throw new Error(source.playlistId + ': ' + (body.error || response.status));
    const items = (body.videos || []).map((v) => ({
        videoId: v.videoId || v.youtubeVideoId,
        title: v.title,
        position: v.position,
    }));
    if (items.some((item) => !item.videoId)) {
        throw new Error(source.playlistId + ': the resolver returned an item without a video id');
    }
    return { title: body.title, items, truncated: !!body.truncated, unusable: body.unusableItems || 0 };
}

function node(fields) {
    return Object.assign({
        nodeType: 'VIDEO',
        position: 0,
        enabled: true,
        youtubeVideoId: null,
        youtubePlaylistId: null,
        thumbnailMode: 'AUTO',
        thumbnailVideoId: null,
        thumbnailUrl: null,
        createdAt: 0,
        updatedAt: 0,
    }, fields);
}

function category(id, title, position) {
    return node({ id, parentId: null, nodeType: 'CATEGORY', title, position });
}

function collection(id, parentId, title, position, playlistId, videos) {
    const holder = node({
        id, parentId, nodeType: 'SUBCATEGORY', title, position, youtubePlaylistId: playlistId,
    });
    const children = videos.map((video, index) => node({
        id: id + '-' + String(index + 1).padStart(2, '0'),
        parentId: id,
        title: video.title,
        position: index,
        // A video imported from a playlist keeps it, so the TV knows which source approves it.
        youtubeVideoId: video.videoId,
        youtubePlaylistId: playlistId,
        enabled: video.enabled !== false,
    }));
    return [holder].concat(children);
}

/**
 * A video node that came from a *channel*.
 *
 * `youtubePlaylistId` is left null on purpose, and this is a real limitation rather than a choice:
 * the catalog contract validates that field as a playlist id
 * (`CatalogPayloadValidator.youtubeProblems` -> `ContentSourceParser.playlistIdProblem`), so a
 * channel id - `UC…` - is refused there, and a video node has nowhere else to say which source it
 * came from. The channel videos here still play, because playback approval reads the TV's own cache
 * of the allowed source, not this field; but the dashboard's library screen reads *this* field, so it
 * will report them as "Can't play yet" while the channel is in fact allowed (`app.js` `isPlayable`).
 * That is recorded in docs/EXAMPLE_KIDS_LIBRARY.md as a finding, not hidden.
 */
function channelVideo(video, id, parentId, position) {
    return node({
        id, parentId, title: video.title, position,
        youtubeVideoId: video.videoId,
        youtubePlaylistId: null,
    });
}

/** The whole fixture, in catalog order. Category order is the order the child sees. */
function buildTree(resolved) {
    const nodes = [];
    const manifestItems = [];

    const record = (semanticName, n, source, expected) => manifestItems.push({
        name: semanticName,
        nodeId: n.id,
        type: n.nodeType,
        youtubeVideoId: n.youtubeVideoId || null,
        youtubePlaylistId: n.youtubePlaylistId || null,
        title: n.title,
        source: source.channel,
        sourceId: source.playlistId || source.channelId,
        sourceUrl: source.url,
        category: null, // filled in below
        position: n.position,
        enabled: n.enabled,
        expectedBehavior: expected,
    });

    // --- 1. CoComelon -------------------------------------------------------
    const coco = resolved.cocomelonAnimal.items;
    nodes.push(category('ex-cocomelon', 'CoComelon', 0));
    nodes.push(...collection('ex-cocomelon-animal', 'ex-cocomelon', 'CoComelon Animal Songs', 0,
        SOURCES.cocomelonAnimal.playlistId,
        [coco[0], coco[1], coco[2], Object.assign({}, coco[3], { enabled: false })]));
    nodes.push(node({
        id: 'ex-cocomelon-video-4', parentId: 'ex-cocomelon', title: coco[4].title, position: 1,
        youtubeVideoId: coco[4].videoId, youtubePlaylistId: SOURCES.cocomelonAnimal.playlistId,
    }));
    record('EXAMPLE_COCOMELON_PLAYLIST', nodes[1], SOURCES.cocomelonAnimal, 'OPENS_CONTAINER');
    record('EXAMPLE_COCOMELON_VIDEO_1', nodes[2], SOURCES.cocomelonAnimal, 'PLAYS');
    record('EXAMPLE_COCOMELON_VIDEO_2', nodes[3], SOURCES.cocomelonAnimal, 'PLAYS');
    record('EXAMPLE_COCOMELON_VIDEO_3', nodes[4], SOURCES.cocomelonAnimal, 'PLAYS');
    record('EXAMPLE_COCOMELON_HIDDEN', nodes[5], SOURCES.cocomelonAnimal, 'HIDDEN_FROM_THE_CHILD');
    record('EXAMPLE_COCOMELON_VIDEO_4', nodes[6], SOURCES.cocomelonAnimal, 'PLAYS');

    // --- 2. Bluey ----------------------------------------------------------
    nodes.push(category('ex-bluey', 'Bluey', 1));
    PINNED_BLUEY.forEach((video, index) => {
        const n = channelVideo(video, 'ex-bluey-video-' + (index + 1), 'ex-bluey', index);
        nodes.push(n);
        record('EXAMPLE_BLUEY_VIDEO_' + (index + 1), n, SOURCES.blueyChannel, 'PLAYS');
    });

    // --- 3. Peppa Pig ------------------------------------------------------
    const birthday = resolved.peppaBirthday.items;
    nodes.push(category('ex-peppa', 'Peppa Pig', 2));
    nodes.push(...collection('ex-peppa-birthday', 'ex-peppa', 'Peppa Pig Birthday Special', 0,
        SOURCES.peppaBirthday.playlistId, birthday));
    record('EXAMPLE_PEPPA_PLAYLIST', nodes[nodes.length - birthday.length - 1], SOURCES.peppaBirthday, 'OPENS_CONTAINER');
    for (let i = 0; i < birthday.length; i++) {
        record('EXAMPLE_PEPPA_PLAYLIST_ITEM_' + String(i + 1).padStart(2, '0'),
            nodes[nodes.length - birthday.length + i], SOURCES.peppaBirthday,
            i === 0 ? 'PLAYS_FIRST_ITEM' : (i === birthday.length - 1 ? 'PLAYS_LAST_ITEM' : 'PLAYS'));
    }
    const denied = node({
        id: 'ex-peppa-unapproved', parentId: 'ex-peppa', title: UNAPPROVED.title, position: 1,
        youtubeVideoId: UNAPPROVED.videoId,
    });
    nodes.push(denied);
    record('EXAMPLE_UNAPPROVED_VIDEO', denied,
        { channel: UNAPPROVED.channel, playlistId: UNAPPROVED.videoId, url: UNAPPROVED.url },
        'PLAYBACK_DENIED');

    // --- 4. ChuChu TV ------------------------------------------------------
    const chuchu = resolved.chuchuClassics.items;
    const longest = chuchu.reduce((best, item) => (item.title.length > best.title.length ? item : best), chuchu[0]);
    nodes.push(category('ex-chuchu', 'ChuChu TV', 3));
    nodes.push(...collection('ex-chuchu-classics', 'ex-chuchu', 'ChuChu TV Classics', 0,
        SOURCES.chuchuClassics.playlistId, [chuchu[0], chuchu[1]]));
    nodes.push(node({
        id: 'ex-chuchu-long-title', parentId: 'ex-chuchu', title: longest.title, position: 1,
        youtubeVideoId: longest.videoId, youtubePlaylistId: SOURCES.chuchuClassics.playlistId,
    }));
    record('EXAMPLE_CHUCHU_PLAYLIST', nodes[nodes.length - 4], SOURCES.chuchuClassics, 'OPENS_CONTAINER');
    record('EXAMPLE_CHUCHU_VIDEO_1', nodes[nodes.length - 3], SOURCES.chuchuClassics, 'PLAYS');
    record('EXAMPLE_CHUCHU_VIDEO_2', nodes[nodes.length - 2], SOURCES.chuchuClassics, 'PLAYS');
    record('EXAMPLE_CHUCHU_LONG_TITLE', nodes[nodes.length - 1], SOURCES.chuchuClassics,
        'PLAYS_TITLE_' + longest.title.length + '_CHARS');

    // --- 5. Mixed Cartoons: videos and a playlist, from more than one source --
    const peppaBest = resolved.peppaBest.items;
    nodes.push(category('ex-mixed', 'Mixed Cartoons', 4));
    nodes.push(channelVideo(PINNED_BLUEY[0], 'ex-mixed-bluey', 'ex-mixed', 0));
    nodes.push(node({
        id: 'ex-mixed-cocomelon', parentId: 'ex-mixed', title: coco[5].title, position: 1,
        youtubeVideoId: coco[5].videoId, youtubePlaylistId: SOURCES.cocomelonAnimal.playlistId,
    }));
    nodes.push(...collection('ex-mixed-peppa-best', 'ex-mixed', 'Peppa Pig Best Videos', 2,
        SOURCES.peppaBest.playlistId, [peppaBest[0], peppaBest[1], peppaBest[2]]));
    // The mixed shelf pushes seven nodes, so the named items are the last six of them, counted from
    // the end: the category itself is the seventh. Counting from the end rather than from the start
    // is what keeps this correct when a shelf above it gains an item.
    record('EXAMPLE_MIXED_BLUEY_VIDEO', nodes[nodes.length - 6], SOURCES.blueyChannel, 'PLAYS');
    record('EXAMPLE_MIXED_COCOMELON_VIDEO', nodes[nodes.length - 5], SOURCES.cocomelonAnimal, 'PLAYS');
    record('EXAMPLE_MIXED_PEPPA_PLAYLIST', nodes[nodes.length - 4], SOURCES.peppaBest, 'OPENS_CONTAINER');
    record('EXAMPLE_MIXED_PEPPA_VIDEO_1', nodes[nodes.length - 3], SOURCES.peppaBest, 'PLAYS');
    record('EXAMPLE_MIXED_PEPPA_VIDEO_2', nodes[nodes.length - 2], SOURCES.peppaBest, 'PLAYS');

    // --- 6/7. The two edge shelves -----------------------------------------
    nodes.push(category('ex-edge-one-video', 'One Video (edge case)', 5));
    nodes.push(node({
        id: 'ex-edge-one-video-01', parentId: 'ex-edge-one-video', title: coco[6].title, position: 0,
        youtubeVideoId: coco[6].videoId, youtubePlaylistId: SOURCES.cocomelonAnimal.playlistId,
    }));
    record('EXAMPLE_EDGE_ONE_VIDEO', nodes[nodes.length - 1], SOURCES.cocomelonAnimal, 'PLAYS');
    nodes.push(category('ex-edge-empty', 'Empty Shelf (edge case)', 6));
    manifestItems.push({
        name: 'EXAMPLE_EDGE_EMPTY_SHELF',
        nodeId: 'ex-edge-empty',
        type: 'CATEGORY',
        youtubeVideoId: null,
        youtubePlaylistId: null,
        title: 'Empty Shelf (edge case)',
        source: null,
        sourceId: null,
        sourceUrl: null,
        category: 'Empty Shelf (edge case)',
        position: 6,
        enabled: true,
        expectedBehavior: 'NOT_RENDERED_ON_THE_TV',
    });

    // Category names, for the manifest: the check that the child's order is the file's order.
    const byId = {};
    nodes.forEach((n) => { byId[n.id] = n; });
    const categoryOrder = nodes.filter((n) => n.nodeType === 'CATEGORY')
        .sort((a, b) => a.position - b.position).map((n) => ({ id: n.id, title: n.title, position: n.position }));
    manifestItems.forEach((item) => {
        const own = byId[item.nodeId];
        let walk = own ? own.parentId : null;
        while (walk) { item.category = byId[walk].title; walk = byId[walk].parentId; }
    });

    return { nodes, manifestItems, categoryOrder };
}

async function main() {
    const bearer = await token();
    const resolved = {};
    const sourceFacts = [];
    for (const key of ['cocomelonAnimal', 'peppaBirthday', 'peppaBest', 'chuchuClassics']) {
        const source = SOURCES[key];
        const result = await resolvePlaylist(bearer, source);
        resolved[key] = result;
        sourceFacts.push({
            key, channel: source.channel, channelId: source.channelId, playlistId: source.playlistId,
            url: source.url, title: result.title, videoCount: result.items.length,
            truncatedAtImportLimit: result.truncated, unusableItems: result.unusable,
        });
        console.log('%s  %s  %d items  %s', key.padEnd(16), source.playlistId, result.items.length, result.title);
        await sleep(500);
    }
    // The denial item must be genuinely outside every approved source, or the test proves nothing.
    for (const fact of sourceFacts) {
        if (resolved[fact.key].items.some((item) => item.videoId === UNAPPROVED.videoId)) {
            throw new Error('the unapproved example video is inside approved source ' + fact.playlistId);
        }
    }
    sourceFacts.push({
        key: 'blueyChannel', channel: SOURCES.blueyChannel.channel, channelId: SOURCES.blueyChannel.channelId,
        playlistId: null, url: SOURCES.blueyChannel.url,
        title: SOURCES.blueyChannel.channel, videoCount: PINNED_BLUEY.length,
        truncatedAtImportLimit: false, unusableItems: 0,
        note: 'items pinned from the channel RSS feed; a channel link is refused by the catalog resolver',
    });

    const tree = buildTree(resolved);
    const yaml = CatalogYaml.serialize(tree.nodes, { name: 'Example Kids Library' });

    // The file must survive the dashboard's own reader, or it is not a catalog file.
    const read = CatalogYaml.readDocument(yaml);
    if (!read.ok) throw new Error('the generated file does not read back: ' + read.problems.join('; '));
    const before = JSON.stringify(tree.nodes.map(comparable).sort(byId));
    const after = JSON.stringify(read.nodes.map(comparable).sort(byId));
    if (before !== after) {
        throw new Error('the generated file does not round-trip through the dashboard parser');
    }

    const header = [
        '# SafeTube example kids library (W11)',
        '#',
        '# Generated by scripts/fixtures/build-example-library.js on ' + VERIFIED + ' from the official',
        '# channels listed below, through this app\'s own YouTube resolver. Do not hand-edit the ids:',
        '# re-run the generator instead, and the file is rewritten from what YouTube actually returns.',
        '#',
        '# Load it in the parent dashboard: Settings -> Library file -> choose this file -> Import.',
        '# It is a catalog file, not permissions: a video here still only plays when its source is',
        '# one of your allowed sources.',
        '#',
        '# Sources: ' + sourceFacts.map((f) => f.channel + (f.playlistId ? ' (' + f.playlistId + ')' : '')).join(', '),
        '',
    ].join('\n');

    // Checked on the exact bytes that are committed, header and all: the header is made of comments
    // the parser ignores, and a claim that is only true of an intermediate string is not a claim
    // about the file.
    const written = header + yaml;
    const writtenRead = CatalogYaml.readDocument(written);
    if (!writtenRead.ok || writtenRead.nodes.length !== tree.nodes.length) {
        throw new Error('the file as written does not read back: ' +
            (writtenRead.ok ? writtenRead.nodes.length + ' nodes instead of ' + tree.nodes.length
                : writtenRead.problems.join('; ')));
    }

    fs.writeFileSync(path.join(OUT, 'example-kids-library.yaml'), written, 'utf8');

    const manifest = {
        name: 'SafeTube example kids library',
        schemaVersion: CatalogYaml.SCHEMA_VERSION,
        catalogName: 'Example Kids Library',
        generated: VERIFIED,
        generatedBy: 'tv-app/scripts/fixtures/build-example-library.js',
        device: { host: HOST },
        categoryOrder: tree.categoryOrder,
        nodeCount: tree.nodes.length,
        counts: {
            categories: tree.nodes.filter((n) => n.nodeType === 'CATEGORY').length,
            collections: tree.nodes.filter((n) => n.nodeType === 'SUBCATEGORY').length,
            videos: tree.nodes.filter((n) => n.nodeType === 'VIDEO').length,
            hidden: tree.nodes.filter((n) => n.enabled === false).length,
        },
        sources: sourceFacts,
        // The whole tree, exactly as stored, so a device test can compare the TV's own rows with the
        // file field by field instead of spot-checking a few titles.
        nodes: tree.nodes.map((n) => ({
            id: n.id,
            parentId: n.parentId,
            nodeType: n.nodeType,
            title: n.title,
            position: n.position,
            enabled: n.enabled,
            youtubeVideoId: n.youtubeVideoId,
            youtubePlaylistId: n.youtubePlaylistId,
        })),
        items: tree.manifestItems,
    };
    fs.writeFileSync(path.join(OUT, 'example-kids-library.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');

    console.log('wrote %s (%d nodes: %d categories, %d collections, %d videos, %d hidden)',
        path.join(OUT, 'example-kids-library.yaml'), tree.nodes.length,
        manifest.counts.categories, manifest.counts.collections, manifest.counts.videos, manifest.counts.hidden);
    console.log('wrote %s (%d named items)', path.join(OUT, 'example-kids-library.json'), manifest.items.length);
    console.log('category order: ' + tree.categoryOrder.map((c) => c.position + ':' + c.title).join(' | '));
}

function comparable(n) {
    return [n.id, n.parentId || '', n.nodeType, n.title, n.position, n.enabled, n.youtubeVideoId || '', n.youtubePlaylistId || ''].join('\u0000');
}

function byId(a, b) { return a < b ? -1 : (a > b ? 1 : 0); }

main().catch((error) => {
    console.error('FAILED: ' + error.message);
    process.exit(1);
});
