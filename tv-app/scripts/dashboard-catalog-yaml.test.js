/*
 * The YAML catalog file: the format, the validation, and the round trip.
 *
 * `catalog-yaml.js` is a model - no DOM, no network, no storage - so every rule it applies is
 * testable here. The tests are grouped the way the feature is: what a file may contain, what an
 * export writes, what an import accepts and refuses, what the preview says, and the one property
 * that makes the whole thing trustworthy: export → import → export is the same file.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const CatalogYaml = require('../app/src/main/assets/catalog-yaml.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

function node(id, parentId, nodeType, title, position, extra) {
    return Object.assign({
        id: id,
        parentId: parentId,
        nodeType: nodeType,
        title: title,
        position: position,
        enabled: true,
        youtubeVideoId: null,
        youtubePlaylistId: null,
        thumbnailMode: 'AUTO',
        thumbnailVideoId: null,
        thumbnailUrl: null,
        createdAt: 0,
        updatedAt: 0
    }, extra || {});
}

/** A small library with everything the format has to carry: a folder of curated videos, an imported
 *  playlist, a hidden shelf, an explicit picture, and a title with the characters humans type. */
function library() {
    return [
        node('cat-cartoon', null, 'CATEGORY', 'Cartoon', 0),
        node('i-cocomelon', 'cat-cartoon', 'SUBCATEGORY', 'CoComelon', 0, {
            youtubePlaylistId: 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT',
            thumbnailMode: 'VIDEO',
            thumbnailVideoId: 'i-cocomelon#e_04ZrNroTo'
        }),
        node('i-cocomelon#e_04ZrNroTo', 'i-cocomelon', 'VIDEO', 'Wheels on the Bus', 0, {
            youtubeVideoId: 'e_04ZrNroTo',
            youtubePlaylistId: 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT'
        }),
        node('i-cocomelon#MR5XSOdjKMA', 'i-cocomelon', 'VIDEO', 'Bath Song', 1, {
            youtubeVideoId: 'MR5XSOdjKMA',
            youtubePlaylistId: 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT'
        }),
        node('i-favourites', 'cat-cartoon', 'SUBCATEGORY', 'Favourites', 1),
        node('vid-it-s-a-demo', 'i-favourites', 'VIDEO', "It's a demo: part #2", 0, {
            youtubeVideoId: 'demo1234567'
        }),
        node('cat-bedtime', null, 'CATEGORY', 'Bedtime', 1, { enabled: false })
    ];
}

function read(text) {
    return CatalogYaml.readDocument(text);
}

// --- the format ------------------------------------------------------------------------------

test('an export is a versioned document with a catalog of categories', () => {
    const yaml = CatalogYaml.serialize(library());

    assert.match(yaml, /^version: 1$/m);
    assert.match(yaml, /^catalog:$/m);
    assert.match(yaml, /^  name: SafeTube Library$/m);
    assert.match(yaml, /^  categories:$/m);
    assert.match(yaml, /^    - id: cat-cartoon$/m);
    assert.match(yaml, /^      name: Cartoon$/m);
});

test('a title is written plainly when it can be, and quoted when it cannot', () => {
    const yaml = CatalogYaml.serialize(library());

    assert.match(yaml, /^      name: Cartoon$/m, 'a plain title stays plain');
    assert.match(yaml, /title: 'It''s a demo: part #2'/, 'a title with a colon, a hash and an apostrophe is quoted');
    assert.match(yaml, /^\s+title: Wheels on the Bus$/m);
});

