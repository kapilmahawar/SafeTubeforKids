/*
 * Tests for the parent's catalog editor model.
 *
 * Run with:  node --test tv-app/scripts/dashboard-catalog-editor.test.js
 *
 * `catalog-editor.js` holds every mutation a parent can make, as pure functions of a session, so all
 * of them can be tested here without a browser, a device or a server. What these tests cover is:
 *
 *  - each mutation's *result*: identities, content and subtrees, canonical positions, and the
 *    sibling order the parent asked for;
 *  - the mutations that must be refused, and that a refusal changes nothing;
 *  - the save/reload flow against a fake server that enforces the same version rule the real one
 *    does, including the 409 path;
 *  - the invariant that matters most: for every parent, positions are 0..n-1.
 *
 * The authoritative validator (YouTube identifiers, thumbnails, the whole tree) runs on the server
 * and is tested in the JVM suite. The fake server below only has to be honest about *versioning*,
 * which is what the browser-side flow is about.
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

/** The tree the phase describes, plus one disabled video. */
function document(version) {
    return {
        schemaVersion: 2,
        catalogVersion: version === undefined ? 7 : version,
        nodes: [
            node('cat-cartoon', null, 'CATEGORY', 'Cartoons', 0),
            node('i-cocomelon', 'cat-cartoon', 'SUBCATEGORY', 'Cocomelon', 0, { youtubePlaylistId: 'PLcocomelon' }),
            node('i-cocomelon#a', 'i-cocomelon', 'VIDEO', 'Video A', 0, { youtubeVideoId: 'vidA' }),
            node('i-cocomelon#b', 'i-cocomelon', 'VIDEO', 'Video B', 1, { youtubeVideoId: 'vidB' }),
            node('i-cocomelon#c', 'i-cocomelon', 'VIDEO', 'Video C', 2, { youtubeVideoId: 'vidC' }),
            node('i-bluey', 'cat-cartoon', 'SUBCATEGORY', 'Bluey', 1, { youtubePlaylistId: 'PLbluey' }),
            node('i-bluey#d', 'i-bluey', 'VIDEO', 'Video D', 0, { youtubeVideoId: 'vidD' }),
            node('i-direct-a', 'cat-cartoon', 'VIDEO', 'Direct Video A', 2, { youtubeVideoId: 'vidDirectA' }),
            node('i-direct-b', 'cat-cartoon', 'VIDEO', 'Direct Video B', 3, { youtubeVideoId: 'vidDirectB', enabled: false }),
            node('cat-music', null, 'CATEGORY', 'Music', 1),
            node('i-nursery', 'cat-music', 'SUBCATEGORY', 'Nursery', 0, { youtubePlaylistId: 'PLnursery' }),
        ],
    };
}

const deterministicIds = (() => {
    let counter = 0;
    return () => {
        counter = 0;
        return (type) => 'new-' + String(type).toLowerCase() + '-' + (++counter);
    };
})();

function openEditor(version) {
    return CatalogEditor.open(document(version), { newId: deterministicIds() });
}

/** Applies a mutation and insists it was accepted, returning the new session. */
function apply(session, result) {
    assert.equal(result.ok, true, 'expected the mutation to be accepted: ' + result.reason);
    assert.ok(result.session, 'an accepted mutation returns a session');
    return result.session;
}

function titles(session, parentId) {
    return CatalogEditor.childrenOf(session, parentId).map((n) => n.title);
}

function ids(session, parentId) {
    return CatalogEditor.childrenOf(session, parentId).map((n) => n.id);
}

/** The invariant, asserted after every single mutation in this file. */
function assertCanonical(session) {
    const groups = {};
    session.nodes.forEach((n) => {
        const key = n.parentId || '<root>';
        (groups[key] = groups[key] || []).push(n);
    });
    Object.keys(groups).forEach((key) => {
        const positions = groups[key].map((n) => n.position).sort((a, b) => a - b);
        assert.deepEqual(positions, positions.map((_, index) => index),
            'positions under ' + key + ' must be 0..n-1, got ' + JSON.stringify(positions));
    });
    assert.deepEqual(CatalogEditor.problems(session), [], 'the working copy must satisfy the structural rules');
}

// --- reading a session -------------------------------------------------------------------

test('opening a document gives a working copy at the server version', () => {
    const session = openEditor(7);

    assert.equal(session.catalogVersion, 7);
    assert.equal(CatalogEditor.isDirty(session), false);
    assert.deepEqual(titles(session, null), ['Cartoons', 'Music']);
    assert.deepEqual(titles(session, 'cat-cartoon'), ['Cocomelon', 'Bluey', 'Direct Video A', 'Direct Video B']);
    assert.equal(CatalogEditor.nodeById(session, 'i-bluey#d').parentId, 'i-bluey');
});

test('the document the editor sends is contract v2 and carries the read version', () => {
    const session = openEditor(7);

    const sent = CatalogEditor.toDocument(session);

    assert.equal(sent.schemaVersion, 2);
    assert.equal(sent.catalogVersion, 7);
    assert.equal(sent.nodes.length, session.nodes.length);
});

// --- create ------------------------------------------------------------------------------

test('a new category becomes the last root node, with a canonical position', () => {
    const session = apply(openEditor(), CatalogEditor.addCategory(openEditor(), { title: 'Learning' }));

    assert.deepEqual(titles(session, null), ['Cartoons', 'Music', 'Learning']);
    assert.deepEqual(CatalogEditor.childrenOf(session, null).map((n) => n.position), [0, 1, 2]);
    assertCanonical(session);
});

test('a new subcategory is appended inside the shelf it was added to', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.addSubcategory(before, { parentId: 'cat-cartoon', title: 'Peppa' }));

    assert.deepEqual(titles(session, 'cat-cartoon'), ['Cocomelon', 'Bluey', 'Direct Video A', 'Direct Video B', 'Peppa']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-cartoon').map((n) => n.position), [0, 1, 2, 3, 4]);
    assertCanonical(session);
});

test('a new direct video is appended next to the containers, and mixed children stay mixed', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.addVideo(before, {
        parentId: 'cat-cartoon', title: 'Fresh Video', youtubeVideoId: 'https://www.youtube.com/watch?v=vidFresh',
    }));

    const children = CatalogEditor.childrenOf(session, 'cat-cartoon');
    assert.deepEqual(children.map((n) => n.nodeType),
        ['SUBCATEGORY', 'SUBCATEGORY', 'VIDEO', 'VIDEO', 'VIDEO']);
    assert.deepEqual(children.map((n) => n.position), [0, 1, 2, 3, 4]);
    const added = CatalogEditor.nodeById(session, children[4].id);
    assert.equal(added.youtubeVideoId, 'vidFresh');
    assert.equal(added.title, 'Fresh Video');
    assert.equal(added.enabled, true);
    assertCanonical(session);
});

test('a new node gets an identifier that is not already used, and never an inherited one', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.addCategory(before, { title: 'Learning' }));

    const created = CatalogEditor.childrenOf(session, null).find((n) => n.title === 'Learning');
    assert.ok(created.id);
    assert.equal(session.nodes.filter((n) => n.id === created.id).length, 1);
    // Untouched nodes keep their identifiers.
    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#b', 'i-cocomelon#c']);
});

