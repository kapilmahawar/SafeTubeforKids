/*
 * Tests for importing a YouTube playlist into the catalog.
 *
 * Run with:  node --test tv-app/scripts/dashboard-catalog-import.test.js
 *
 * What is under test is the *catalog* half of an import: what the editor does with a playlist that
 * has already been resolved. Resolution itself (YouTube, NewPipe, pagination) is tested in the JVM
 * suite, and the two are deliberately separate, because the whole point of the split is that
 * resolving writes nothing and the catalog changes in exactly one atomic document write.
 *
 * The fake server below enforces the same version rule the real routes do, so the conflict and
 * no-change cases mean something here.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const CatalogEditor = require('../app/src/main/assets/catalog-editor.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

function node(id, parentId, nodeType, title, position, extra) {
    return Object.assign({
        id: id, parentId: parentId, nodeType: nodeType, title: title, position: position,
        enabled: true, youtubeVideoId: null, youtubePlaylistId: null,
        thumbnailMode: 'AUTO', thumbnailVideoId: null, thumbnailUrl: null,
        createdAt: 1700000000000, updatedAt: 1700000000000,
    }, extra || {});
}

/** A shelf with a container and a hand-curated video, plus a second shelf. The W4 starting point. */
function document(version) {
    return {
        schemaVersion: 2,
        catalogVersion: version === undefined ? 5 : version,
        nodes: [
            node('cat-cartoon', null, 'CATEGORY', 'Cartoons', 0),
            node('i-cocomelon', 'cat-cartoon', 'SUBCATEGORY', 'Cocomelon', 0, { youtubePlaylistId: 'PLcocomelon' }),
            node('i-cocomelon#a', 'i-cocomelon', 'VIDEO', 'Episode A', 0, { youtubeVideoId: 'vidA', youtubePlaylistId: 'PLcocomelon' }),
            node('i-cocomelon#b', 'i-cocomelon', 'VIDEO', 'Episode B', 1, { youtubeVideoId: 'vidB', youtubePlaylistId: 'PLcocomelon' }),
            node('i-handpicked', 'cat-cartoon', 'VIDEO', 'Hand Picked', 1, { youtubeVideoId: 'vidHand' }),
            node('i-hidden', 'cat-cartoon', 'VIDEO', 'Parent Hid This', 2, { youtubeVideoId: 'vidHidden', enabled: false }),
            node('cat-favourites', null, 'CATEGORY', 'Favourites', 1),
        ],
    };
}

const deterministicIds = (() => {
    let counter = 0;
    return () => {
        counter = 0;
        return (type) => 'imp-' + String(type).toLowerCase() + '-' + (++counter);
    };
})();

function openEditor(version) {
    return CatalogEditor.open(document(version), { newId: deterministicIds() });
}

function childrenOf(session, parentId) {
    return CatalogEditor.childrenOf(session, parentId);
}

function videoIdsUnder(session, parentId) {
    return childrenOf(session, parentId)
        .filter((n) => n.nodeType === 'VIDEO')
        .map((n) => n.youtubeVideoId);
}

function titlesUnder(session, parentId) {
    return childrenOf(session, parentId).map((n) => n.title);
}

function assertCanonical(session) {
    const groups = {};
    session.nodes.forEach((n) => {
        const key = n.parentId || '<root>';
        (groups[key] = groups[key] || []).push(n);
    });
    Object.keys(groups).forEach((key) => {
        const positions = groups[key].map((n) => n.position).sort((a, b) => a - b);
        assert.deepEqual(positions, positions.map((_, index) => index), 'positions under ' + key);
    });
    assert.deepEqual(CatalogEditor.problems(session), []);
}

function videos(...pairs) {
    return pairs.map(([videoId, title]) => ({ videoId: videoId, title: title }));
}

function importInto(session, parentId, playlistId, list) {
    const result = CatalogEditor.importPlaylist(session, {
        parentId: parentId, playlistId: playlistId, videos: list,
    });
    assert.equal(result.ok, true, 'expected the import to be applied: ' + result.reason);
    return result;
}