test('the file carries curation: order, hidden, playlist provenance and the chosen picture', () => {
    const yaml = CatalogYaml.serialize(library());

    assert.match(yaml, /^\s+order: 1$/m, 'the second video is the second video');
    assert.match(yaml, /^\s+hidden: true$/m, 'a hidden shelf says so');
    assert.match(yaml, /playlist: PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT/);
    assert.match(yaml, /picture: video:i-cocomelon#e_04ZrNroTo/);
    assert.match(yaml, /^\s+playlist: PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT$/m,
        'a video keeps the source it came from, which is how the TV approves it');
});

test('the file carries no runtime state, no identifiers and no secrets', () => {
    const yaml = CatalogYaml.serialize(library());

    ['createdAt', 'updatedAt', 'thumbnailUrl', 'position', 'parentId', 'nodeType', 'enabled',
        'token', 'session', 'resume', 'positionMs', 'password', 'pin']
        .forEach((forbidden) => assert.ok(!yaml.includes(forbidden), 'the export must not mention ' + forbidden));

    // Videos are named by their YouTube id, not by a database row.
    assert.match(yaml, /youtube: e_04ZrNroTo/);
});

test('exporting the same library twice produces the same bytes', () => {
    assert.equal(CatalogYaml.serialize(library()), CatalogYaml.serialize(library()));
    const shuffled = library().slice().reverse();
    assert.equal(CatalogYaml.serialize(shuffled), CatalogYaml.serialize(library()),
        'and so does a differently ordered list of the same nodes: the tree decides');
});

test('the output uses only the subset the parser reads', () => {
    const yaml = CatalogYaml.serialize(library());
    const parsed = CatalogYaml.parse(yaml);

    assert.equal(parsed.ok, true, JSON.stringify(parsed));
    yaml.split('\n').forEach((text, index) => {
        assert.ok(!/[\t]/.test(text), 'line ' + (index + 1) + ' has a tab');
        assert.ok(!/[{[]/.test(text), 'line ' + (index + 1) + ' uses a flow collection');
        assert.ok(!/\s+$/.test(text), 'line ' + (index + 1) + ' has trailing whitespace');
    });
});

// --- reading a file --------------------------------------------------------------------------

test('a file that is not YAML is refused with the line it failed on', () => {
    const parsed = CatalogYaml.parse('version: 1\ncatalog:\n  name: x\n\tcategories: []\n');

    assert.equal(parsed.ok, false);
    assert.equal(parsed.line, 4);
    assert.match(parsed.message, /tab/i);
});

test('the subset refuses what it cannot be sure of, rather than guessing', () => {
    const cases = [
        ['catalog: {name: x}', /flow collection/],
        ['catalog:\n  name: &anchor x', /anchor/],
        ['catalog:\n  name: !!str x', /tag/],
        ['catalog:\n  name: |\n    text', /multi-line/],
        ['catalog:\n  name: "unclosed', /closing quote/],
        ['---\nversion: 1\n---\nversion: 1', /one YAML document/],
    ];
    cases.forEach(([text, pattern]) => {
        const parsed = CatalogYaml.parse(text);
        assert.equal(parsed.ok, false, 'should refuse: ' + JSON.stringify(text));
        assert.match(parsed.message, pattern);
    });
});

test('comments and blank lines are ignored, as a human editor expects', () => {
    const parsed = CatalogYaml.parse([
        '# a note the parent typed',
        'version: 1        # the format',
        '',
        'catalog:',
        '  name: My library',
        '  categories: []',
        ''
    ].join('\n'));

    assert.equal(parsed.ok, true);
    assert.equal(parsed.value.catalog.name, 'My library');
});

test('a value may be single quoted, double quoted or plain', () => {
    const parsed = CatalogYaml.parse([
        'version: 1',
        'catalog:',
        "  name: 'Single: quoted'",
        '  categories:',
        '    - id: c1',
        '      name: "Double: quoted"',
        '    - id: c2',
        '      name: Plain',
        ''
    ].join('\n'));

    assert.equal(parsed.value.catalog.name, 'Single: quoted');
    assert.equal(parsed.value.catalog.categories[0].name, 'Double: quoted');
    assert.equal(parsed.value.catalog.categories[1].name, 'Plain');
});

// --- validation ------------------------------------------------------------------------------

test('a missing version is refused, and so is an unknown one', () => {
    const missing = read('catalog:\n  categories: []\n');
    assert.equal(missing.ok, false);
    assert.match(missing.problems[0], /no "version"/);

    const future = read('version: 2\ncatalog:\n  categories: []\n');
    assert.equal(future.ok, false);
    assert.match(future.problems[0], /version: 2/);
    assert.match(future.problems[0], /nothing was imported/);
});

test('a missing catalog section is refused', () => {
    const result = read('version: 1\nname: SafeTube\n');
    assert.equal(result.ok, false);
    assert.match(result.problems[0], /no "catalog" section/);
});

test('a malformed category names the category it is about', () => {
    const noId = read('version: 1\ncatalog:\n  categories:\n    - name: Cartoons\n');
    assert.equal(noId.ok, false);
    assert.match(noId.problems[0], /Category "Cartoons": a category needs an "id"/);

    const noName = read('version: 1\ncatalog:\n  categories:\n    - id: cartoons\n');
    assert.equal(noName.ok, false);
    assert.match(noName.problems[0], /Category "cartoons": a category needs a "name"/);
});

test('a malformed collection, video and order are each named and located', () => {
    const yaml = [
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      order: 0',
        '      collections:',
        '        - name: CoComelon',
        '        - id: songs',
        '          name: Songs',
        '          order: -2',
        '          videos:',
        '            - title: No video id',
        '            - youtube: abc12345678',
        ''
    ].join('\n');

    const result = read(yaml);
    assert.equal(result.ok, false);
    const all = result.problems.join('\n');
    assert.match(all, /Category "Cartoons" \/ collection 1: a collection needs an "id"/);
    assert.match(all, /collection 2: "order" must be a whole number starting at 0/);
    assert.match(all, /video 1: a video needs a "youtube" id/);
    assert.match(all, /video 2: a video needs a "title"/);
});

test('the same id twice is refused, whichever two nodes share it', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      videos:',
        '        - youtube: e_04ZrNroTo',
        '          title: One',
        '    - id: cartoons',
        '      name: Cartoons again',
        ''
    ].join('\n'));

    assert.equal(result.ok, false);
    assert.match(result.problems.join('\n'), /the id "cartoons" is used twice/);
});