test('a new node carries no invented timestamps, so the server stamps it', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.addCategory(before, { title: 'Learning' }));

    const created = CatalogEditor.childrenOf(session, null).find((n) => n.title === 'Learning');
    assert.equal(created.createdAt, 0);
    assert.equal(created.updatedAt, 0);
});

test('creating is refused where the node type cannot live', () => {
    const session = openEditor();

    // A subcategory only belongs to a shelf, a video belongs to a shelf or a subcategory, and a
    // shelf only belongs at ROOT.
    assert.equal(CatalogEditor.addSubcategory(session, { parentId: 'i-bluey', title: 'Nope' }).ok, false);
    assert.equal(CatalogEditor.addVideo(session, { parentId: 'i-bluey#d', title: 'Nope', youtubeVideoId: 'vidX' }).ok, false);
    assert.equal(CatalogEditor.addVideo(session, { parentId: null, title: 'Nope', youtubeVideoId: 'vidX' }).ok, false);
    assert.equal(CatalogEditor.addSubcategory(session, { parentId: 'cat-absent', title: 'Nope' }).ok, false);
});

test('creating is refused without a name, or without a video id for a video', () => {
    const session = openEditor();

    assert.equal(CatalogEditor.addCategory(session, { title: '   ' }).ok, false);
    assert.equal(CatalogEditor.addSubcategory(session, { parentId: 'cat-cartoon', title: '' }).ok, false);
    assert.equal(CatalogEditor.addVideo(session, { parentId: 'cat-cartoon', title: 'Named', youtubeVideoId: '' }).ok, false);
    assert.equal(
        CatalogEditor.addVideo(session, { parentId: 'cat-cartoon', title: 'Named', youtubeVideoId: 'not a url' }).ok,
        false,
    );
});

// --- video identifiers -------------------------------------------------------------------

test('a pasted YouTube URL is reduced to the video id', () => {
    const cases = {
        'https://www.youtube.com/watch?v=DuXwFlL8Usk': 'DuXwFlL8Usk',
        'https://youtu.be/DuXwFlL8Usk': 'DuXwFlL8Usk',
        'https://www.youtube.com/shorts/DuXwFlL8Usk': 'DuXwFlL8Usk',
        'https://www.youtube.com/embed/DuXwFlL8Usk': 'DuXwFlL8Usk',
        'https://www.youtube.com/watch?list=PLx&v=DuXwFlL8Usk&t=30': 'DuXwFlL8Usk',
        '  DuXwFlL8Usk  ': 'DuXwFlL8Usk',
    };
    Object.keys(cases).forEach((input) => {
        assert.equal(CatalogEditor.videoIdFrom(input), cases[input], 'for ' + input);
    });

    assert.equal(CatalogEditor.videoIdFrom(''), null);
    assert.equal(CatalogEditor.videoIdFrom('has space'), null);
    assert.equal(CatalogEditor.videoIdFrom(null), null);
});

test('the editor never invents a title or a thumbnail from the identifier', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.addVideo(before, {
        parentId: 'cat-music', title: 'Twinkle', youtubeVideoId: 'DuXwFlL8Usk',
    }));

    const added = CatalogEditor.childrenOf(session, 'cat-music').find((n) => n.title === 'Twinkle');
    assert.equal(added.thumbnailMode, 'AUTO');
    assert.equal(added.thumbnailVideoId, null);
    assert.equal(added.thumbnailUrl, null);
    // The parent's name and the identifier stay separate.
    assert.equal(added.title.includes('DuXwFlL8Usk'), false);
});

// --- rename ------------------------------------------------------------------------------

test('a category can be renamed, keeping its identifier and everything under it', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.rename(before, { id: 'cat-cartoon', title: 'Cartoons & Songs' }));

    assert.equal(CatalogEditor.nodeById(session, 'cat-cartoon').title, 'Cartoons & Songs');
    assert.deepEqual(titles(session, 'cat-cartoon'), ['Cocomelon', 'Bluey', 'Direct Video A', 'Direct Video B']);
    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#b', 'i-cocomelon#c']);
    assertCanonical(session);
});

test('a subcategory can be renamed and keeps its playlist provenance', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.rename(before, { id: 'i-bluey', title: 'Bluey Episodes' }));

    const renamed = CatalogEditor.nodeById(session, 'i-bluey');
    assert.equal(renamed.title, 'Bluey Episodes');
    assert.equal(renamed.youtubePlaylistId, 'PLbluey');
    assert.deepEqual(ids(session, 'i-bluey'), ['i-bluey#d']);
    assertCanonical(session);
});

test('a video title is not parent-editable in this phase, and the editor says so', () => {
    const before = openEditor();
    const result = CatalogEditor.rename(before, { id: 'i-cocomelon#a', title: 'Renamed By Hand' });

    assert.equal(result.ok, false);
    assert.match(result.reason, /video keeps the name it was added with/);
});

test('renaming is refused for a blank name or an unknown node', () => {
    const session = openEditor();

    assert.equal(CatalogEditor.rename(session, { id: 'cat-cartoon', title: '  ' }).ok, false);
    assert.equal(CatalogEditor.rename(session, { id: 'absent', title: 'X' }).ok, false);
});

// --- enable / disable --------------------------------------------------------------------

test('every kind of node can be disabled and enabled again', () => {
    let session = openEditor();

    session = apply(session, CatalogEditor.setEnabled(session, { id: 'cat-music', enabled: false }));
    session = apply(session, CatalogEditor.setEnabled(session, { id: 'i-bluey', enabled: false }));
    session = apply(session, CatalogEditor.setEnabled(session, { id: 'i-cocomelon#a', enabled: false }));

    assert.equal(CatalogEditor.nodeById(session, 'cat-music').enabled, false);
    assert.equal(CatalogEditor.nodeById(session, 'i-bluey').enabled, false);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#a').enabled, false);

    session = apply(session, CatalogEditor.setEnabled(session, { id: 'i-bluey', enabled: true }));
    assert.equal(CatalogEditor.nodeById(session, 'i-bluey').enabled, true);
    assertCanonical(session);
});

test('disabling a container does not disable, delete or renumber its children', () => {
    const before = openEditor();
    const childrenBefore = CatalogEditor.childrenOf(before, 'i-cocomelon').map((n) => [n.id, n.enabled, n.position]);

    const session = apply(before, CatalogEditor.setEnabled(before, { id: 'i-cocomelon', enabled: false }));

    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon').enabled, false);
    assert.deepEqual(
        CatalogEditor.childrenOf(session, 'i-cocomelon').map((n) => [n.id, n.enabled, n.position]),
        childrenBefore,
    );
    assertCanonical(session);
});

test('a disabled node keeps existing in the document that is saved', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.setEnabled(before, { id: 'i-direct-b', enabled: false }));

    const sent = CatalogEditor.toDocument(session);
    const stored = sent.nodes.find((n) => n.id === 'i-direct-b');

    assert.equal(stored.enabled, false);
    assert.equal(sent.nodes.length, before.nodes.length);
});

// --- delete ------------------------------------------------------------------------------

