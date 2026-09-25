/*
 * Tests for the dashboard's read-only catalog tree.
 *
 * Run with:  node --test tv-app/scripts/dashboard-catalog-tree.test.js
 *
 * `catalog-tree.js` is deliberately free of the DOM, of fetch and of browser storage: it is a pure
 * function from the catalog document to HTML. That is what makes the tree's nesting, ordering and
 * disabled state testable here, with no browser and no server, and it is also why a browser tab can
 * never become a second place the catalog is stored.
 *
 * The last two tests in this file are source-level guards rather than behaviour tests, and they are
 * marked as such: the dashboard is a browser script, so "the catalog is never written to browser
 * storage" and "the view fetches the server's document" are properties of the source that no
 * pure-function test can reach.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const CatalogTree = require('../app/src/main/assets/catalog-tree.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

/** Every node title in the order the renderer emits them, which is the order they are shown in. */
function renderedTitles(html) {
    const titles = [];
    const pattern = /class="catalog-node-title">([^<]*)</g;
    let match;
    while ((match = pattern.exec(html)) !== null) titles.push(match[1]);
    return titles;
}

/**
 * The source without its comments. Both source-level guards below ask what the code *does*; a comment
 * that says "no localStorage here" must not be mistaken for using it.
 */
function code(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');
}

function node(id, parentId, nodeType, title, position, extra) {
    return Object.assign(
        { id: id, parentId: parentId, nodeType: nodeType, title: title, position: position, enabled: true },
        extra || {},
    );
}

/** The shape the phase description uses: two shelves, one with containers, one with a direct video. */
const catalog = [
    node('cat-cartoon', null, 'CATEGORY', 'Cartoons', 0),
    node('i-cocomelon', 'cat-cartoon', 'SUBCATEGORY', 'Cocomelon', 0, { youtubePlaylistId: 'PLcocomelon' }),
    node('i-cocomelon#a', 'i-cocomelon', 'VIDEO', 'Video A', 0, { youtubeVideoId: 'vidA', youtubePlaylistId: 'PLcocomelon' }),
    node('i-cocomelon#b', 'i-cocomelon', 'VIDEO', 'Video B', 1, { youtubeVideoId: 'vidB', youtubePlaylistId: 'PLcocomelon' }),
    node('i-cocomelon#c', 'i-cocomelon', 'VIDEO', 'Video C', 2, { youtubeVideoId: 'vidC', youtubePlaylistId: 'PLcocomelon' }),
    node('i-bluey', 'cat-cartoon', 'SUBCATEGORY', 'Bluey', 1, { youtubePlaylistId: 'PLbluey' }),
    node('i-bluey#d', 'i-bluey', 'VIDEO', 'Video D', 0, { youtubeVideoId: 'vidD', youtubePlaylistId: 'PLbluey' }),
    node('i-direct-a', 'cat-cartoon', 'VIDEO', 'Direct Video A', 2, { youtubeVideoId: 'vidDirectA' }),
    node('i-direct-b', 'cat-cartoon', 'VIDEO', 'Direct Video B', 3, { youtubeVideoId: 'vidDirectB' }),
    node('cat-music', null, 'CATEGORY', 'Music', 1),
    node('i-nursery', 'cat-music', 'SUBCATEGORY', 'Nursery', 0, { youtubePlaylistId: 'PLnursery' }),
];

test('the whole tree renders, with the containers inside their shelf', () => {
    const html = CatalogTree.render(catalog);

    assert.deepEqual(renderedTitles(html), [
        'Cartoons',
        'Cocomelon', 'Video A', 'Video B', 'Video C',
        'Bluey', 'Video D',
        'Direct Video A', 'Direct Video B',
        'Music', 'Nursery',
    ]);

    // Three levels of nesting, which is what "recursive" means for this tree: each shelf that holds
    // something contributes one child list.
    assert.equal((html.match(/class="catalog-children"/g) || []).length, 4);
});

test('a shelf renders as a category and a container as a subcategory', () => {
    const html = CatalogTree.render(catalog);

    assert.match(html, /catalog-node-category[^>]*>.*?class="catalog-node-type catalog-type-category">Category</s);
    assert.match(html, /catalog-node-subcategory[^>]*>.*?class="catalog-node-type catalog-type-subcategory">Subcategory</s);
    assert.match(html, /catalog-node-video[^>]*>.*?class="catalog-node-type catalog-type-video">Video</s);
});

test('direct videos and containers sit side by side in the order the parent configured', () => {
    const html = CatalogTree.render(catalog);

    const cartoonChildren = renderedTitles(html).slice(1, 9);
    assert.deepEqual(cartoonChildren, [
        'Cocomelon', 'Video A', 'Video B', 'Video C',
        'Bluey', 'Video D',
        'Direct Video A', 'Direct Video B',
    ]);
});