test('a video id that is missing, or duplicated under one parent, is refused', () => {
    const sameVideoTwice = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      videos:',
        '        - youtube: e_04ZrNroTo',
        '          title: One',
        '        - youtube: e_04ZrNroTo',
        '          title: Twice',
        ''
    ].join('\n'));

    assert.equal(sameVideoTwice.ok, false);
    assert.match(sameVideoTwice.problems.join('\n'), /is used twice/,
        'two nodes derived from one video under one parent cannot both exist');
});

test('a playlist id that does not look like one is refused', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      collections:',
        '        - id: cocomelon',
        '          name: CoComelon',
        '          playlist: https://www.youtube.com/playlist?list=PLx',
        ''
    ].join('\n'));

    assert.equal(result.ok, false);
    assert.match(result.problems.join('\n'), /does not look like a YouTube playlist id/);
});

test('a picture must name a video that is inside that container', () => {
    const outside = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      picture: video:elsewhere',
        '      videos:',
        '        - youtube: e_04ZrNroTo',
        '          title: One',
        '    - id: music',
        '      name: Music',
        '      videos:',
        '        - id: elsewhere',
        '          youtube: MR5XSOdjKMA',
        '          title: Two',
        ''
    ].join('\n'));

    assert.equal(outside.ok, false);
    assert.match(outside.problems.join('\n'), /not inside it as its picture/);

    const unknown = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      picture: video:nowhere',
        ''
    ].join('\n'));
    assert.equal(unknown.ok, false);
    assert.match(unknown.problems.join('\n'), /no video in this file has that id/);
});