test('deleting a category removes its whole subtree and closes the gap', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.remove(before, { id: 'cat-cartoon' }));

    assert.deepEqual(titles(session, null), ['Music']);
    assert.deepEqual(CatalogEditor.childrenOf(session, null).map((n) => n.position), [0]);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon'), null);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#c'), null);
    assert.equal(CatalogEditor.nodeById(session, 'i-bluey#d'), null);
    assertCanonical(session);
});

test('deleting a subcategory removes the videos inside it', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.remove(before, { id: 'i-cocomelon' }));

    assert.deepEqual(titles(session, 'cat-cartoon'), ['Bluey', 'Direct Video A', 'Direct Video B']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-cartoon').map((n) => n.position), [0, 1, 2]);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#a'), null);
    assertCanonical(session);
});

test('deleting a video removes only that video', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.remove(before, { id: 'i-cocomelon#b' }));

    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#c']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'i-cocomelon').map((n) => n.position), [0, 1]);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon').title, 'Cocomelon');
    assertCanonical(session);
});

test('deleting the middle of a mixed sibling list normalizes the rest', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.remove(before, { id: 'i-bluey' }));

    assert.deepEqual(titles(session, 'cat-cartoon'), ['Cocomelon', 'Direct Video A', 'Direct Video B']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-cartoon').map((n) => n.position), [0, 1, 2]);
    assertCanonical(session);
});

test('deleting an unknown node is refused', () => {
    const session = openEditor();
    assert.equal(CatalogEditor.remove(session, { id: 'absent' }).ok, false);
});

// --- reorder -----------------------------------------------------------------------------

test('a category can be moved up and down among the shelves', () => {
    let session = openEditor();

    session = apply(session, CatalogEditor.moveUp(session, { id: 'cat-music' }));
    assert.deepEqual(ids(session, null), ['cat-music', 'cat-cartoon']);

    session = apply(session, CatalogEditor.moveDown(session, { id: 'cat-music' }));
    assert.deepEqual(ids(session, null), ['cat-cartoon', 'cat-music']);
    assert.deepEqual(CatalogEditor.childrenOf(session, null).map((n) => n.position), [0, 1]);
    assertCanonical(session);
});

test('a subcategory can be moved up and down among mixed siblings', () => {
    let session = openEditor();

    // Cartoons: Cocomelon(0), Bluey(1), Direct A(2), Direct B(3)
    session = apply(session, CatalogEditor.moveDown(session, { id: 'i-bluey' }));
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-direct-a', 'i-bluey', 'i-direct-b']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-cartoon').map((n) => n.position), [0, 1, 2, 3]);

    session = apply(session, CatalogEditor.moveUp(session, { id: 'i-bluey' }));
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-bluey', 'i-direct-a', 'i-direct-b']);
    assertCanonical(session);
});

test('a direct video moves the same way as a container', () => {
    let session = openEditor();

    session = apply(session, CatalogEditor.moveUp(session, { id: 'i-direct-b' }));
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-bluey', 'i-direct-b', 'i-direct-a']);

    session = apply(session, CatalogEditor.moveUp(session, { id: 'i-direct-b' }));
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-direct-b', 'i-bluey', 'i-direct-a']);
    assertCanonical(session);
});

test('moving the first item up, or the last one down, is refused and changes nothing', () => {
    const session = openEditor();
    const before = JSON.stringify(session.nodes);

    assert.equal(CatalogEditor.moveUp(session, { id: 'i-cocomelon' }).ok, false);
    assert.equal(CatalogEditor.moveDown(session, { id: 'i-direct-b' }).ok, false);
    assert.equal(JSON.stringify(session.nodes), before);
});

test('a reorder preserves every identity, every field and every subtree', () => {
    const before = openEditor();
    const after = apply(before, CatalogEditor.moveUp(before, { id: 'i-bluey' }));

    const byId = {};
    before.nodes.forEach((n) => { byId[n.id] = n; });
    assert.equal(after.nodes.length, before.nodes.length);
    after.nodes.forEach((n) => {
        const original = byId[n.id];
        assert.ok(original, n.id + ' must still exist');
        // Everything except the position it now has.
        assert.deepEqual(Object.assign({}, n, { position: original.position }), original);
    });
    assert.deepEqual(ids(after, 'i-bluey'), ['i-bluey#d'], 'the subtree is untouched');
});

// --- move between parents ----------------------------------------------------------------

test('a subcategory moves to another shelf and both sibling groups are renumbered', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.moveTo(before, { id: 'i-bluey', parentId: 'cat-music' }));

    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-direct-a', 'i-direct-b']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-cartoon').map((n) => n.position), [0, 1, 2]);
    assert.deepEqual(ids(session, 'cat-music'), ['i-nursery', 'i-bluey']);
    assert.deepEqual(CatalogEditor.childrenOf(session, 'cat-music').map((n) => n.position), [0, 1]);
    assert.deepEqual(ids(session, 'i-bluey'), ['i-bluey#d'], 'the subtree moved with it');
    assertCanonical(session);
});

test('a video moves from one container to another, at a requested position', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.moveTo(before, {
        id: 'i-cocomelon#b', parentId: 'i-bluey', position: 0,
    }));

    assert.deepEqual(ids(session, 'i-bluey'), ['i-cocomelon#b', 'i-bluey#d']);
    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#c']);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#b').youtubeVideoId, 'vidB');
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon#b').title, 'Video B');
    assertCanonical(session);
});

test('a direct video moves into a container and out again', () => {
    let session = openEditor();

    session = apply(session, CatalogEditor.moveTo(session, { id: 'i-direct-a', parentId: 'i-cocomelon' }));
    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#b', 'i-cocomelon#c', 'i-direct-a']);
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-bluey', 'i-direct-b']);

    session = apply(session, CatalogEditor.moveTo(session, { id: 'i-direct-a', parentId: 'cat-cartoon' }));
    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-cocomelon', 'i-bluey', 'i-direct-b', 'i-direct-a']);
    assertCanonical(session);
});

test('the identifier and content survive a move, so nothing is recreated', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.moveTo(before, { id: 'i-cocomelon', parentId: 'cat-music' }));

    const moved = CatalogEditor.nodeById(session, 'i-cocomelon');
    assert.equal(moved.id, 'i-cocomelon');
    assert.equal(moved.title, 'Cocomelon');
    assert.equal(moved.youtubePlaylistId, 'PLcocomelon');
    assert.equal(moved.createdAt, 1700000000000);
    assert.deepEqual(ids(session, 'i-cocomelon'), ['i-cocomelon#a', 'i-cocomelon#b', 'i-cocomelon#c']);
    assert.equal(session.nodes.length, before.nodes.length);
});

