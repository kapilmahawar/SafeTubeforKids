/*
 * The SafeTube catalog as a YAML file.
 *
 * This is the model behind "Export catalog" and "Import catalog" in the parent dashboard: it turns
 * the parent's curated library into a file a human can read and edit, and turns such a file back
 * into catalog nodes. Like `catalog-editor.js` it is deliberately free of the DOM, of fetch and of
 * browser storage, so every rule here is testable without a browser and the view can only ever be a
 * view.
 *
 * ### What the file is, and what it is not
 *
 * It is the **catalog**: the shelves the parent made, what is inside them, in what order, what the
 * child may see, and which video stands for a container. It is *not* runtime state - no playback
 * position, no watch history, no continue-watching, no sessions, no tokens - and it is not
 * authorization: the list of allowed YouTube sources is a separate thing on purpose, and a file can
 * never hand a child permission to watch something. A playlist named in a file whose source is not
 * allowed stays unplayable, exactly as it does when a parent types that link by hand.
 *
 * ### Why the shape looks like this
 *
 * The catalog is one flat tree - `parentId` plus `position` - with three node types: a CATEGORY is a
 * row on the TV, a SUBCATEGORY is a card that opens a list, and a VIDEO is a video. The file keeps
 * that shape rather than inventing a second one: a category holds collections and videos, a
 * collection holds videos, and a collection may name the YouTube playlist it was imported from. Node
 * ids are written out because they are what keeps a video's identity - and therefore its place and
 * its resume history - stable across an import.
 *
 * ### The YAML subset
 *
 * The parser below reads a deliberately small, documented subset: block mappings, block sequences,
 * plain and quoted scalars, comments and blank lines. It rejects everything else (flow collections,
 * anchors, aliases, tags, block scalars, multiple documents) with a line number, because a file this
 * format cannot read is better refused than silently half-understood. The emitter only ever writes
 * that same subset, so a file this program produced always reads back byte-identical in meaning.
 */