test('an empty library is a legitimate file, not an error', () => {
    const result = read('version: 1\ncatalog:\n  name: Fresh start\n  categories: []\n');

    assert.equal(result.ok, true);
    assert.deepEqual(result.nodes, []);
    assert.equal(result.name, 'Fresh start');
});

test('an empty category and an empty collection are kept as they are', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      collections:',
        '        - id: empty',
        '          name: Nothing in here yet',
        ''
    ].join('\n'));

    assert.equal(result.ok, true);
    assert.equal(result.nodes.length, 2);
    assert.equal(result.nodes.filter((n) => n.nodeType === 'SUBCATEGORY')[0].title, 'Nothing in here yet');
});

// --- ordering --------------------------------------------------------------------------------

test('order decides the sequence, and missing order means the order of the lines', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: third',
        '      name: Third',
        '      order: 2',
        '    - id: first',
        '      name: First',
        '      order: 0',
        '    - id: second',
        '      name: Second',
        '      order: 1',
        ''
    ].join('\n'));

    assert.equal(result.ok, true);
    const order = result.nodes.slice().sort((a, b) => a.position - b.position).map((n) => n.title);
    assert.deepEqual(order, ['First', 'Second', 'Third']);
});

test('a hand-edited file with a gap in the orders still imports, in the order it meant', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: a',
        '      name: A',
        '      order: 0',
        '    - id: b',
        '      name: B',
        '      order: 5',
        ''
    ].join('\n'));

    assert.equal(result.ok, true, 'the server refuses gaps, so the importer closes them the way the editor does');
    const positions = result.nodes.slice().sort((x, y) => x.position - y.position).map((n) => n.id);
    assert.deepEqual(positions, ['a', 'b']);
    assert.deepEqual(result.nodes.map((n) => n.position).sort(), [0, 1]);
});

test('a video without an id gets the id the catalog itself derives from its playlist parent', () => {
    const result = read([
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cartoons',
        '      name: Cartoons',
        '      collections:',
        '        - id: cocomelon',
        '          name: CoComelon',
        '          videos:',
        '            - youtube: e_04ZrNroTo',
        '              title: Wheels on the Bus',
        ''
    ].join('\n'));

    assert.equal(result.ok, true);
    const video = result.nodes.filter((n) => n.nodeType === 'VIDEO')[0];
    assert.equal(video.id, 'cocomelon#e_04ZrNroTo', 'the same convention the TV uses for imported episodes');
});

// --- preview ---------------------------------------------------------------------------------

test('the preview counts what the file holds, and what a child could actually play', () => {
    const yaml = CatalogYaml.serialize(library());
    const result = read(yaml);
    const view = CatalogYaml.preview(result.nodes, [], { 'PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT': true });

    assert.equal(view.counts.categories, 2);
    assert.equal(view.counts.collections, 2);
    assert.equal(view.counts.videos, 3);
    assert.equal(view.counts.hidden, 1);
    assert.equal(view.counts.playlists, 1);
    assert.equal(view.counts.unplayable, 1, "the curated demo video's source is not allowed");
});

test('the preview reports a playlist whose source is not allowed, without changing anything', () => {
    const result = read(CatalogYaml.serialize(library()));
    const view = CatalogYaml.preview(result.nodes, [], {});

    assert.deepEqual(view.unplayableSources, ['PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT', 'demo1234567']);
    assert.equal(view.counts.unplayable, 3, 'the two playlist videos and the curated one');
});

test('the preview says what changes: added, removed, renamed and reordered', () => {
    const before = library();
    const after = before.map((n) => Object.assign({}, n));
    after[0].title = 'Cartoons';
    after[4] = Object.assign({}, after[4], { position: 0 });
    after[1] = Object.assign({}, after[1], { position: 1 });
    const removed = after.filter((n) => n.id !== 'vid-it-s-a-demo' && n.parentId !== 'vid-it-s-a-demo');
    const added = removed.concat([node('cat-music', null, 'CATEGORY', 'Music', 2)]);

    const view = CatalogYaml.preview(added, before, {});

    assert.equal(view.changes.renamed.length, 1);
    assert.equal(view.changes.renamed[0].to, 'Cartoons');
    assert.equal(view.changes.removed.length, 1);
    assert.equal(view.changes.added.length, 1);
    assert.equal(view.changes.added[0].title, 'Music');
    assert.equal(view.changes.reordered, 1, 'two containers swapping places is one reorder');
});