test('an impossible destination is refused', () => {
    const session = openEditor();
    const before = JSON.stringify(session.nodes);

    // A shelf belongs at ROOT only.
    assert.equal(CatalogEditor.moveTo(session, { id: 'cat-music', parentId: 'cat-cartoon' }).ok, false);
    // A container belongs to a shelf, not to another container.
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-bluey', parentId: 'i-cocomelon' }).ok, false);
    // A video belongs to a shelf or a container, not to another video, and not to ROOT.
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-cocomelon#a', parentId: 'i-bluey#d' }).ok, false);
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-cocomelon#a', parentId: null }).ok, false);
    // A container cannot be moved inside the videos it holds.
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-cocomelon', parentId: 'i-cocomelon#a' }).ok, false);
    // A node cannot be placed inside itself, and an unknown destination does not exist.
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-bluey', parentId: 'i-bluey' }).ok, false);
    assert.equal(CatalogEditor.moveTo(session, { id: 'i-bluey', parentId: 'cat-absent' }).ok, false);

    assert.equal(JSON.stringify(session.nodes), before, 'a refused move changes nothing');
});

test('moving to the same parent at a position reorders it there', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.moveTo(before, { id: 'i-direct-b', parentId: 'cat-cartoon', position: 0 }));

    assert.deepEqual(ids(session, 'cat-cartoon'), ['i-direct-b', 'i-cocomelon', 'i-bluey', 'i-direct-a']);
    assertCanonical(session);
});

test('a requested position beyond the end appends', () => {
    const before = openEditor();
    const session = apply(before, CatalogEditor.moveTo(before, { id: 'i-cocomelon', parentId: 'cat-music', position: 99 }));

    assert.deepEqual(ids(session, 'cat-music'), ['i-nursery', 'i-cocomelon']);
    assertCanonical(session);
});

// --- the invariant, over a whole editing session ------------------------------------------

test('after a run of mixed edits every sibling list is still 0..n-1', () => {
    let session = openEditor();

    session = apply(session, CatalogEditor.addCategory(session, { title: 'Learning' }));
    const learningId = CatalogEditor.childrenOf(session, null).find((n) => n.title === 'Learning').id;
    session = apply(session, CatalogEditor.addSubcategory(session, { parentId: 'cat-cartoon', title: 'Peppa' }));
    session = apply(session, CatalogEditor.addVideo(session, { parentId: 'cat-cartoon', title: 'Fresh', youtubeVideoId: 'vidFresh' }));
    session = apply(session, CatalogEditor.rename(session, { id: 'cat-cartoon', title: 'Cartoons!' }));
    session = apply(session, CatalogEditor.setEnabled(session, { id: 'i-cocomelon', enabled: false }));
    session = apply(session, CatalogEditor.moveUp(session, { id: 'i-direct-b' }));
    session = apply(session, CatalogEditor.moveTo(session, { id: 'i-bluey', parentId: 'cat-music' }));
    session = apply(session, CatalogEditor.remove(session, { id: 'i-cocomelon#b' }));
    session = apply(session, CatalogEditor.moveDown(session, { id: 'cat-cartoon' }));

    assertCanonical(session);
    assert.deepEqual(ids(session, null), ['cat-music', 'cat-cartoon', learningId]);
    assert.deepEqual(titles(session, null), ['Music', 'Cartoons!', 'Learning']);
    assert.equal(CatalogEditor.nodeById(session, 'i-cocomelon').enabled, false);
    assert.equal(CatalogEditor.nodeById(session, 'i-direct-b').enabled, false, 'the disabled flag is untouched by reordering');
    assert.deepEqual(ids(session, 'i-bluey'), ['i-bluey#d'], 'the moved container kept its subtree');
});

// --- the save flow, against a server that enforces the version rule -----------------------

/**
 * A stand-in for the real server: it keeps one document and one version, refuses a write whose
 * `expectedCatalogVersion` is not the version it holds (the rule the JVM suite pins on the real
 * routes), and stamps the nodes it stores.
 */