test('position decides the order, and the id only breaks a tie', () => {
    const outOfOrder = [
        node('shelf', null, 'CATEGORY', 'Shelf', 0),
        node('z-last', 'shelf', 'VIDEO', 'Third', 2, { youtubeVideoId: 'vid3' }),
        node('a-first', 'shelf', 'VIDEO', 'First', 0, { youtubeVideoId: 'vid1' }),
        node('m-second', 'shelf', 'VIDEO', 'Second', 1, { youtubeVideoId: 'vid2' }),
    ];

    assert.deepEqual(renderedTitles(CatalogTree.render(outOfOrder)), ['Shelf', 'First', 'Second', 'Third']);

    // The same position on two children is settled by id - the same rule the database reads use.
    const tied = [
        node('shelf', null, 'CATEGORY', 'Shelf', 0),
        node('b-second', 'shelf', 'VIDEO', 'B', 0, { youtubeVideoId: 'vidB' }),
        node('a-first', 'shelf', 'VIDEO', 'A', 0, { youtubeVideoId: 'vidA' }),
    ];
    assert.deepEqual(renderedTitles(CatalogTree.render(tied)), ['Shelf', 'A', 'B']);
});

test('shelves are ordered among themselves the same way', () => {
    const shelves = [
        node('c', null, 'CATEGORY', 'Third', 2),
        node('a', null, 'CATEGORY', 'First', 0),
        node('b', null, 'CATEGORY', 'Second', 1),
    ];

    assert.deepEqual(renderedTitles(CatalogTree.render(shelves)), ['First', 'Second', 'Third']);
});

test('a disabled node is shown as disabled, not hidden', () => {
    const nodes = [
        node('shelf', null, 'CATEGORY', 'Shelf', 0, { enabled: false }),
        node('v1', 'shelf', 'VIDEO', 'Hidden Video', 0, { youtubeVideoId: 'vid1', enabled: false }),
        node('v2', 'shelf', 'VIDEO', 'Shown Video', 1, { youtubeVideoId: 'vid2' }),
    ];

    const html = CatalogTree.render(nodes);

    // The dashboard shows what is stored - a parent has to be able to see what is switched off.
    assert.deepEqual(renderedTitles(html), ['Shelf', 'Hidden Video', 'Shown Video']);
    assert.match(html, /catalog-node-disabled/);
    assert.match(html, /class="catalog-node-enabled is-disabled">disabled</);
    assert.match(html, /class="catalog-node-enabled is-enabled">enabled</);

    assert.equal(CatalogTree.summary(nodes).disabled, 2);
});