test('the preview shows the outline in catalog order', () => {
    const result = read(CatalogYaml.serialize(library()));
    const view = CatalogYaml.preview(result.nodes, [], {});

    assert.deepEqual(view.tree.map((c) => c.name), ['Cartoon', 'Bedtime']);
    assert.deepEqual(view.tree[0].collections.map((c) => c.name), ['CoComelon', 'Favourites']);
    assert.equal(view.tree[0].collections[0].videos, 2);
    assert.equal(view.tree[1].hidden, true);
});

// --- the round trip --------------------------------------------------------------------------

test('export → import → export is the same file', () => {
    const original = library();
    const first = CatalogYaml.serialize(original);

    const imported = read(first);
    assert.equal(imported.ok, true, JSON.stringify(imported.problems));

    const second = CatalogYaml.serialize(imported.nodes);
    assert.equal(second, first, 'the same library, written the same way');

    const again = read(second);
    assert.equal(again.ok, true);
    assert.equal(CatalogYaml.serialize(again.nodes), first);
});

test('the reconstructed catalog is the same curation, node for node', () => {
    const original = library();
    const imported = read(CatalogYaml.serialize(original));

    const key = (n) => [n.id, n.parentId, n.nodeType, n.title, n.position, n.enabled,
        n.youtubeVideoId, n.youtubePlaylistId, n.thumbnailMode, n.thumbnailVideoId].join('|');
    assert.deepEqual(imported.nodes.map(key).sort(), original.map(key).sort());
});

test('a reordered file imports in the new order, and says so', () => {
    const yaml = [
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cat-bedtime',
        '      name: Bedtime',
        '      order: 0',
        '    - id: cat-cartoon',
        '      name: Cartoon',
        '      order: 1',
        '      collections:',
        '        - id: i-favourites',
        '          name: Favourites',
        '          order: 0',
        '        - id: i-cocomelon',
        '          name: CoComelon',
        '          order: 1',
        '          videos:',
        '            - id: i-cocomelon#MR5XSOdjKMA',
        '              youtube: MR5XSOdjKMA',
        '              title: Bath Song',
        '              order: 0',
        '            - id: i-cocomelon#e_04ZrNroTo',
        '              youtube: e_04ZrNroTo',
        '              title: Wheels on the Bus',
        '              order: 1',
        ''
    ].join('\n');

    const result = read(yaml);
    assert.equal(result.ok, true, JSON.stringify(result.problems));
    const view = CatalogYaml.preview(result.nodes, library(), {});

    assert.equal(view.changes.reordered, 3, 'the two shelves, the two collections and the two videos');
    const categoryOrder = result.nodes.filter((n) => n.nodeType === 'CATEGORY')
        .sort((a, b) => a.position - b.position).map((n) => n.title);
    assert.deepEqual(categoryOrder, ['Bedtime', 'Cartoon']);
    const videoOrder = result.nodes.filter((n) => n.nodeType === 'VIDEO' && n.parentId === 'i-cocomelon')
        .sort((a, b) => a.position - b.position).map((n) => n.title);
    assert.deepEqual(videoOrder, ['Bath Song', 'Wheels on the Bus']);
});