function fakeServer(initial, options) {
    const refuse = (options && options.refuse) || null;
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
                return Promise.resolve({
                    status: 409,
                    data: { error: 'Catalog version conflict', catalogVersion: state.version },
                });
            }
            const found = CatalogEditor.problems({ nodes: body.nodes, savedNodes: body.nodes });
            if (found.length) {
                return Promise.resolve({ status: 400, data: { error: 'Invalid catalog', details: found } });
            }
            if (refuse) {
                return Promise.resolve({ status: 500, data: { error: 'Catalog could not be persisted' } });
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

test('saving sends the version it read, and adopts the version the server assigned', async () => {
    const server = fakeServer(document(7));
    const session = openEditor(7);
    const edited = apply(session, CatalogEditor.addCategory(session, { title: 'Learning' }));

    let sentBody = null;
    const result = await CatalogEditor.save(edited, (body) => {
        sentBody = body;
        return server.put(body);
    });

    assert.equal(sentBody.expectedCatalogVersion, 7, 'the browser never invents a version');
    assert.equal(sentBody.schemaVersion, 2);
    assert.equal(result.outcome, 'saved');
    assert.equal(result.session.catalogVersion, 8, 'the session adopts the server version');
    assert.equal(CatalogEditor.isDirty(result.session), false);
    assert.deepEqual(titles(result.session, null), ['Cartoons', 'Music', 'Learning']);
    assert.equal(server.version(), 8);
});

test('a save sends the whole tree as one document, not a sequence of operations', async () => {
    const server = fakeServer(document(7));
    const session = openEditor(7);

    const requests = [];
    const edited = apply(
        apply(session, CatalogEditor.addCategory(session, { title: 'Learning' })),
        CatalogEditor.setEnabled(session, { id: 'i-direct-b', enabled: true }),
    );
    await CatalogEditor.save(edited, (body) => {
        requests.push(body);
        return server.put(body);
    });

    assert.equal(requests.length, 1, 'one user action is one atomic document write');
    assert.equal(requests[0].nodes.length, edited.nodes.length);
});

test('a conflict is reported, keeps the working copy, and does not touch the server', async () => {
    const server = fakeServer(document(7));
    const myCopy = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Mine' }));

    // Somebody else publishes first.
    const other = apply(openEditor(7), CatalogEditor.rename(openEditor(7), { id: 'cat-music', title: 'Their Name' }));
    await CatalogEditor.save(other, server.put);
    const serverAfterOther = server.nodes();
    assert.equal(server.version(), 8);

    // The stale editor saves the document it read at version 7.
    const result = await CatalogEditor.save(myCopy, server.put);

    assert.equal(result.outcome, 'conflict');
    assert.equal(result.serverVersion, 8, 'the view can say which version the server is on');
    assert.deepEqual(titles(result.session, null), ['Cartoons', 'Music', 'Mine'], 'the unsaved edits are still here');
    assert.equal(CatalogEditor.isDirty(result.session), true);
    assert.deepEqual(server.nodes(), serverAfterOther, 'the server document was not overwritten');
    assert.equal(server.version(), 8, 'and the version did not move');
});

test('a conflict never triggers an automatic retry or a forced write', async () => {
    const server = fakeServer(document(7));
    await CatalogEditor.save(apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Theirs' })), server.put);

    let calls = 0;
    const result = await CatalogEditor.save(openEditor(7), (body) => {
        calls++;
        return server.put(body);
    });

    assert.equal(result.outcome, 'conflict');
    assert.equal(calls, 1, 'exactly one attempt: no silent retry, no last-write-wins');
});

test('a rejected document is reported with the server reasons and changes nothing', async () => {
    const server = fakeServer(document(7));
    const session = openEditor(7);
    // A video with no identifier: the editor refuses to build it, so the document is forced.
    const broken = CatalogEditor.toDocument(session);
    broken.nodes.push(node('i-bad', 'cat-cartoon', 'VIDEO', 'No Identifier', 4));

    const result = await CatalogEditor.save(
        { catalogVersion: 7, nodes: broken.nodes, savedNodes: broken.nodes },
        server.put,
    );

    assert.equal(result.outcome, 'rejected');
    assert.ok(result.reasons.length > 0);
    assert.match(result.reasons.join(' '), /youtubeVideoId/);
    assert.equal(server.version(), 7);
    assert.equal(server.nodes().length, document(7).nodes.length);
});

test('a failed request leaves the edits unsaved and still in the browser', async () => {
    const server = fakeServer(document(7), { refuse: true });
    const edited = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Learning' }));

    const result = await CatalogEditor.save(edited, server.put);

    assert.equal(result.outcome, 'failed');
    assert.equal(CatalogEditor.isDirty(result.session), true);
    assert.deepEqual(titles(result.session, null), ['Cartoons', 'Music', 'Learning']);
    assert.equal(server.version(), 7);
});

test('a network failure is a failure, never a Saved', async () => {
    const edited = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Learning' }));

    const result = await CatalogEditor.save(edited, () => Promise.reject(new Error('offline')));

    assert.equal(result.outcome, 'failed');
    assert.equal(result.reason, 'offline');
    assert.equal(CatalogEditor.isDirty(result.session), true);
});

test('an expired session is reported as a failure rather than an edit problem', async () => {
    const result = await CatalogEditor.save(openEditor(7), () => Promise.resolve({ status: 401, data: { error: 'Session expired' } }));

    assert.equal(result.outcome, 'failed');
    assert.match(result.reason, /session expired/);
});

// --- refresh, reload and persistence ------------------------------------------------------

test('a refresh reads the server document, so saved changes come back', async () => {
    const server = fakeServer(document(7));
    const session = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Learning' }));
    await CatalogEditor.save(session, server.put);

    // A refresh is a brand-new session built from what the server serves - nothing local is consulted.
    const reloaded = await CatalogEditor.reload(server.get, { newId: deterministicIds() });

    assert.equal(reloaded.outcome, 'reloaded');
    assert.equal(reloaded.session.catalogVersion, 8);
    assert.deepEqual(titles(reloaded.session, null), ['Cartoons', 'Music', 'Learning']);
    assert.equal(CatalogEditor.isDirty(reloaded.session), false);
});

test('a refresh discards unsaved edits, and the editor can say that it would', () => {
    const session = openEditor(7);
    const edited = apply(session, CatalogEditor.addCategory(session, { title: 'Never Saved' }));

    assert.equal(CatalogEditor.isDirty(session), false);
    assert.equal(CatalogEditor.isDirty(edited), true);

    // A refresh builds the working copy from the server document again: the unsaved shelf is gone.
    const afterRefresh = CatalogEditor.open(document(7), { newId: deterministicIds() });
    assert.deepEqual(titles(afterRefresh, null), ['Cartoons', 'Music']);
    assert.equal(CatalogEditor.isDirty(afterRefresh), false);
});

test('reloading is the explicit way out of a conflict, and it takes the server state', async () => {
    const server = fakeServer(document(7));
    await CatalogEditor.save(apply(openEditor(7), CatalogEditor.rename(openEditor(7), { id: 'cat-music', title: 'Their Name' })), server.put);

    const mine = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Mine' }));
    const conflicted = await CatalogEditor.save(mine, server.put);
    assert.equal(conflicted.outcome, 'conflict');

    const reloaded = await CatalogEditor.reload(server.get, { newId: deterministicIds() });

    assert.equal(reloaded.outcome, 'reloaded');
    assert.deepEqual(titles(reloaded.session, null), ['Cartoons', 'Their Name']);
    assert.equal(reloaded.session.catalogVersion, 8);
    assert.equal(CatalogEditor.isDirty(reloaded.session), false);
});

test('a reload that fails leaves the caller with nothing it can pretend is saved', async () => {
    const result = await CatalogEditor.reload(() => Promise.resolve({ status: 503, data: { error: 'TV is offline' } }));

    assert.equal(result.outcome, 'failed');
    assert.equal(result.session, undefined);
    assert.match(result.reason, /offline/);
});

test('a conflict is not a reason to lose the catalog: the server keeps its version until a real save', async () => {
    const server = fakeServer(document(7));
    const edited = apply(openEditor(7), CatalogEditor.addCategory(openEditor(7), { title: 'Mine' }));

    await CatalogEditor.save(edited, server.put);                 // 7 -> 8
    const stale = await CatalogEditor.save(edited, server.put);   // still expects 7 -> 409
    assert.equal(stale.outcome, 'conflict');

    // Reconciled by reloading, editing again and saving at the version the server is on.
    const reloaded = await CatalogEditor.reload(server.get, { newId: deterministicIds() });
    const again = apply(reloaded.session, CatalogEditor.addCategory(reloaded.session, { title: 'After Reload' }));
    const saved = await CatalogEditor.save(again, server.put);

    assert.equal(saved.outcome, 'saved');
    assert.equal(saved.session.catalogVersion, 9);
    assert.deepEqual(titles(saved.session, null), ['Cartoons', 'Music', 'Mine', 'After Reload']);
});

// --- the picture a container shows --------------------------------------------------------
//
// The rule the editor offers is the rule the TV applies: AUTO is the first video inside, in catalog
// order, skipping anything hidden and everything inside it. These tests pin the *editor's* half of
// that - what it offers, what it stores and what it refuses - against the resolver tests in the JVM
// suite, which pin the same examples on the app's side.

/** A tree of containers and videos, opened as a working copy. */
function openTree(nodes, version) {
    return CatalogEditor.open({ schemaVersion: 2, catalogVersion: version || 3, nodes: nodes });
}

/**
 * A publishable tree to work on: a shelf holding a subcategory of two videos, a hidden subcategory
 * with a video inside it, a direct video, an empty subcategory and a second subcategory - plus a
 * second shelf, so a video outside the one being edited exists.
 */
function thumbnailTree() {
    return [
        node('cat', null, 'CATEGORY', 'Cat', 0),
        node('sub1', 'cat', 'SUBCATEGORY', 'Sub One', 0),
        node('v1', 'sub1', 'VIDEO', 'V1', 0, { youtubeVideoId: 'ytV1' }),
        node('v2', 'sub1', 'VIDEO', 'V2', 1, { youtubeVideoId: 'ytV2' }),
        node('hidden', 'cat', 'SUBCATEGORY', 'Hidden Sub', 1, { enabled: false }),
        node('hv', 'hidden', 'VIDEO', 'HV', 0, { youtubeVideoId: 'ytHV' }),
        node('direct', 'cat', 'VIDEO', 'Direct', 2, { youtubeVideoId: 'ytDirect' }),
        node('empty', 'cat', 'SUBCATEGORY', 'Nothing Inside', 3),
        node('sub2', 'cat', 'SUBCATEGORY', 'Sub Two', 4),
        node('v3', 'sub2', 'VIDEO', 'V3', 0, { youtubeVideoId: 'ytV3' }),
        node('cat2', null, 'CATEGORY', 'Cat Two', 1),
        node('w1', 'cat2', 'VIDEO', 'W1', 0, { youtubeVideoId: 'ytW1' }),
    ];
}

/**
 * The same tree with one video whose identifier is blank - the state a hand-edited document can be in.
 * A stored catalog cannot normally hold this, which is exactly why the resolver has to survive it.
 */
function corruptTree() {
    return thumbnailTree().concat([
        node('blank', 'sub2', 'VIDEO', 'No Video Id', 1, { youtubeVideoId: '   ' }),
    ]);
}

test('a mode the editor cannot render is reported rather than guessed at', () => {
    assert.equal(CatalogEditor.thumbnailModeOf(node('a', null, 'CATEGORY', 'A', 0)), 'AUTO');
    assert.equal(CatalogEditor.thumbnailModeOf({ thumbnailMode: null }), 'AUTO');
    assert.equal(CatalogEditor.thumbnailModeOf({ thumbnailMode: 'VIDEO' }), 'VIDEO');
    assert.equal(CatalogEditor.thumbnailModeOf({ thumbnailMode: 'CUSTOM' }), null, 'CUSTOM is reserved, not renderable');
    assert.equal(CatalogEditor.thumbnailModeOf({ thumbnailMode: 'SOMETHING' }), null);
    assert.equal(CatalogEditor.thumbnailModeOf({ thumbnailMode: '   ' }), null);

    // The editor offers exactly two answers, and CUSTOM is not one of them.
    assert.deepEqual(CatalogEditor.THUMBNAIL_MODES, ['AUTO', 'VIDEO']);
});

test('automatic uses the first video inside, in catalog order', () => {
    const session = openTree(thumbnailTree());
    assert.equal(CatalogEditor.autoVideo(session, 'cat').id, 'v1', 'the first container comes first');
    assert.equal(CatalogEditor.autoVideo(session, 'sub1').id, 'v1');
    assert.equal(CatalogEditor.autoVideo(session, 'sub2').id, 'v3');
    assert.equal(CatalogEditor.autoVideo(session, 'cat2').id, 'w1');
    assert.equal(CatalogEditor.autoVideo(session, 'hidden').id, 'hv',
        'a container being hidden does not decide what is inside it: unhiding it must bring its picture back');
});

test('a direct video before a container wins, and a container before a direct video wins', () => {
    const directFirst = openTree([
        node('cat', null, 'CATEGORY', 'Cat', 0),
        node('d', 'cat', 'VIDEO', 'Direct', 0, { youtubeVideoId: 'ytD' }),
        node('sub', 'cat', 'SUBCATEGORY', 'Sub', 1),
        node('s', 'sub', 'VIDEO', 'Nested', 0, { youtubeVideoId: 'ytS' }),
    ]);
    assert.equal(CatalogEditor.autoVideo(directFirst, 'cat').id, 'd');

    const containerFirst = openTree([
        node('cat', null, 'CATEGORY', 'Cat', 0),
        node('sub', 'cat', 'SUBCATEGORY', 'Sub', 0),
        node('s', 'sub', 'VIDEO', 'Nested', 0, { youtubeVideoId: 'ytS' }),
        node('d', 'cat', 'VIDEO', 'Direct', 1, { youtubeVideoId: 'ytD' }),
    ]);
    assert.equal(CatalogEditor.autoVideo(containerFirst, 'cat').id, 's',
        'a container is descended into before the next sibling is looked at');
});

test('a hidden video is skipped, and a hidden container takes its subtree with it', () => {
    const session = openTree([
        node('cat', null, 'CATEGORY', 'Cat', 0),
        node('off', 'cat', 'VIDEO', 'Hidden', 0, { youtubeVideoId: 'ytOff', enabled: false }),
        node('hidden', 'cat', 'SUBCATEGORY', 'Hidden Sub', 1, { enabled: false }),
        node('hv', 'hidden', 'VIDEO', 'Inside Hidden', 0, { youtubeVideoId: 'ytHV' }),
        node('on', 'cat', 'VIDEO', 'Shown', 2, { youtubeVideoId: 'ytOn' }),
    ]);

    assert.equal(CatalogEditor.autoVideo(session, 'cat').id, 'on');

    const videos = CatalogEditor.descendantVideos(session, 'cat');
    assert.deepEqual(videos.map((v) => v.id), ['off', 'hv', 'on'], 'every video is listed, so the parent can decide');
    assert.deepEqual(videos.map((v) => v.usable), [false, false, true]);
    assert.equal(videos[1].reachable, false, 'a video inside a hidden container is out of reach');
    assert.equal(videos[0].enabled, false);
});

test('a video with no usable YouTube id cannot be the picture', () => {
    const session = openTree(corruptTree());
    const videos = CatalogEditor.descendantVideos(session, 'cat');
    const blank = videos.find((v) => v.id === 'blank');
    assert.equal(blank.usable, false);
    assert.equal(blank.youtubeVideoId, null, 'a blank id is no id');
    assert.equal(CatalogEditor.autoVideo(session, 'cat').id, 'v1', 'so automatic looks further');

    assert.equal(CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'blank' }).ok, false);
});