test('the row shows the node type, position, identifiers and thumbnail configuration', () => {
    const nodes = [
        node('i1', 'shelf', 'SUBCATEGORY', 'Cocomelon', 3, {
            youtubePlaylistId: 'PLcocomelon',
            thumbnailMode: 'VIDEO',
            thumbnailVideoId: 'vidA',
        }),
    ];

    const html = CatalogTree.render(nodes);

    assert.match(html, /class="catalog-node-position" title="Position among its siblings">#3</);
    assert.match(html, /imports playlist PLcocomelon</);
    assert.match(html, /thumbnail from vidA</);
    assert.match(html, /catalog-node-id">i1</);
});

test('a video shows its video id and where it was imported from', () => {
    const html = CatalogTree.render([
        node('v', 'shelf', 'VIDEO', 'Episode', 0, { youtubeVideoId: 'vidA', youtubePlaylistId: 'PLcocomelon' }),
    ]);

    assert.match(html, /video vidA</);
    assert.match(html, /from playlist PLcocomelon</);
});

test('an AUTO thumbnail adds no chip, because there is nothing to say', () => {
    const html = CatalogTree.render([node('v', 'shelf', 'VIDEO', 'Episode', 0, { youtubeVideoId: 'vidA' })]);

    assert.doesNotMatch(html, /thumbnail/);
});

test('an empty catalog says so rather than rendering nothing at all', () => {
    assert.match(CatalogTree.render([]), /No shelves configured yet/);
    assert.match(CatalogTree.render(null), /No shelves configured yet/);
});

test('the summary counts each kind of node and the disabled ones', () => {
    const counts = CatalogTree.summary(catalog);

    assert.deepEqual(counts, {
        categories: 2,
        subcategories: 3,
        videos: 6,
        disabled: 0,
        unplaced: 0,
        total: 11,
    });
});

test('nodes that cannot be reached from ROOT are shown rather than dropped or followed forever', () => {
    const nodes = [
        node('shelf', null, 'CATEGORY', 'Shelf', 0),
        node('orphan', 'missing-parent', 'VIDEO', 'Orphan', 0, { youtubeVideoId: 'vidO' }),
        node('loop-a', 'loop-b', 'SUBCATEGORY', 'Loop A', 0),
        node('loop-b', 'loop-a', 'SUBCATEGORY', 'Loop B', 0),
    ];

    const html = CatalogTree.render(nodes);

    // The reachable shelf renders normally; the rest is reported as what it is instead of vanishing.
    assert.deepEqual(renderedTitles(html), ['Shelf', 'Orphan', 'Loop A', 'Loop B']);
    assert.match(html, /Not reachable from ROOT/);
    assert.equal(CatalogTree.summary(nodes).unplaced, 3);
});

test('a cycle terminates instead of hanging the renderer', () => {
    const cycle = [
        node('a', 'b', 'SUBCATEGORY', 'A', 0),
        node('b', 'a', 'SUBCATEGORY', 'B', 0),
    ];

    const html = CatalogTree.render(cycle);

    assert.match(html, /Not reachable from ROOT/);
    assert.equal(CatalogTree.order(cycle).roots.length, 0);
});

test('a title is escaped, so a name cannot inject markup into the dashboard', () => {
    const html = CatalogTree.render([
        node('shelf', null, 'CATEGORY', '<img src=x onerror="alert(1)">', 0),
        node('v', 'shelf', 'VIDEO', 'Tom & Jerry <b>', 0, { youtubeVideoId: 'vidA' }),
    ]);

    assert.doesNotMatch(html, /<img/);
    assert.match(html, /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt;/);
    assert.match(html, /Tom &amp; Jerry &lt;b&gt;/);
});

test('the rendered tree is a tree: the editor controls live in the panel, not in the rows', () => {
    const html = CatalogTree.render(catalog, { selectedId: 'i-cocomelon' });

    // Rows carry the id a click landed on and can be marked selected...
    assert.match(html, /data-node-id="i-cocomelon"/);
    assert.match(html, /catalog-node-row is-selected" data-node-id="i-cocomelon"/);

    // ...but the rendering itself still offers no control of any kind.
    assert.doesNotMatch(html, /<button/);
    assert.doesNotMatch(html, /<input/);
    assert.doesNotMatch(html, /<form/);
    assert.doesNotMatch(html, /<select/);
    assert.doesNotMatch(html, /ondragstart/);
    assert.doesNotMatch(html, /contenteditable/);
    assert.doesNotMatch(html, /onclick/);
});

test('the renderer never touches the DOM, the network or browser storage', () => {
    const source = code('catalog-tree.js');

    // This is the property that keeps the dashboard a *view*: the catalog it shows can only ever have
    // come from the document it was handed.
    ['document.', 'window.', 'fetch(', 'XMLHttpRequest', 'localStorage', 'sessionStorage', 'indexedDB', 'setTimeout']
        .forEach((forbidden) => {
            assert.ok(
                !source.includes(forbidden),
                'catalog-tree.js must not reference ' + forbidden,
            );
        });
});

// --- source-level guards -------------------------------------------------------------
// The dashboard is a browser script, so these two properties cannot be reached by a pure function:
// they are assertions about the source that ships.

test('the dashboard stores only the session token and a UI dismissal flag', () => {
    const appSource = code('app.js');

    const storageLines = appSource.split('\n')
        .map((line, index) => ({ line: line.trim(), number: index + 1 }))
        .filter((entry) => /localStorage|sessionStorage/.test(entry.line));

    assert.ok(storageLines.length > 0, 'the session token has to live somewhere');

    storageLines.forEach((entry) => {
        assert.doesNotMatch(
            entry.line,
            /catalog/i,
            'app.js:' + entry.number + ' touches browser storage near the catalog: ' + entry.line,
        );
        assert.match(
            entry.line,
            /STORAGE_KEY|DISMISS_KEY|removeItem/,
            'app.js:' + entry.number + ' uses browser storage for something other than the session: ' + entry.line,
        );
    });
});

test('the catalog view fetches the server document and renders it into the page', () => {
    const appSource = code('app.js');
    const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');

    // The view reads the server's document on load and on reload; it never renders a stored copy.
    assert.match(appSource, /apiCall\('GET', '\/catalog'\)/);
    assert.match(appSource, /CatalogEditor\.reload\(/);
    assert.match(appSource, /CatalogTree\.render\(editorSession\.nodes/);
    assert.match(appSource, /getElementById\('catalog-tree'\)/);
    assert.match(appSource, /loadCatalog\(\);/);

    // And the page has the section, the tree, and the scripts the view needs.
    assert.match(html, /id="catalog-section"/);
    assert.match(html, /id="catalog-tree"/);
    assert.match(html, /<script src="catalog-tree.js"><\/script>/);
    assert.match(html, /<script src="catalog-editor.js"><\/script>/);
    // The dashboard page itself carries no catalog data: it is fetched at runtime or not at all.
    assert.doesNotMatch(html, /"nodes"\s*:/);
});