test('a large catalog stays practical and deterministic', () => {
    const big = [];
    for (let c = 0; c < 20; c++) {
        big.push(node('cat-' + c, null, 'CATEGORY', 'Shelf ' + c, c));
        for (let s = 0; s < 5; s++) {
            const collectionId = 'col-' + c + '-' + s;
            big.push(node(collectionId, 'cat-' + c, 'SUBCATEGORY', 'Collection ' + c + '-' + s, s, {
                youtubePlaylistId: 'PL' + String(c) + String(s) + 'abcdefghij'
            }));
            for (let v = 0; v < 20; v++) {
                big.push(node(collectionId + '#' + c + s + v, collectionId, 'VIDEO', 'Video ' + c + '-' + s + '-' + v, v, {
                    youtubeVideoId: 'v' + c + s + v + 'abcdefgh'
                }));
            }
        }
    }

    const started = Date.now();
    const yaml = CatalogYaml.serialize(big);
    const back = read(yaml);
    const elapsed = Date.now() - started;

    assert.equal(back.ok, true);
    assert.equal(back.nodes.length, big.length, '2000+ nodes come back whole');
    assert.equal(CatalogYaml.serialize(back.nodes), yaml);
    assert.ok(elapsed < 5000, 'and quickly: ' + elapsed + 'ms');
});

test('a mistyped key is refused by name rather than dropped', () => {
    // `folder:` where `collections:` was meant: silently ignoring it would import a library quietly
    // missing what the parent wrote, which is worse than refusing the file.
    const bad = [
        'version: 1',
        'catalog:',
        '  name: Home',
        '  categories:',
        '    - id: cat-1',
        '      name: Cartoon',
        '      folder:',
        '        - id: col-1',
        '          name: Songs'
    ].join('\n');

    const result = read(bad);

    assert.equal(result.ok, false);
    assert.ok(result.problems.some((p) => p.includes('"folder"')), result.problems.join(' | '));
    assert.ok(result.problems.some((p) => p.includes('Category "Cartoon"')), result.problems.join(' | '));
});

test('an unknown key at the top of the file or in the catalog is refused', () => {
    const rootTypo = read(['version: 1', 'library:', '  categories: []'].join('\n'));
    assert.equal(rootTypo.ok, false);
    assert.ok(rootTypo.problems.some((p) => p.includes('"library"')), rootTypo.problems.join(' | '));

    const catalogTypo = read(['version: 1', 'catalog:', '  name: Home', '  shelves: []'].join('\n'));
    assert.equal(catalogTypo.ok, false);
    assert.ok(catalogTypo.problems.some((p) => p.includes('"shelves"')), catalogTypo.problems.join(' | '));
});

test('a playlist on a category is refused, and the message says where it belongs', () => {
    const bad = [
        'version: 1',
        'catalog:',
        '  categories:',
        '    - id: cat-1',
        '      name: Cartoon',
        '      playlist: PLT1rvk7Trkw5qNnjS-y7-0FZQOsdQOvHT'
    ].join('\n');

    const result = read(bad);

    assert.equal(result.ok, false);
    assert.equal(result.problems.length, 1, 'said once, not twice: ' + result.problems.join(' | '));
    assert.ok(result.problems[0].includes('collection'), result.problems[0]);
});

// --- the file is a model ---------------------------------------------------------------------

test('the format model touches no DOM, no network and no browser storage', () => {
    const source = fs.readFileSync(path.join(assets, 'catalog-yaml.js'), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');

    ['document.', 'fetch(', 'XMLHttpRequest', 'localStorage', 'sessionStorage', 'indexedDB', 'setTimeout',
        'window.CatalogYaml =']
        .forEach((forbidden) => assert.ok(!source.includes(forbidden),
            'catalog-yaml.js must not reference ' + forbidden));
});

test('the format knows nothing about authorization', () => {
    const source = fs.readFileSync(path.join(assets, 'catalog-yaml.js'), 'utf8');

    // The file describes the catalog; it can never describe permission. The only place the word
    // appears is the preview, which *reads* the allowed list to say what cannot play yet.
    assert.ok(!/approved\s*=|authorize|grant|PlaybackAuthorization/i.test(source),
        'the format must not be able to grant anything');
});