test('a container with nothing usable inside it has no automatic picture', () => {
    const session = openTree(thumbnailTree());
    assert.equal(CatalogEditor.autoVideo(session, 'empty'), null);
    assert.deepEqual(CatalogEditor.descendantVideos(session, 'empty'), []);
    assert.equal(CatalogEditor.autoVideo(session, 'nope'), null, 'an unknown container has none either');
});

test('the automatic picture does not depend on the order the nodes arrive in', () => {
    const shuffled = thumbnailTree().slice().reverse();
    assert.equal(
        CatalogEditor.autoVideo(openTree(shuffled), 'cat').id,
        CatalogEditor.autoVideo(openTree(thumbnailTree()), 'cat').id,
        'only position and id decide, never the order of the array');
});

test('the videos offered for a choice are the ones inside, with a readable path', () => {
    const session = openTree(thumbnailTree());
    const videos = CatalogEditor.descendantVideos(session, 'cat');

    assert.deepEqual(videos.map((v) => v.id), ['v1', 'v2', 'hv', 'direct', 'v3']);
    assert.deepEqual(videos.map((v) => v.path), ['Sub One / V1', 'Sub One / V2', 'Hidden Sub / HV', 'Direct', 'Sub Two / V3']);
    assert.deepEqual(videos.map((v) => v.usable), [true, true, false, true, true],
        'a video inside a hidden container is listed but never usable');
    assert.deepEqual(CatalogEditor.descendantVideos(session, 'v1'), [], 'a video has nothing inside it');
    assert.deepEqual(CatalogEditor.descendantVideos(session, 'cat2').map((v) => v.id), ['w1'],
        'a choice is only ever offered from inside the container itself');
});

test('choosing a video stores the node id inside the container, not a YouTube id', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, {
        id: 'cat', mode: 'VIDEO', videoNodeId: 'v3',
    }));

    const cat = CatalogEditor.nodeById(chosen, 'cat');
    assert.equal(cat.thumbnailMode, 'VIDEO');
    assert.equal(cat.thumbnailVideoId, 'v3', 'a node id names exactly one node');
    assert.notEqual(cat.thumbnailVideoId, 'ytV3');
    assert.equal(CatalogEditor.isDirty(chosen), true);
    assertCanonical(chosen);
});