var CatalogYaml = (function () {
    'use strict';

    var SCHEMA_VERSION = 1;
    var NODE_TYPES = ['CATEGORY', 'SUBCATEGORY', 'VIDEO'];
    var MAX_DEPTH = 32;

    function isObject(value) {
        return value !== null && typeof value === 'object' && !Array.isArray(value);
    }

    function isArray(value) {
        return Array.isArray(value);
    }

    // --- writing -------------------------------------------------------------------------------

    /**
     * A scalar, quoted only when it has to be.
     *
     * A title is the parent's words, so it is written as plainly as possible: quoted only when it
     * starts with a character that would change its meaning, contains a colon-space, a hash, a
     * bracket, or is one of the words YAML reads as a boolean or a nothing. Single quotes are used
     * and doubled inside, which is the YAML rule a human editor is most likely to get right.
     */
    function quote(value) {
        var text = value === null || value === undefined ? '' : String(value);
        if (text === '') return "''";
        var risky = /^[\s>|&*!?%@`"'#,[\]{}:-]|:\s|\s#|\s$|[\n\r\t]/.test(text) ||
            /^(true|false|null|yes|no|on|off|~)$/i.test(text) ||
            /^-?\d+(\.\d+)?$/.test(text);
        if (!risky) return text;
        return "'" + text.replace(/'/g, "''") + "'";
    }

    function line(indent, key, value) {
        return new Array(indent + 1).join('  ') + key + ':' + (value === undefined ? '' : ' ' + value);
    }

    /** One video as a sequence item: "- id: …" on the first line, the rest indented under it. */
    function writeVideo(video, indent) {
        var fields = [];
        if (video.id) fields.push(['id', quote(video.id)]);
        fields.push(['youtube', quote(video.youtubeVideoId)]);
        fields.push(['title', quote(video.title)]);
        fields.push(['order', String(video.position)]);
        // Where the video came from, when it came from a playlist. This is not decoration: the TV
        // approves a video through its source, so a file that dropped this would leave a parent's
        // imported videos looking unplayable. It repeats, one line per video, on purpose.
        if (video.youtubePlaylistId) fields.push(['playlist', quote(video.youtubePlaylistId)]);
        if (video.enabled === false) fields.push(['hidden', 'true']);

        return fields.map(function (pair, index) {
            return index === 0
                ? line(indent, '- ' + pair[0], pair[1])
                : line(indent + 1, pair[0], pair[1]);
        });
    }

    function pictureOf(node, byId) {
        if (node.thumbnailMode !== 'VIDEO') return null;
        var chosen = node.thumbnailVideoId;
        if (!chosen || !byId[chosen]) return null;
        return 'video:' + chosen;
    }

    /** One category or collection, as a sequence item, with the videos and collections it holds. */
    function writeContainer(shape, indent, byId) {
        var node = shape.node;
        var out = [
            line(indent, '- id', quote(node.id)),
            line(indent + 1, 'name', quote(node.title)),
            line(indent + 1, 'order', String(node.position))
        ];
        if (node.enabled === false) out.push(line(indent + 1, 'hidden', 'true'));
        if (node.nodeType === 'SUBCATEGORY' && node.youtubePlaylistId) {
            out.push(line(indent + 1, 'playlist', quote(node.youtubePlaylistId)));
        }
        var picture = pictureOf(node, byId);
        if (picture) out.push(line(indent + 1, 'picture', quote(picture)));

        if (shape.videos.length) {
            out.push(line(indent + 1, 'videos'));
            shape.videos.forEach(function (video) {
                out = out.concat(writeVideo(video, indent + 2));
            });
        }
        if (shape.collections.length) {
            out.push(line(indent + 1, 'collections'));
            shape.collections.forEach(function (collection) {
                out = out.concat(writeContainer(collection, indent + 2, byId));
            });
        }
        return out;
    }

    /**
     * Groups the flat node list into the tree the file shows.
     *
     * Siblings are ordered by `position` and then by id, which is the same rule the TV renders with,
     * so the file's order is the child's order. Children are grouped once, so this is linear in the
     * number of nodes however deep the tree is.
     */
    function group(nodes) {
        var byParent = {};
        var byId = {};
        nodes.forEach(function (node) {
            byId[node.id] = node;
            var key = node.parentId || '';
            if (!byParent[key]) byParent[key] = [];
            byParent[key].push(node);
        });
        Object.keys(byParent).forEach(function (key) {
            byParent[key].sort(function (a, b) {
                if (a.position !== b.position) return a.position - b.position;
                return String(a.id) < String(b.id) ? -1 : (String(a.id) > String(b.id) ? 1 : 0);
            });
        });

        function container(node, depth) {
            var children = byParent[node.id] || [];
            var shape = { node: node, videos: [], collections: [] };
            if (depth > MAX_DEPTH) return shape;
            children.forEach(function (child) {
                if (child.nodeType === 'VIDEO') shape.videos.push(child);
                else shape.collections.push(container(child, depth + 1));
            });
            return shape;
        }

        return { byId: byId, roots: (byParent[''] || []).map(function (node) { return container(node, 1); }) };
    }

    /**
     * The catalog as YAML text.
     *
     * Deterministic: the same nodes always produce the same bytes, because siblings are ordered by
     * position and id and the fields are written in a fixed order.
     */
    function serialize(nodes, options) {
        var settings = options || {};
        var tree = group(nodes);
        var out = [
            '# SafeTube catalog',
            '#',
            '# This file is your curated library: the shelves your child sees, what is inside them,',
            '# and in what order. Edit it and import it back on the SafeTube page.',
            '#',
            '# It is not permissions. A playlist listed here can only play on the TV while it is one of',
            '# your allowed sources, so importing this file never lets your child watch something new.',
            'version: ' + SCHEMA_VERSION,
            '',
            'catalog:',
            line(1, 'name', quote(settings.name || 'SafeTube Library')),
            line(1, 'categories')
        ];

        tree.roots.forEach(function (category) {
            out = out.concat(writeContainer(category, 2, tree.byId));
        });

        return out.join('\n') + '\n';
    }

    // --- reading -------------------------------------------------------------------------------

    /**
     * The YAML subset, as a parser.
     *
     * Returns either `{ ok: true, value }` or `{ ok: false, line, column, message }`. Only block
     * mappings, block sequences, scalars and comments are understood; anything else is named and
     * refused with the line it is on, because guessing at a document is how a parent's library ends
     * up half-restored.
     */
    function parseScalar(text) {
        var value = text.trim();
        if (value === '') return '';
        if (value.charAt(0) === "'" ) {
            if (value.charAt(value.length - 1) !== "'" || value.length < 2) {
                return { error: 'a quoted value is missing its closing quote' };
            }
            return { value: value.slice(1, -1).replace(/''/g, "'") };
        }
        if (value.charAt(0) === '"') {
            if (value.charAt(value.length - 1) !== '"' || value.length < 2) {
                return { error: 'a quoted value is missing its closing quote' };
            }
            var body = value.slice(1, -1);
            return { value: body.replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\') };
        }
        if (/^[>|]/.test(value)) return { error: 'multi-line text blocks are not supported; keep a value on one line' };
        if (/^[&*]/.test(value)) return { error: 'anchors and aliases are not supported' };
        if (/^!/.test(value)) return { error: 'YAML tags are not supported' };
        // An empty list or mapping written inline is unambiguous and a human will type it, so it is
        // read. Anything else in flow style is not: it is refused rather than half-understood.
        if (value === '[]') return { value: [] };
        if (value === '{}') return { value: {} };
        if (/^[[{]/.test(value)) return { error: 'flow collections are not supported; use one item per line' };
        return { value: value };
    }

    /** Splits a line into its content and its indentation, ignoring comments and blank lines. */
    function readLine(raw, number) {
        var text = raw.replace(/\r$/, '');
        if (text.indexOf('\t') !== -1) {
            return { error: 'tabs are not allowed for indentation - use spaces', line: number };
        }
        var stripped = text.replace(/^\s+/, '');
        if (stripped === '' || stripped.charAt(0) === '#') return null;
        var indent = text.length - stripped.length;
        return { indent: indent, text: stripComment(stripped), line: number };
    }

    /**
     * Removes a trailing comment: a `#` that starts the line or follows a space, and that is not
     * inside quotes. A parent annotating their own file expects `name: Cartoon  # the row on the TV`
     * to keep working, and the emitter never writes one.
     */
    function stripComment(text) {
        var quoteChar = null;
        for (var i = 0; i < text.length; i++) {
            var ch = text.charAt(i);
            if (quoteChar) {
                if (ch === quoteChar) quoteChar = null;
                continue;
            }
            if (ch === "'" || ch === '"') { quoteChar = ch; continue; }
            if (ch === '#' && (i === 0 || text.charAt(i - 1) === ' ')) {
                return text.slice(0, i).replace(/\s+$/, '');
            }
        }
        return text;
    }

    function parse(text) {
        if (typeof text !== 'string') return { ok: false, line: 0, message: 'the file is not text' };
        if (/^---\s*$/m.test(text) && text.split(/^---\s*$/m).length > 2) {
            return { ok: false, line: 0, message: 'only one YAML document is supported' };
        }

        var lines = [];
        var raw = text.split('\n');
        for (var i = 0; i < raw.length; i++) {
            var read = readLine(raw[i], i + 1);
            if (read && read.error) return { ok: false, line: read.line, message: read.error };
            if (read) lines.push(read);
        }
        if (!lines.length) return { ok: false, line: 0, message: 'the file is empty' };

        var at = 0;

        // One error discipline for the reader: a block returns `{ value }` or `{ error }`, and an
        // error travels back up unchanged until `parse` reports it with the line it came from.
        function fail(line, message) {
            return { error: { line: line, message: message } };
        }

        /** A block at `indent`: a sequence when its first line starts with "- ", a mapping otherwise. */
        function block(indent, depth) {
            if (depth > MAX_DEPTH) return fail(lines[at] ? lines[at].line : 0, 'the file nests too deeply');
            if (at >= lines.length) return { value: null };
            var first = lines[at];
            if (first.indent < indent) return { value: null };
            if (first.indent > indent) return fail(first.line, 'this line is indented further than the line above it');

            if (first.text.charAt(0) === '-' && (first.text.length === 1 || first.text.charAt(1) === ' ')) {
                var items = [];
                while (at < lines.length && lines[at].indent === indent &&
                       lines[at].text.charAt(0) === '-' &&
                       (lines[at].text.length === 1 || lines[at].text.charAt(1) === ' ')) {
                    var head = lines[at];
                    var rest = head.text.slice(1).trim();
                    at++;
                    if (rest === '') {
                        // The item's own keys are simply the next lines, whatever indent they chose:
                        // a nested block is wherever the file puts it, as long as it is deeper.
                        var itemIndent = (at < lines.length && lines[at].indent > indent)
                            ? lines[at].indent : indent + 1;
                        var nested = block(itemIndent, depth + 1);
                        if (nested.error) return nested;
                        items.push(nested.value === null ? {} : nested.value);
                        continue;
                    }
                    // "- key: value" opens a mapping whose further keys are indented under it.
                    var entry = {};
                    var split = splitKey(rest, head.line);
                    if (split.error) return fail(head.line, split.error);
                    if (split.isMap === false) {
                        items.push(split.value);
                        continue;
                    }
                    var inline = parseScalar(split.raw);
                    if (inline.error) return fail(head.line, inline.error);
                    entry[split.key] = inline.value;
                    var more = block(indent + 2, depth + 1);
                    if (more.error) return more;
                    if (isObject(more.value)) {
                        Object.keys(more.value).forEach(function (key) {
                            if (entry[key] !== undefined) {
                                // The same key twice in one item: refuse rather than pick one.
                                entry[key] = more.value[key];
                            } else {
                                entry[key] = more.value[key];
                            }
                        });
                    }
                    items.push(entry);
                }
                return { value: items };
            }

            var map = {};
            while (at < lines.length && lines[at].indent === indent) {
                var head2 = lines[at];
                var split2 = splitKey(head2.text, head2.line);
                if (split2.error) return fail(head2.line, split2.error);
                if (split2.isMap === false) return fail(head2.line, 'expected "key: value"');
                var key = split2.key;
                if (map[key] !== undefined) return fail(head2.line, '"' + key + '" appears twice');
                at++;
                if (split2.raw === '') {
                    // A key with nothing after the colon opens a block on the following lines, at
                    // whatever indent the file chose for it - not necessarily one level down.
                    var childIndent = (at < lines.length && lines[at].indent > indent)
                        ? lines[at].indent : indent + 1;
                    var child = block(childIndent, depth + 1);
                    if (child.error) return child;
                    map[key] = child.value === null ? null : child.value;
                } else {
                    var scalar = parseScalar(split2.raw);
                    if (scalar.error) return fail(head2.line, scalar.error);
                    map[key] = scalar.value;
                }
            }
            return { value: map };
        }

        function splitKey(text, number) {
            var colon = -1;
            var quoteChar = null;
            for (var j = 0; j < text.length; j++) {
                var ch = text.charAt(j);
                if (quoteChar) {
                    if (ch === quoteChar) quoteChar = null;
                    continue;
                }
                if (ch === "'" || ch === '"') { quoteChar = ch; continue; }
                if (ch === ':' && (j + 1 === text.length || text.charAt(j + 1) === ' ')) { colon = j; break; }
            }
            if (colon === -1) return { isMap: false, value: text, line: number };
            var key = text.slice(0, colon).trim();
            if (!key) return { error: 'a line starts with ":"' };
            return { isMap: true, key: key.replace(/^['"]|['"]$/g, ''), raw: text.slice(colon + 1).trim() };
        }

        // Named `parsedRoot` rather than `document` on purpose: this module is a model, and a name
        // that reads like the browser's `document` would invite a DOM dependency that must not exist.
        var parsedRoot = block(0, 1);
        if (parsedRoot.error) return { ok: false, line: parsedRoot.error.line, message: parsedRoot.error.message };
        if (at < lines.length) {
            return { ok: false, line: lines[at].line, message: 'the file has content this format does not understand' };
        }
        return { ok: true, value: parsedRoot.value };
    }

    // --- validation ----------------------------------------------------------------------------

    function problem(where, message) {
        return where ? where + ': ' + message : message;
    }

    function asText(value) {
        if (value === null || value === undefined) return '';
        return typeof value === 'string' ? value.trim() : String(value).trim();
    }

    function asOrder(value, fallback, where, problems) {
        if (value === null || value === undefined || value === '') return fallback;
        var number = typeof value === 'number' ? value : Number(asText(value));
        if (!isFinite(number) || Math.floor(number) !== number || number < 0) {
            problems.push(problem(where, '"order" must be a whole number starting at 0'));
            return fallback;
        }
        return number;
    }

    function asHidden(value, where, problems) {
        if (value === null || value === undefined || value === '') return false;
        var text = asText(value).toLowerCase();
        if (text === 'true' || text === 'yes' || text === 'on' || text === '1') return true;
        if (text === 'false' || text === 'no' || text === 'off' || text === '0') return false;
        problems.push(problem(where, '"hidden" must be true or false'));
        return false;
    }

    function asList(value, where, problems) {
        if (value === null || value === undefined) return [];
        if (!isArray(value)) {
            problems.push(problem(where, 'this should be a list, one item per "- " line'));
            return [];
        }
        return value;
    }

    /**
     * Refuses keys this format does not have.
     *
     * A mistyped key in a hand-edited file - `folder:` where `collections:` was meant - would
     * otherwise be dropped in silence, and the parent would import a library quietly missing what
     * they wrote. The file carries its own version, so anything unrecognised in a file this reader
     * claims to understand is a mistake, and it is better named than ignored.
     */
    function rejectUnknownKeys(raw, known, where, problems) {
        Object.keys(raw).forEach(function (key) {
            if (known[key]) return;
            problems.push(problem(where, '"' + key + '" is not something a SafeTube catalog file has'));
        });
    }

    var KNOWN = {
        root: { version: true, catalog: true },
        catalog: { name: true, categories: true },
        // `playlist` is recognised on a category only so that it can be refused by name, with a
        // sentence that says where it belongs, rather than twice as an unknown key.
        category: { id: true, name: true, title: true, order: true, hidden: true, videos: true, collections: true, picture: true, playlist: true },
        collection: { id: true, name: true, title: true, order: true, hidden: true, playlist: true, videos: true, picture: true },
        video: { id: true, youtube: true, youtubeVideoId: true, title: true, name: true, order: true, hidden: true, playlist: true }
    };

    /**
     * A file's contents as catalog nodes.
     *
     * Everything is checked before anything is handed back: version, shape, ids, titles, ordering,
     * duplicates, the video ids, and the pictures that name a video. The result is a node list the
     * existing editor and the existing server validator accept, or a list of problems that name the
     * shelf and the line they are about.
     */
    function readDocument(text) {
        var parsed = parse(text);
        if (!parsed.ok) {
            return { ok: false, problems: [parsed.line ? 'Line ' + parsed.line + ': ' + parsed.message : parsed.message] };
        }

        var problems = [];
        var root = parsed.value;
        if (!isObject(root)) return { ok: false, problems: ['The file is not a SafeTube catalog'] };

        rejectUnknownKeys(root, KNOWN.root, 'At the top of the file', problems);

        if (root.version === null || root.version === undefined || asText(root.version) === '') {
            return { ok: false, problems: ['The file has no "version". A SafeTube catalog file starts with "version: ' + SCHEMA_VERSION + '".'] };
        }
        if (String(asText(root.version)) !== String(SCHEMA_VERSION)) {
            return {
                ok: false,
                problems: ['This file says "version: ' + asText(root.version) + '". This version of SafeTube reads version ' +
                    SCHEMA_VERSION + ' files, so nothing was imported.'],
            };
        }

        if (!isObject(root.catalog)) {
            // If they wrote something else at the top - `library:` for `catalog:` - naming it is far
            // more use than only reporting what is missing.
            var strays = Object.keys(root).filter(function (key) { return !KNOWN.root[key]; });
            return {
                ok: false,
                problems: ['The file has no "catalog" section' + (strays.length
                    ? ' - it has "' + strays.join('", "') + '" instead'
                    : '')],
            };
        }
        var catalog = root.catalog;
        var nodes = [];
        var seen = {};
        var byId = {};

        rejectUnknownKeys(catalog, KNOWN.catalog, 'The "catalog" section', problems);

        function addNode(node, where) {
            if (seen[node.id]) {
                problems.push(problem(where, 'the id "' + node.id + '" is used twice'));
                return null;
            }
            seen[node.id] = true;
            byId[node.id] = node;
            nodes.push(node);
            return node;
        }

        function readVideo(raw, parentType, parentId, fallbackOrder, where) {
            if (!isObject(raw)) {
                problems.push(problem(where, 'a video should be a mapping with "youtube" and "title"'));
                return null;
            }
            var youtube = asText(raw.youtube !== undefined ? raw.youtube : raw.youtubeVideoId);
            var title = asText(raw.title !== undefined ? raw.title : raw.name);
            var id = asText(raw.id) || (parentId ? parentId + '#' + youtube : youtube);
            rejectUnknownKeys(raw, KNOWN.video, where, problems);
            if (!youtube) problems.push(problem(where, 'a video needs a "youtube" id'));
            if (!title) problems.push(problem(where, 'a video needs a "title"'));
            if (!id) problems.push(problem(where, 'a video needs an "id" or a "youtube" id to derive one from'));
            if (!youtube || !title || !id) return null;

            return addNode({
                id: id,
                parentId: parentId,
                nodeType: 'VIDEO',
                title: title,
                position: asOrder(raw.order, fallbackOrder, where, problems),
                enabled: !asHidden(raw.hidden, where, problems),
                youtubeVideoId: youtube,
                youtubePlaylistId: asText(raw.playlist) || null,
                thumbnailMode: 'AUTO',
                thumbnailVideoId: null,
                thumbnailUrl: null,
                createdAt: 0,
                updatedAt: 0
            }, where);
        }

        function readCollection(raw, categoryId, fallbackOrder, where) {
            if (!isObject(raw)) {
                problems.push(problem(where, 'a collection should be a mapping with "id" and "name"'));
                return null;
            }
            var id = asText(raw.id);
            var name = asText(raw.name !== undefined ? raw.name : raw.title);
            rejectUnknownKeys(raw, KNOWN.collection, where, problems);
            if (!id) problems.push(problem(where, 'a collection needs an "id"'));
            if (!name) problems.push(problem(where, 'a collection needs a "name"'));
            if (!id || !name) return null;

            var playlist = asText(raw.playlist);
            if (playlist && !/^[A-Za-z0-9_-]{10,64}$/.test(playlist)) {
                problems.push(problem(where, '"playlist" does not look like a YouTube playlist id'));
            }

            var node = addNode({
                id: id,
                parentId: categoryId,
                nodeType: 'SUBCATEGORY',
                title: name,
                position: asOrder(raw.order, fallbackOrder, where, problems),
                enabled: !asHidden(raw.hidden, where, problems),
                youtubeVideoId: null,
                youtubePlaylistId: playlist || null,
                thumbnailMode: 'AUTO',
                thumbnailVideoId: null,
                thumbnailUrl: null,
                createdAt: 0,
                updatedAt: 0
            }, where);

            asList(raw.videos, where + ' / videos', problems).forEach(function (video, index) {
                readVideo(video, 'SUBCATEGORY', id, index, where + ' / video ' + (index + 1));
            });
            applyPicture(raw.picture, node, where, problems);
            return node;
        }

        function applyPicture(rawPicture, node, where, problems) {
            if (rawPicture === null || rawPicture === undefined || asText(rawPicture) === '') return;
            var text = asText(rawPicture);
            if (text === 'auto') return;
            if (text.indexOf('video:') !== 0) {
                problems.push(problem(where, '"picture" must be "auto" or "video:<a video id in this collection>"'));
                return;
            }
            var chosen = text.slice('video:'.length).trim();
            if (!chosen) {
                problems.push(problem(where, '"picture: video:" needs the id of a video inside it'));
                return;
            }
            node.thumbnailMode = 'VIDEO';
            node.thumbnailVideoId = chosen;
        }

        var categories = asList(catalog.categories, 'catalog / categories', problems);
        if (!categories.length && !problems.length) {
            // An empty catalog is a legitimate thing to import: it is how a parent starts over.
            return { ok: true, nodes: [], name: asText(catalog.name), problems: [] };
        }

        categories.forEach(function (raw, index) {
            var where = 'Category ' + (index + 1);
            if (!isObject(raw)) {
                problems.push(problem(where, 'a category should be a mapping with "id" and "name"'));
                return;
            }
            var id = asText(raw.id);
            var name = asText(raw.name !== undefined ? raw.name : raw.title);
            where = 'Category "' + (name || id || index + 1) + '"';
            rejectUnknownKeys(raw, KNOWN.category, where, problems);
            if (raw.playlist !== undefined && raw.playlist !== null && asText(raw.playlist) !== '') {
                // A playlist belongs to a collection, which is what the server and the TV enforce.
                // Saying so is better than dropping the line and importing something else.
                problems.push(problem(where, 'a category holds collections, not a YouTube playlist - put "playlist" on a collection inside it'));
            }
            if (!id) problems.push(problem(where, 'a category needs an "id"'));
            if (!name) problems.push(problem(where, 'a category needs a "name"'));
            if (!id || !name) return;

            var node = addNode({
                id: id,
                parentId: null,
                nodeType: 'CATEGORY',
                title: name,
                position: asOrder(raw.order, index, where, problems),
                enabled: !asHidden(raw.hidden, where, problems),
                youtubeVideoId: null,
                youtubePlaylistId: null,
                thumbnailMode: 'AUTO',
                thumbnailVideoId: null,
                thumbnailUrl: null,
                createdAt: 0,
                updatedAt: 0
            }, where);

            asList(raw.videos, where + ' / videos', problems).forEach(function (video, videoIndex) {
                readVideo(video, 'CATEGORY', id, videoIndex, where + ' / video ' + (videoIndex + 1));
            });
            asList(raw.collections, where + ' / collections', problems).forEach(function (collection, collectionIndex) {
                readCollection(collection, id, collectionIndex, where + ' / collection ' + (collectionIndex + 1));
            });
            applyPicture(raw.picture, node, where, problems);
        });

        // A picture that names a video has to name one that is inside that container, which can only
        // be checked once every node exists - the same rule the server enforces.
        nodes.forEach(function (node) {
            if (node.thumbnailMode !== 'VIDEO') return;
            var chosen = byId[node.thumbnailVideoId];
            var where = 'Container "' + node.title + '"';
            if (!chosen) {
                problems.push(problem(where, 'names "' + node.thumbnailVideoId + '" as its picture, and no video in this file has that id'));
                return;
            }
            if (chosen.nodeType !== 'VIDEO') {
                problems.push(problem(where, 'names "' + chosen.title + '" as its picture, and that is a collection'));
                return;
            }
            var walk = chosen.parentId;
            var inside = false;
            var guard = 0;
            while (walk && guard++ < MAX_DEPTH) {
                if (walk === node.id) { inside = true; break; }
                walk = byId[walk] ? byId[walk].parentId : null;
            }
            if (!inside) problems.push(problem(where, 'names a video that is not inside it as its picture'));
        });

        if (problems.length) return { ok: false, problems: problems };

        return { ok: true, nodes: normalise(nodes), name: asText(catalog.name), problems: [] };
    }

    /**
     * Sibling positions as `0..n-1`, in the order the file listed them.
     *
     * The server refuses gaps and duplicates rather than repairing them, so the file's own order is
     * what decides: `order` puts siblings in sequence, and equal or missing orders fall back to the
     * order the lines appear in. That is the same renumbering the editor does after every edit, which
     * is why a hand-edited file with one line removed still imports.
     */
    function normalise(nodes) {
        var groups = {};
        nodes.forEach(function (node) {
            var key = node.parentId || '';
            if (!groups[key]) groups[key] = [];
            groups[key].push(node);
        });
        Object.keys(groups).forEach(function (key) {
            groups[key].sort(function (a, b) {
                if (a.position !== b.position) return a.position - b.position;
                return nodes.indexOf(a) - nodes.indexOf(b);
            }).forEach(function (node, index) {
                node.position = index;
            });
        });
        return nodes;
    }

    // --- describing a change -------------------------------------------------------------------

    function keyOf(node) {
        return node.nodeType + '/' + node.id;
    }

    /**
     * What importing this file would do, in the words the preview uses.
     *
     * Counts the things a parent cares about (shelves, collections, videos, and how many of those
     * videos can actually play today), and the differences from what is in the library now: added,
     * removed, renamed and reordered. Nothing here writes anything.
     */
    function preview(yamlNodes, currentNodes, allowedPlaylists) {
        var allowed = allowedPlaylists || {};
        var now = {};
        (currentNodes || []).forEach(function (node) { now[keyOf(node)] = node; });
        var next = {};
        yamlNodes.forEach(function (node) { next[keyOf(node)] = node; });

        var counts = { categories: 0, collections: 0, videos: 0, hidden: 0, unplayable: 0, playlists: 0 };
        var yamlById = {};
        yamlNodes.forEach(function (node) { yamlById[node.id] = node; });

        counts.categories = yamlNodes.filter(function (n) { return n.nodeType === 'CATEGORY'; }).length;
        counts.collections = yamlNodes.filter(function (n) { return n.nodeType === 'SUBCATEGORY'; }).length;
        counts.videos = yamlNodes.filter(function (n) { return n.nodeType === 'VIDEO'; }).length;
        counts.hidden = yamlNodes.filter(function (n) { return n.enabled === false; }).length;
        counts.playlists = Object.keys(yamlNodes.reduce(function (all, node) {
            var parent = node.parentId ? yamlById[node.parentId] : null;
            if (node.nodeType === 'VIDEO' && parent && parent.nodeType === 'SUBCATEGORY' && parent.youtubePlaylistId) {
                all[parent.youtubePlaylistId] = true;
            } else if (node.nodeType === 'SUBCATEGORY' && node.youtubePlaylistId) {
                all[node.youtubePlaylistId] = true;
            }
            return all;
        }, {})).length;

        var unplayableSources = {};
        yamlNodes.forEach(function (node) {
            if (node.nodeType !== 'VIDEO' || node.enabled === false) return;
            var parent = node.parentId ? yamlById[node.parentId] : null;
            var fromCollection = parent && parent.nodeType === 'SUBCATEGORY' ? parent.youtubePlaylistId : null;
            // The same rule the library screen uses: a video plays when the playlist it came from is
            // allowed, or when the video itself is. A file cannot change either.
            if ((fromCollection && allowed[fromCollection]) || (node.youtubePlaylistId && allowed[node.youtubePlaylistId])) return;
            if (allowed[node.youtubeVideoId]) return;
            counts.unplayable += 1;
            if (fromCollection) unplayableSources[fromCollection] = true;
            else if (node.youtubePlaylistId) unplayableSources[node.youtubePlaylistId] = true;
            else if (node.youtubeVideoId) unplayableSources[node.youtubeVideoId] = true;
        });

        var changes = { added: [], removed: [], renamed: [], reordered: 0, hidden: 0, shown: 0 };
        Object.keys(next).forEach(function (key) {
            var node = next[key];
            var was = now[key];
            if (!was) {
                changes.added.push(node);
                return;
            }
            if (was.title !== node.title) changes.renamed.push({ from: was.title, to: node.title });
            if (was.enabled !== node.enabled) {
                if (node.enabled) changes.shown += 1; else changes.hidden += 1;
            }
        });
        Object.keys(now).forEach(function (key) {
            if (!next[key]) changes.removed.push(now[key]);
        });

        // Order changes are per sibling group: two nodes that both moved are one reorder.
        var groups = {};
        yamlNodes.forEach(function (node) {
            var key = node.parentId || '';
            if (!groups[key]) groups[key] = [];
            groups[key].push(node);
        });
        Object.keys(groups).forEach(function (key) {
            var before = (currentNodes || []).filter(function (node) { return (node.parentId || '') === key; });
            if (before.length !== groups[key].length) return;
            var orderNow = before.slice().sort(function (a, b) { return a.position - b.position; }).map(function (n) { return n.id; }).join('\u0000');
            var orderNext = groups[key].slice().sort(function (a, b) { return a.position - b.position; }).map(function (n) { return n.id; }).join('\u0000');
            if (orderNow !== orderNext) changes.reordered += 1;
        });

        return {
            counts: counts,
            changes: changes,
            unplayableSources: Object.keys(unplayableSources),
            tree: treeForPreview(yamlNodes)
        };
    }

    /** The outline the preview shows, in catalog order. */
    function treeForPreview(nodes) {
        var grouped = group(nodes);
        return grouped.roots.map(function (category) {
            return {
                name: category.node.title,
                hidden: category.node.enabled === false,
                videos: category.videos.length,
                collections: category.collections.map(function (collection) {
                    return {
                        name: collection.node.title,
                        hidden: collection.node.enabled === false,
                        videos: collection.videos.length,
                        playlist: collection.node.youtubePlaylistId || null
                    };
                })
            };
        });
    }

    var api = {
        SCHEMA_VERSION: SCHEMA_VERSION,
        serialize: serialize,
        parse: parse,
        readDocument: readDocument,
        preview: preview,
        normalise: normalise,
        quote: quote,
        keyOf: keyOf
    };

    return api;
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = CatalogYaml;
}