/** The same fake server the editor's own suite uses: one document, one version, 409 on a stale write. */
function fakeServer(initial) {
    const state = {
        version: initial.catalogVersion,
        nodes: JSON.parse(JSON.stringify(initial.nodes)),
    };
    return {
        version: () => state.version,
        nodes: () => JSON.parse(JSON.stringify(state.nodes)),
        get: () => Promise.resolve({
            status: 200,
            data: { schemaVersion: 2, catalogVersion: state.version, nodes: JSON.parse(JSON.stringify(state.nodes)) },
        }),
        put: (body) => {
            if (body.expectedCatalogVersion !== state.version) {
                return Promise.resolve({ status: 409, data: { error: 'Catalog version conflict', catalogVersion: state.version } });
            }
            state.version += 1;
            state.nodes = JSON.parse(JSON.stringify(body.nodes));
            return Promise.resolve({
                status: 200,
                data: { schemaVersion: 2, catalogVersion: state.version, nodes: JSON.parse(JSON.stringify(state.nodes)) },
            });
        },
    };
}

/** A resolver that answers like the real `/catalog/import/resolve` route. */
function resolver(payload, status) {
    const calls = [];
    const fn = (url) => {
        calls.push(url);
        return Promise.resolve({ status: status === undefined ? 200 : status, data: payload });
    };
    fn.calls = calls;
    return fn;
}

const resolved = (overrides) => Object.assign({
    sourceType: 'yt_playlist',
    sourceId: 'PLimported',
    title: 'Imported Playlist',
    videos: videos(['vidX', 'X'], ['vidY', 'Y']),
    truncated: false,
    unusableItems: 0,
    approved: false,
}, overrides || {});

// --- basic import -------------------------------------------------------------------------

test('a playlist imports into a category as ordinary videos', () => {
    const before = openEditor();
    const { session, summary } = importInto(before, 'cat-cartoon', 'PLimported', videos(['vidX', 'Video X'], ['vidY', 'Video Y']));

    // The videos are appended after what the shelf already had, in playlist order.
    assert.deepEqual(titlesUnder(session, 'cat-cartoon'), ['Cocomelon', 'Hand Picked', 'Parent Hid This', 'Video X', 'Video Y']);
    assert.deepEqual(videoIdsUnder(session, 'cat-cartoon'), ['vidHand', 'vidHidden', 'vidX', 'vidY']);
    assert.equal(summary.added.length, 2);
    assert.equal(summary.kept.length, 0);
    assertCanonical(session);
});

test('a playlist imports into a subcategory the same way', () => {
    const before = openEditor();
    const { session } = importInto(before, 'i-cocomelon', 'PLimported', videos(['vidX', 'Video X']));

    assert.deepEqual(videoIdsUnder(session, 'i-cocomelon'), ['vidA', 'vidB', 'vidX']);
    assert.deepEqual(childrenOf(session, 'i-cocomelon').map((n) => n.position), [0, 1, 2]);
    assertCanonical(session);
});

test('the playlist itself never becomes a catalog node', () => {
    const before = openEditor();
    const { session } = importInto(before, 'cat-cartoon', 'PLimported', videos(['vidX', 'Video X']));

    // No node named after the playlist, no PLAYLIST node type, and no container invented for it: the
    // playlist's only trace is the source recorded on each video it brought in.
    assert.equal(session.nodes.some((n) => n.nodeType === 'PLAYLIST'), false);
    assert.equal(session.nodes.some((n) => n.title === 'Imported Playlist'), false);
    assert.equal(
        session.nodes.filter((n) => n.parentId === 'cat-cartoon' && n.nodeType === 'SUBCATEGORY').length,
        before.nodes.filter((n) => n.parentId === 'cat-cartoon' && n.nodeType === 'SUBCATEGORY').length,
        'no container was created for the playlist',
    );
    assert.equal(CatalogEditor.nodeById(session, 'PLimported'), null);

    // Only new VIDEO nodes, each carrying the source it came from.
    const imported = session.nodes.filter((n) => n.youtubePlaylistId === 'PLimported');
    assert.equal(imported.length, 1);
    assert.equal(imported[0].nodeType, 'VIDEO');
    assert.equal(imported[0].youtubeVideoId, 'vidX');
});