test('automatic clears the choice, because a node carries one answer', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v3' }));
    const back = apply(chosen, CatalogEditor.setThumbnail(chosen, { id: 'cat', mode: 'AUTO' }));

    const cat = CatalogEditor.nodeById(back, 'cat');
    assert.equal(cat.thumbnailMode, 'AUTO');
    assert.equal(cat.thumbnailVideoId, null);
});

test('a picture choice that cannot be honoured is refused, and changes nothing', () => {
    const session = openTree(corruptTree());

    const refused = [
        [{ id: 'nope', mode: 'VIDEO', videoNodeId: 'v1' }, 'that node no longer exists'],
        [{ id: 'v1', mode: 'VIDEO', videoNodeId: 'v3' }, 'a video is its own picture; choose one for a shelf or subcategory instead'],
        [{ id: 'cat', mode: 'CUSTOM' }, 'choose automatic, or one video inside this category'],
        [{ id: 'cat', mode: 'VIDEO' }, 'choose a video to take the picture from'],
        [{ id: 'cat', mode: 'VIDEO', videoNodeId: 'ghost' }, 'that video no longer exists'],
        [{ id: 'cat', mode: 'VIDEO', videoNodeId: 'sub2' }, 'only a video can stand for a category'],
        [{ id: 'sub2', mode: 'VIDEO', videoNodeId: 'v1' }, 'that video is not inside this subcategory'],
        [{ id: 'cat', mode: 'VIDEO', videoNodeId: 'w1' }, 'that video is not inside this category'],
        [{ id: 'direct', mode: 'VIDEO', videoNodeId: 'cat' }, 'a video is its own picture; choose one for a shelf or subcategory instead'],
        [{ id: 'cat', mode: 'VIDEO', videoNodeId: 'blank' }, 'that video has no YouTube id to take a picture from'],
        [{ id: 'cat', mode: 'VIDEO', videoNodeId: 'hv' }, 'that video is hidden from your child, and a hidden video cannot stand for this category'],
    ];

    refused.forEach(([input, reason]) => {
        const result = CatalogEditor.setThumbnail(session, input);
        assert.equal(result.ok, false, JSON.stringify(input) + ' must be refused');
        assert.equal(result.reason, reason);
        assert.equal(result.session, undefined, 'a refusal carries no session to apply');
    });

    assert.deepEqual(session.nodes, openTree(corruptTree()).nodes, 'and the working copy is untouched');
});

test('the picture rules the editor checks are the rules the server checks', () => {
    const session = openTree(thumbnailTree());
    const broken = (changes) => {
        const nodes = session.nodes.map((n) => (n.id === 'cat' ? Object.assign({}, n, changes) : n));
        return CatalogEditor.problems({ nodes: nodes, savedNodes: nodes });
    };

    assert.deepEqual(broken({}), [], 'the tree itself is fine');

    assert.match(broken({ thumbnailMode: 'VIDEO', thumbnailVideoId: null }).join('; '),
        /chooses a video as its picture but names none/);
    assert.match(broken({ thumbnailMode: 'VIDEO', thumbnailVideoId: 'ghost' }).join('; '),
        /names thumbnail video ghost, which is not in this catalog/);
    assert.match(broken({ thumbnailMode: 'VIDEO', thumbnailVideoId: 'sub2' }).join('; '),
        /names sub2 as its picture, and that is a SUBCATEGORY/);
    assert.match(broken({ thumbnailMode: 'VIDEO', thumbnailVideoId: 'w1' }).join('; '),
        /thumbnail video w1 is not inside cat/);
    assert.match(broken({ thumbnailVideoId: 'v1' }).join('; '),
        /names a thumbnail video while its mode is AUTO/);
    assert.match(broken({ thumbnailMode: 'CUSTOM' }).join('; '),
        /unsupported thumbnail mode "CUSTOM"/);
    assert.match(broken({ thumbnailUrl: 'https://example.com/x.jpg' }).join('; '),
        /names a picture URL; a picture comes from the app, not from a link/);
    assert.deepEqual(broken({ thumbnailMode: 'VIDEO', thumbnailVideoId: 'v3' }), [],
        'a video deeper inside the container is a valid choice');

    // The same rules asked of a video, and of a video inside a hidden container.
    const corrupt = openTree(corruptTree());
    const withBlank = corrupt.nodes.map((n) => (n.id === 'cat'
        ? Object.assign({}, n, { thumbnailMode: 'VIDEO', thumbnailVideoId: 'blank' })
        : n));
    assert.match(CatalogEditor.problems({ nodes: withBlank, savedNodes: withBlank }).join('; '),
        /thumbnail video blank has no YouTube id to take a picture from/);

    const withVideo = session.nodes.map((n) => (n.id === 'v1' ? Object.assign({}, n, { thumbnailVideoId: 'v2' }) : n));
    assert.match(CatalogEditor.problems({ nodes: withVideo, savedNodes: withVideo }).join('; '),
        /video v1 uses its own picture and must not choose one/);
});

test('a choice whose video is hidden later is kept as the parent made it, and the TV falls back to automatic', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v1' }));

    // The choice stays exactly as the parent made it: hiding a video is not a reason to rewrite a
    // configuration, and unhiding it brings the picture straight back.
    const hidden = apply(chosen, CatalogEditor.setEnabled(chosen, { id: 'v1', enabled: false }));
    assert.equal(CatalogEditor.nodeById(hidden, 'cat').thumbnailVideoId, 'v1');
    assertCanonical(hidden);

    const shown = apply(hidden, CatalogEditor.setEnabled(hidden, { id: 'v1', enabled: true }));
    assert.equal(CatalogEditor.nodeById(shown, 'cat').thumbnailVideoId, 'v1');
    assertCanonical(shown);
});

test('deleting a video takes the picture that named it with it', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v3' }));

    const removed = apply(chosen, CatalogEditor.remove(chosen, { id: 'v3' }));
    const cat = CatalogEditor.nodeById(removed, 'cat');
    assert.equal(cat.thumbnailMode, 'AUTO', 'a selection that names nothing cannot be kept');
    assert.equal(cat.thumbnailVideoId, null);
    assertCanonical(removed);

    const other = apply(chosen, CatalogEditor.remove(chosen, { id: 'v1' }));
    assert.equal(CatalogEditor.nodeById(other, 'cat').thumbnailVideoId, 'v3', 'another video is not this video');
});

test('deleting a container clears the pictures chosen from inside it', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v3' }));
    const removed = apply(chosen, CatalogEditor.remove(chosen, { id: 'sub2' }));

    assert.equal(CatalogEditor.nodeById(removed, 'cat').thumbnailVideoId, null);
    assertCanonical(removed);
});

test('moving the chosen video out of the container is reported rather than quietly rewritten', () => {
    const session = openTree(thumbnailTree());
    const chosen = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v3' }));
    const moved = apply(chosen, CatalogEditor.moveTo(chosen, { id: 'v3', parentId: 'cat2' }));

    // A move is reversible, so the choice is kept rather than thrown away - but the document is not
    // publishable in that state, and the editor says so instead of letting Save discover it.
    assert.equal(CatalogEditor.nodeById(moved, 'cat').thumbnailVideoId, 'v3');
    assert.match(CatalogEditor.problems(moved).join('; '), /thumbnail video v3 is not inside cat/);

    const back = apply(moved, CatalogEditor.moveTo(moved, { id: 'v3', parentId: 'sub2' }));
    assert.deepEqual(CatalogEditor.problems(back), [], 'moving it back makes the document publishable again');
    assert.equal(CatalogEditor.nodeById(back, 'cat').thumbnailVideoId, 'v3');
});

test('the picture is published through the same working copy and the same Save', async () => {
    const server = fakeServer(document(7));
    const session = openEditor(7);
    const chosen = apply(session, CatalogEditor.setThumbnail(session, {
        id: 'cat-cartoon', mode: 'VIDEO', videoNodeId: 'i-bluey#d',
    }));

    let sent = null;
    const result = await CatalogEditor.save(chosen, (body) => {
        sent = body;
        return server.put(body);
    });

    assert.equal(result.outcome, 'saved');
    assert.equal(sent.expectedCatalogVersion, 7, 'the same optimistic version as any other edit');
    assert.equal(sent.nodes.filter((n) => n.id === 'cat-cartoon')[0].thumbnailMode, 'VIDEO');
    assert.equal(sent.nodes.filter((n) => n.id === 'cat-cartoon')[0].thumbnailVideoId, 'i-bluey#d');
    assert.equal(result.session.catalogVersion, 8, 'and only the server says it is saved');
    assert.equal(CatalogEditor.isDirty(result.session), false);
});

test('a picture edit made against a stale copy is a conflict like any other edit', async () => {
    const server = fakeServer(document(7));
    const session = openEditor(7);
    await CatalogEditor.save(apply(session, CatalogEditor.addCategory(session, { title: 'First' })), server.put);

    const stale = apply(session, CatalogEditor.setThumbnail(session, {
        id: 'cat-cartoon', mode: 'VIDEO', videoNodeId: 'i-bluey#d',
    }));
    const result = await CatalogEditor.save(stale, server.put);

    assert.equal(result.outcome, 'conflict');
    assert.equal(result.serverVersion, 8);
    assert.equal(CatalogEditor.nodeById(result.session, 'cat-cartoon').thumbnailVideoId, 'i-bluey#d',
        'the unsaved choice survives the conflict');
});

test('no node ever carries a picture URL, however the picture was chosen', () => {
    const session = openTree(thumbnailTree());
    const auto = apply(session, CatalogEditor.setThumbnail(session, { id: 'cat', mode: 'AUTO' }));
    const video = apply(auto, CatalogEditor.setThumbnail(auto, { id: 'cat', mode: 'VIDEO', videoNodeId: 'v3' }));

    [session, auto, video].forEach((state) => {
        CatalogEditor.toDocument(state).nodes.forEach((n) => {
            assert.equal(n.thumbnailUrl, null, 'the TV has only the artwork its sources provided');
        });
    });

    const source = fs.readFileSync(path.join(assets, 'catalog-editor.js'), 'utf8');
    assert.ok(!/ytimg|img\.youtube|https?:\/\//.test(source.replace(/\/\*[\s\S]*?\*\//g, '')),
        'the editor must not synthesise a thumbnail URL');
});

test('the page offers exactly two pictures, and the reserved mode is nowhere in the editor', () => {
    const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
    const app = code('app.js');

    const modes = html.match(/<select id="panel-thumbnail-mode"[\s\S]*?<\/select>/);
    assert.ok(modes, 'the panel must offer a picture for a container');
    assert.deepEqual([...modes[0].matchAll(/value="([^"]+)"/g)].map((m) => m[1]), ['AUTO', 'VIDEO'],
        'CUSTOM is reserved and is deliberately not offered');

    assert.ok(html.includes('id="panel-thumbnail-video"'), 'the videos inside must be choosable');
    assert.ok(!/CUSTOM/.test(app), 'app.js must never write the reserved mode either');

    // The two controls are wired to the model, not to a second way of saving anything.
    assert.match(app, /window\.changeThumbnailMode\s*=/);
    assert.match(app, /window\.changeThumbnailVideo\s*=/);
});

test('a picture travels through the catalog document, so the editor opens no endpoint of its own', () => {
    const app = code('app.js');
    const model = code('catalog-editor.js');

    [app, model].forEach((source) => {
        assert.ok(!/['"`][^'"`]*\/thumbnail/i.test(source), 'there must be no separate thumbnail API');
        [...source.matchAll(/thumbnailUrl\s*:\s*([^,\n}]+)/g)]
            .forEach((write) => assert.equal(write[1].trim(), 'null', 'nothing writes a picture URL'));
    });

    const calls = [...app.matchAll(/apiCall\('([A-Z]+)',\s*'([^']+)'/g)];
    assert.ok(calls.length > 0, 'the dashboard still talks to the server');
    calls.forEach(([, method, target]) => {
        assert.ok(!/thumbnail/i.test(target), method + ' ' + target + ' would be a thumbnail API of its own');
    });
});

// --- the editor is a model, not a view ----------------------------------------------------

test('the editor model touches no DOM, no network and no browser storage', () => {
    const source = fs.readFileSync(path.join(assets, 'catalog-editor.js'), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');

    ['document.', 'window.', 'fetch(', 'XMLHttpRequest', 'localStorage', 'sessionStorage', 'indexedDB', 'setTimeout']
        .forEach((forbidden) => {
            assert.ok(!source.includes(forbidden), 'catalog-editor.js must not reference ' + forbidden);
        });
});

// --- the page and the script agree --------------------------------------------------------

function code(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');
}

test('every control in the page is wired to a function the script exposes', () => {
    const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
    const app = code('app.js');

    const handlers = [...html.matchAll(/on(?:click|change)="(\w+)\(/g)].map((m) => m[1]);
    assert.ok(handlers.length > 0, 'the editor must have controls');

    [...new Set(handlers)].forEach((name) => {
        // app.js is an IIFE, so an inline handler only works if the script put it on `window`.
        assert.match(app, new RegExp('window\\.' + name + '\\s*='),
            'index.html calls ' + name + '() but app.js never exposes it');
    });

    // The editor's own controls are all here, so a missing one is a missing feature, not a typo.
    ['addNode', 'renameSelected', 'toggleSelectedEnabled', 'moveSelectedUp', 'moveSelectedDown',
        'moveSelectedTo', 'deleteSelected', 'saveCatalog', 'reloadCatalogFromServer', 'keepEditing']
        .forEach((required) => assert.ok(handlers.includes(required), 'the page must offer ' + required));
});

test('every element the dashboard looks up exists in the page', () => {
    const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
    const app = code('app.js');

    const ids = [...new Set([...app.matchAll(/getElementById\('([^']+)'\)/g)].map((m) => m[1]))];
    assert.ok(ids.length > 10);

    ids.forEach((id) => {
        assert.ok(html.includes('id="' + id + '"'), 'app.js looks up #' + id + ', which the page does not have');
    });
});