test('imported videos carry the id and title the playlist gave them, and nothing invented', () => {
    const before = openEditor();
    const { session } = importInto(before, 'cat-favourites', 'PLimported', videos(['vidX', 'Real YouTube Title']));

    const imported = session.nodes.find((n) => n.youtubeVideoId === 'vidX');
    assert.equal(imported.title, 'Real YouTube Title');
    assert.equal(imported.enabled, true);
    assert.equal(imported.thumbnailMode, 'AUTO');
    assert.equal(imported.thumbnailVideoId, null);
    assert.equal(imported.thumbnailUrl, null);
    assert.equal(imported.createdAt, 0, 'the server stamps a new node');
});

test('an item with no usable id is skipped and the rest still import', () => {
    const before = openEditor();
    const { session, summary } = importInto(before, 'cat-favourites', 'PLimported', [
        { videoId: 'vidX', title: 'Good One' },
        { videoId: '', title: 'Deleted video' },
        { videoId: '   ', title: 'Private video' },
        { videoId: 'vidY', title: 'Another Good One' },
    ]);

    assert.deepEqual(videoIdsUnder(session, 'cat-favourites'), ['vidX', 'vidY']);
    assert.equal(summary.skipped, 2);
    assertCanonical(session);
});

test('a long playlist imports in full, in one go', () => {
    const before = openEditor();
    const many = [];
    for (let i = 0; i < 250; i++) many.push({ videoId: 'bulk' + i, title: 'Bulk ' + i });

    const { session, summary } = importInto(before, 'cat-favourites', 'PLimported', many);

    assert.equal(summary.added.length, 250);
    assert.equal(videoIdsUnder(session, 'cat-favourites').length, 250);
    assert.deepEqual(childrenOf(session, 'cat-favourites').map((n) => n.position).slice(0, 3), [0, 1, 2]);
    assertCanonical(session);
});

// --- de-duplication -----------------------------------------------------------------------

test('the same video listed three times becomes one node', () => {
    const before = openEditor();
    const { session, summary } = importInto(before, 'cat-favourites', 'PLimported', videos(
        ['vidX', 'Video X'], ['vidX', 'Video X'], ['vidX', 'Video X'],
    ));

    assert.deepEqual(videoIdsUnder(session, 'cat-favourites'), ['vidX']);
    assert.equal(summary.added.length, 1);
});

test('re-importing the same playlist changes nothing at all', () => {
    const before = openEditor();
    const first = importInto(before, 'cat-cartoon', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y']));
    const positionsAfterFirst = childrenOf(first.session, 'cat-cartoon').map((n) => [n.id, n.position]);
    const idsAfterFirst = first.session.nodes.map((n) => n.id).sort();

    const second = importInto(first.session, 'cat-cartoon', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y']));

    assert.equal(second.summary.added.length, 0);
    assert.equal(second.summary.kept.length, 2);
    assert.equal(second.summary.hidden.length, 0);
    assert.deepEqual(childrenOf(second.session, 'cat-cartoon').map((n) => [n.id, n.position]), positionsAfterFirst);
    assert.deepEqual(second.session.nodes.map((n) => n.id).sort(), idsAfterFirst);
    assert.equal(CatalogEditor.isDirty(second.session), CatalogEditor.isDirty(first.session));
});

test('the order a playlist returns does not reorder the catalog', () => {
    // The phase's example: the shelf is A, B, C and the playlist lists C, A, B, D.
    const before = CatalogEditor.open({
        schemaVersion: 2, catalogVersion: 1,
        nodes: [
            node('cat-shelf', null, 'CATEGORY', 'Shelf', 0),
            node('vA', 'cat-shelf', 'VIDEO', 'A', 0, { youtubeVideoId: 'A', youtubePlaylistId: 'PLimported' }),
            node('vB', 'cat-shelf', 'VIDEO', 'B', 1, { youtubeVideoId: 'B', youtubePlaylistId: 'PLimported' }),
            node('vC', 'cat-shelf', 'VIDEO', 'C', 2, { youtubeVideoId: 'C', youtubePlaylistId: 'PLimported' }),
        ],
    }, { newId: deterministicIds() });

    const { session } = importInto(before, 'cat-shelf', 'PLimported', videos(
        ['C', 'C'], ['A', 'A'], ['B', 'B'], ['D', 'D'],
    ));

    assert.deepEqual(titlesUnder(session, 'cat-shelf'), ['A', 'B', 'C', 'D']);
    assert.deepEqual(childrenOf(session, 'cat-shelf').map((n) => n.id), ['vA', 'vB', 'vC', session.nodes.find((n) => n.youtubeVideoId === 'D').id]);
});

test('playlist order decides only where the new videos are appended', () => {
    const before = CatalogEditor.open({
        schemaVersion: 2, catalogVersion: 1,
        nodes: [
            node('cat-shelf', null, 'CATEGORY', 'Shelf', 0),
            node('vA', 'cat-shelf', 'VIDEO', 'A', 0, { youtubeVideoId: 'A' }),
            node('vB', 'cat-shelf', 'VIDEO', 'B', 1, { youtubeVideoId: 'B' }),
        ],
    }, { newId: deterministicIds() });

    // B, C, D, E -> A B (existing, untouched) then C D E in playlist order.
    const { session } = importInto(before, 'cat-shelf', 'PLimported', videos(
        ['B', 'B'], ['C', 'C'], ['D', 'D'], ['E', 'E'],
    ));

    assert.deepEqual(titlesUnder(session, 'cat-shelf'), ['A', 'B', 'C', 'D', 'E']);
});

test('an existing video keeps its identity, position, enabled state and metadata', () => {
    const before = openEditor();
    const original = CatalogEditor.nodeById(before, 'i-handpicked');

    // The playlist has it in a different place, disabled videos included, and a new title for it.
    const { session, summary } = importInto(before, 'cat-cartoon', 'PLimported', videos(
        ['vidHidden', 'Parent Hid This'], ['vidHand', 'A Different YouTube Title'], ['vidX', 'New'],
    ));

    const kept = CatalogEditor.nodeById(session, 'i-handpicked');
    assert.equal(kept.id, original.id);
    assert.equal(kept.position, original.position);
    assert.equal(kept.title, original.title, 'the catalog name wins over the playlist title');
    assert.equal(kept.enabled, true);
    assert.equal(kept.createdAt, original.createdAt);
    assert.equal(kept.youtubePlaylistId, 'PLimported', 'the missing source is recorded');

    const hiddenStillHidden = CatalogEditor.nodeById(session, 'i-hidden');
    assert.equal(hiddenStillHidden.enabled, false, 'a parent-disabled video stays disabled');
    assert.equal(summary.kept.length, 2);
    assert.deepEqual(summary.sourcesRecorded.sort(), ['i-handpicked', 'i-hidden']);
});

test('an existing source is never overwritten by a later import', () => {
    const before = openEditor();
    // vidA belongs to PLcocomelon and is imported again from PLimported.
    const { session, summary } = importInto(before, 'i-cocomelon', 'PLimported', videos(['vidA', 'Episode A']));

    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#a').youtubePlaylistId, 'PLcocomelon');
    assert.deepEqual(summary.keptOtherSource, ['i-cocomelon#a']);
    assert.equal(summary.sourcesRecorded.length, 0);
});

// --- multiple parents ---------------------------------------------------------------------

test('the same YouTube video can sit under two parents as two catalog nodes', () => {
    const before = openEditor();

    const first = importInto(before, 'cat-cartoon', 'PLimported', videos(['vidX', 'Video X']));
    const addedToCartoon = first.session.nodes.find((n) => n.youtubeVideoId === 'vidX');

    const second = importInto(first.session, 'cat-favourites', 'PLimported', videos(['vidX', 'Video X']));
    const addedToFavourites = second.session.nodes.filter((n) => n.youtubeVideoId === 'vidX');

    assert.equal(addedToFavourites.length, 2, 'one node per parent, and no more');
    assert.notEqual(addedToCartoon.id, addedToFavourites.find((n) => n.parentId === 'cat-favourites').id);
    // The first node is untouched by the second import.
    assert.deepEqual(CatalogEditor.nodeById(second.session, addedToCartoon.id), addedToCartoon);
    assert.equal(CatalogEditor.nodeById(second.session, addedToCartoon.id).parentId, 'cat-cartoon');
});

// --- source removal and reappearance ------------------------------------------------------

test('a video removed from its source playlist is hidden, never deleted', () => {
    const before = openEditor();
    const first = importInto(before, 'cat-favourites', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y'], ['vidZ', 'Z']));
    const nodeZ = first.session.nodes.find((n) => n.youtubeVideoId === 'vidZ');

    // The playlist no longer lists Z.
    const second = importInto(first.session, 'cat-favourites', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y']));

    const afterZ = CatalogEditor.nodeById(second.session, nodeZ.id);
    assert.ok(afterZ, 'the node is still there');
    assert.equal(afterZ.enabled, false);
    assert.equal(afterZ.position, nodeZ.position, 'its position is kept for when it comes back');
    assert.deepEqual(second.summary.hidden, [nodeZ.id]);
    assertCanonical(second.session);
});

test('a video the parent curated by hand is never hidden by an import', () => {
    const before = openEditor();
    // 'i-handpicked' has no source: no playlist is allowed to decide it should disappear.
    const { session, summary } = importInto(before, 'cat-cartoon', 'PLimported', videos(['vidX', 'X']));

    assert.equal(CatalogEditor.nodeById(session, 'i-handpicked').enabled, true);
    assert.equal(CatalogEditor.nodeById(session, 'i-hidden').enabled, false);
    assert.deepEqual(summary.hidden, []);
});

test('a video another playlist accounts for is not hidden by this one', () => {
    const before = openEditor();
    // vidA is source-owned by PLcocomelon. Importing PLother (which does not list it) must not hide it.
    const { session, summary } = importInto(before, 'i-cocomelon', 'PLother', videos(['vidOther', 'Other']));

    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#a').enabled, true);
    assert.deepEqual(summary.hidden, []);
    assert.deepEqual(videoIdsUnder(session, 'i-cocomelon'), ['vidA', 'vidB', 'vidOther']);
});

test('a video that comes back to its playlist keeps its node, its position and its state', () => {
    const before = openEditor();
    const first = importInto(before, 'cat-favourites', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y']));
    const originalNode = first.session.nodes.find((n) => n.youtubeVideoId === 'vidY');

    const removed = importInto(first.session, 'cat-favourites', 'PLimported', videos(['vidX', 'X']));
    assert.equal(CatalogEditor.nodeById(removed.session, originalNode.id).enabled, false);

    const back = importInto(removed.session, 'cat-favourites', 'PLimported', videos(['vidX', 'X'], ['vidY', 'Y']));

    const returned = CatalogEditor.nodeById(back.session, originalNode.id);
    assert.ok(returned, 'the same node comes back, not a new one');
    assert.equal(back.session.nodes.filter((n) => n.youtubeVideoId === 'vidY').length, 1, 'no duplicate');
    assert.equal(returned.position, originalNode.position, 'its position was kept for it');
    assert.deepEqual(back.summary.added, []);
    // State: a hide is not undone automatically. A single `enabled` flag cannot tell "the source
    // dropped this" apart from "the parent switched this off", and putting a parent's own choice back
    // on screen because a playlist was refreshed is the one thing this must never do - so an import
    // never turns a video back on. The parent re-enables it in one click, in the panel.
    assert.equal(returned.enabled, false);
});

// --- invalid input ------------------------------------------------------------------------

test('importing into something that cannot hold videos is refused and changes nothing', () => {
    const before = openEditor();
    const nodesBefore = JSON.stringify(before.nodes);

    ['i-cocomelon#a', 'i-handpicked', null].forEach((target) => {
        const result = CatalogEditor.importPlaylist(before, {
            parentId: target, playlistId: 'PLimported', videos: videos(['vidX', 'X']),
        });
        assert.equal(result.ok, false, 'a ' + target + ' cannot hold videos');
    });

    assert.equal(JSON.stringify(before.nodes), nodesBefore);
});

test('importing without a playlist id is refused', () => {
    const before = openEditor();
    const result = CatalogEditor.importPlaylist(before, { parentId: 'cat-cartoon', playlistId: '  ', videos: [] });

    assert.equal(result.ok, false);
    assert.match(result.reason, /playlist has no id/);
    assert.equal(CatalogEditor.isDirty(before), false);
});

// --- the durable flow ---------------------------------------------------------------------

test('an import resolves first and publishes once', async () => {
    const server = fakeServer(document(5));
    const session = openEditor(5);
    const resolve = resolver(resolved());
    const puts = [];

    const result = await CatalogEditor.importPlaylistFrom(
        session,
        { url: 'https://www.youtube.com/playlist?list=PLimported', parentId: 'cat-cartoon' },
        resolve,
        (body) => { puts.push(body); return server.put(body); },
    );

    assert.equal(result.outcome, 'imported');
    assert.equal(resolve.calls.length, 1);
    assert.equal(puts.length, 1, 'one user action is one catalog document');
    assert.equal(puts[0].expectedCatalogVersion, 5);
    assert.equal(result.session.catalogVersion, 6);
    assert.deepEqual(videoIdsUnder(result.session, 'cat-cartoon'), ['vidHand', 'vidHidden', 'vidX', 'vidY']);
    assert.equal(result.summary.approved, false);
});

test('a playlist that cannot be resolved changes nothing and publishes nothing', async () => {
    const server = fakeServer(document(5));
    const session = openEditor(5);
    const puts = [];
    const before = JSON.stringify(session.nodes);

    const result = await CatalogEditor.importPlaylistFrom(
        session,
        { url: 'not a playlist', parentId: 'cat-cartoon' },
        resolver({ error: 'Not a valid YouTube URL' }, 400),
        (body) => { puts.push(body); return server.put(body); },
    );

    assert.equal(result.outcome, 'error');
    assert.equal(result.reason, 'Not a valid YouTube URL');
    assert.equal(puts.length, 0, 'nothing was published');
    assert.equal(JSON.stringify(session.nodes), before);
    assert.equal(server.version(), 5, 'and the version did not move');
});

test('a resolution failure during the walk leaves the catalog alone', async () => {
    const server = fakeServer(document(5));
    const session = openEditor(5);
    const puts = [];

    const result = await CatalogEditor.importPlaylistFrom(
        session,
        { url: 'https://www.youtube.com/playlist?list=PLimported', parentId: 'cat-cartoon' },
        resolver({ error: 'timed out' }, 502),
        (body) => { puts.push(body); return server.put(body); },
    );

    assert.equal(result.outcome, 'error');
    assert.match(result.reason, /timed out/);
    assert.equal(puts.length, 0);
    assert.equal(server.version(), 5);
    assert.equal(server.nodes().length, document(5).nodes.length);
});

test('a network failure while resolving is an error, not an import', async () => {
    const session = openEditor(5);
    let put = 0;

    const result = await CatalogEditor.importPlaylistFrom(
        session,
        { url: 'https://www.youtube.com/playlist?list=PLimported', parentId: 'cat-cartoon' },
        () => Promise.reject(new Error('offline')),
        () => { put++; return Promise.resolve({ status: 200, data: {} }); },
    );

    assert.equal(result.outcome, 'error');
    assert.equal(result.reason, 'offline');
    assert.equal(put, 0);
    assert.equal(CatalogEditor.isDirty(session), false);
});

test('re-importing an unchanged playlist publishes nothing and does not move the version', async () => {
    const server = fakeServer(document(5));
    const session = openEditor(5);
    const payload = resolved();
    const puts = [];

    const first = await CatalogEditor.importPlaylistFrom(
        session, { url: 'PLimported', parentId: 'cat-cartoon' }, resolver(payload),
        (body) => { puts.push(body); return server.put(body); },
    );
    assert.equal(first.outcome, 'imported');
    assert.equal(server.version(), 6);

    const second = await CatalogEditor.importPlaylistFrom(
        first.session, { url: 'PLimported', parentId: 'cat-cartoon' }, resolver(payload),
        (body) => { puts.push(body); return server.put(body); },
    );

    assert.equal(second.outcome, 'no-changes');
    assert.equal(puts.length, 1, 'the second import published nothing');
    assert.equal(server.version(), 6, 'and the version did not churn');
});

test('an import never overwrites a catalog that changed underneath it', async () => {
    const server = fakeServer(document(5));
    const session = openEditor(5);

    // Another editor publishes first.
    const other = CatalogEditor.addCategory(openEditor(5), { title: 'Theirs' }).session;
    await CatalogEditor.save(other, server.put);
    const serverAfterOther = server.nodes();
    assert.equal(server.version(), 6);

    const puts = [];
    const result = await CatalogEditor.importPlaylistFrom(
        session, { url: 'PLimported', parentId: 'cat-cartoon' }, resolver(resolved()),
        (body) => { puts.push(body); return server.put(body); },
    );

    assert.equal(result.outcome, 'conflict');
    assert.equal(puts.length, 1, 'exactly one attempt: no retry, no merge, no last-write-wins');
    assert.equal(result.serverVersion, 6);
    assert.deepEqual(server.nodes(), serverAfterOther, 'the newer catalog is untouched');
    assert.equal(server.version(), 6);
    // The parent's working copy still has the imported videos in it, ready to reconcile.
    assert.deepEqual(videoIdsUnder(result.session, 'cat-cartoon'), ['vidHand', 'vidHidden', 'vidX', 'vidY']);
});

test('an import the server refuses is reported and changes nothing', async () => {
    const session = openEditor(5);
    const refused = resolver(resolved());
    let put = 0;

    const result = await CatalogEditor.importPlaylistFrom(
        session, { url: 'PLimported', parentId: 'cat-cartoon' }, refused,
        () => { put++; return Promise.resolve({ status: 400, data: { error: 'Invalid catalog', details: ['nodes[3]: nope'] } }); },
    );

    assert.equal(result.outcome, 'rejected');
    assert.deepEqual(result.reasons, ['nodes[3]: nope']);
    assert.equal(put, 1);
    assert.equal(CatalogEditor.isDirty(session), false, 'the caller keeps its own working copy');
});

test('a truncated playlist is reported rather than hidden', async () => {
    const server = fakeServer(document(5));
    const result = await CatalogEditor.importPlaylistFrom(
        openEditor(5), { url: 'PLimported', parentId: 'cat-cartoon' },
        resolver(resolved({ truncated: true, unusableItems: 2 })),
        server.put,
    );

    assert.equal(result.outcome, 'imported');
    assert.equal(result.summary.truncated, true);
    assert.equal(result.summary.unusableItems, 2);
});

test('the editor warns when the shelf already imports that playlist', () => {
    const before = openEditor();
    const { summary } = importInto(before, 'cat-cartoon', 'PLcocomelon', videos(['vidX', 'X']));

    // cat-cartoon already holds the Cocomelon container, whose import is that same playlist.
    assert.equal(summary.existingContainerId, 'i-cocomelon');
});

test('the add flow resolves through the read-only endpoint and applies it with the editor model', () => {
    const app = fs.readFileSync(path.join(assets, 'app.js'), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n').map((line) => line.replace(/\/\/.*/, '')).join('\n');

    // The preview the parent confirms comes from the one resolve endpoint, which approves nothing.
    assert.match(app, /apiCall\('POST', '\/catalog\/import\/resolve'/);

    // Applying it is the model's job, not a second implementation of the import rules.
    assert.match(app, /CatalogEditor\.importPlaylist\(/);
    assert.match(app, /CatalogEditor\.addVideo\(/);

    // A destination is always a node that may hold a video, never every node.
    assert.match(app, /CatalogEditor\.validParentsFor\(state\.session, CatalogEditor\.VIDEO\)/);

    // And allowing the source is an explicit, separate request to the endpoint that approves -
    // which is what keeps "in my library" and "allowed to play" two different things.
    assert.match(app, /apiCall\('POST', '\/playlists'/);
    assert.match(app, /Let my child watch it/);
});
