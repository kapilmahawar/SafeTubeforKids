/*
 * The parent's catalog editor: the working copy and every mutation a parent can make to it.
 *
 * This file is the *model*: it is deliberately free of the DOM, of fetch and of browser storage, and
 * every function here takes a session and returns a new one, so every mutation is testable without a
 * browser and the view - `app.js` - can only ever be a view.
 *
 * ### What a session is
 *
 * A session is `{ catalogVersion, nodes, savedNodes }`:
 *
 *  - `catalogVersion` is the version the working copy was read from. It is the *only* version the
 *    editor may send as `expectedCatalogVersion`, and the editor never invents or increments one -
 *    the server owns that number (contract v2).
 *  - `nodes` is the working copy: a flat list of nodes in the same shape the server stores, each
 *    naming its `parentId`. It is never nested, because the wire contract is not nested.
 *  - `savedNodes` is what the server last confirmed, so the view can say whether there is anything
 *    unsaved.
 *
 * Nothing is persisted here. A refresh fetches the server's document again; unsaved edits are gone,
 * which the UI says out loud.
 *
 * ### The one invariant every mutation keeps
 *
 * For every parent, its children are numbered `0..n-1`, ordered by `position ASC, id ASC`. Every
 * mutation below ends by normalising the whole tree, so the editor cannot produce a sparse or
 * duplicated position list even by accident, and a sibling's position is never used as an
 * alternative ordering key. Positions only ever change as a *result* of an edit, never as the edit.
 *
 * ### The browser is not the authority
 *
 * `problems(session)` gives a parent immediate feedback on the rules the editor itself must not
 * break - a blank title, an impossible parent, a video with no video id - but it is a convenience.
 * The server validates the complete tree again on every write and is the only thing that decides
 * whether a catalog is publishable, so no rule here is allowed to be the last word.
 */
var CatalogEditor = (function () {
    'use strict';

    var CATEGORY = 'CATEGORY';
    var SUBCATEGORY = 'SUBCATEGORY';
    var VIDEO = 'VIDEO';
    var SCHEMA_VERSION = 2;

    var NODE_TYPES = [CATEGORY, SUBCATEGORY, VIDEO];

    /** Which node types each kind of parent may hold. ROOT (null) takes only a CATEGORY. */
    function childTypesOf(parentType) {
        if (parentType === null || parentType === undefined) return [CATEGORY];
        if (parentType === CATEGORY) return [SUBCATEGORY, VIDEO];
        if (parentType === SUBCATEGORY) return [VIDEO];
        return [];
    }

    function clone(value) {
        return JSON.parse(JSON.stringify(value));
    }

    /** The same order every read in the system uses: `position ASC, id ASC`. */
    function byRenderOrder(a, b) {
        var pa = typeof a.position === 'number' ? a.position : 0;
        var pb = typeof b.position === 'number' ? b.position : 0;
        if (pa !== pb) return pa - pb;
        return String(a.id) < String(b.id) ? -1 : (String(a.id) > String(b.id) ? 1 : 0);
    }

    // --- reading a session -----------------------------------------------------------------

    function nodeById(session, id) {
        for (var i = 0; i < session.nodes.length; i++) {
            if (session.nodes[i].id === id) return session.nodes[i];
        }
        return null;
    }

    /** A parent's children, in render order. */
    function childrenOf(session, parentId) {
        return session.nodes
            .filter(function (node) { return (node.parentId || null) === (parentId || null); })
            .sort(byRenderOrder);
    }

    /** The node's own id and every id beneath it - what a delete removes and a move must not create. */
    function subtreeIds(session, id) {
        var ids = [id];
        var pending = [id];
        while (pending.length) {
            var parent = pending.shift();
            session.nodes.forEach(function (node) {
                if (node.parentId === parent) {
                    ids.push(node.id);
                    pending.push(node.id);
                }
            });
        }
        return ids;
    }

    function roots(session) {
        return childrenOf(session, null);
    }

    /** Every parent a node of `type` may be placed under, with ROOT represented as null. */
    function validParentsFor(session, type) {
        var parents = [null];
        session.nodes.forEach(function (node) {
            if (childTypesOf(node.nodeType).indexOf(type) !== -1) parents.push(node.id);
        });
        return parents;
    }

    function depthOf(session, node) {
        var depth = 0;
        var current = node;
        var guard = 0;
        while (current && current.parentId && guard++ < 64) {
            depth++;
            current = nodeById(session, current.parentId);
        }
        return depth;
    }

    // --- opening, normalising and serialising ----------------------------------------------

    /**
     * Opens a working copy of a server document. The document handed in is never mutated.
     *
     * The parameter is named `snapshot` rather than `document` on purpose: this module is a model, and
     * a name that reads like the browser's `document` would both invite a DOM dependency and make it
     * impossible to prove by inspection that there is none.
     */
    function open(snapshot, options) {
        var settings = options || {};
        return {
            catalogVersion: snapshot && typeof snapshot.catalogVersion === 'number' ? snapshot.catalogVersion : 0,
            nodes: normalizeNodes(clone((snapshot && snapshot.nodes) || [])),
            savedNodes: normalizeNodes(clone((snapshot && snapshot.nodes) || [])),
            newId: settings.newId || defaultNewId
        };
    }

    /**
     * Renumbers every sibling group to `0..n-1`, in the order the tree already renders in.
     *
     * A pure function of the list, so "normalise" can never reorder anything: it only replaces the
     * numbers with dense ones, which is what keeps the invariant true after an insert, a delete or a
     * move *and* keeps the configured order the parent sees.
     */
    function normalizeNodes(nodes) {
        var groups = {};
        nodes.forEach(function (node) {
            var key = node.parentId || '\u0000root';
            if (!groups[key]) groups[key] = [];
            groups[key].push(node);
        });
        Object.keys(groups).forEach(function (key) {
            groups[key].sort(byRenderOrder).forEach(function (node, index) {
                node.position = index;
            });
        });
        return nodes;
    }

    function normalize(session) {
        return {
            catalogVersion: session.catalogVersion,
            nodes: normalizeNodes(session.nodes),
            savedNodes: session.savedNodes,
            newId: session.newId
        };
    }

    /** The document to send: contract v2, with the working copy's nodes. */
    function toDocument(session) {
        return { schemaVersion: SCHEMA_VERSION, catalogVersion: session.catalogVersion, nodes: clone(session.nodes) };
    }

    /** A stable key for "is this the same catalog", independent of the order of the array. */
    function canonicalJson(nodes) {
        return JSON.stringify(clone(nodes).sort(function (a, b) {
            return String(a.id) < String(b.id) ? -1 : (String(a.id) > String(b.id) ? 1 : 0);
        }));
    }

    function isDirty(session) {
        return canonicalJson(session.nodes) !== canonicalJson(session.savedNodes);
    }

    // --- the client-side pre-flight (never the authority) -----------------------------------

    /**
     * The structural rules the editor must not be able to break. The server runs the full validator
     * again - including the YouTube identifier and thumbnail rules this list deliberately leaves to
     * it - and its answer is the one that decides.
     */
    function problems(session) {
        var found = [];
        var byId = {};
        var nodes = session.nodes;

        nodes.forEach(function (node) {
            if (!node.id || !String(node.id).trim()) found.push('a node needs a non-blank id');
            else if (byId[node.id]) found.push('duplicate node id ' + node.id);
            else byId[node.id] = node;

            if (NODE_TYPES.indexOf(node.nodeType) === -1) {
                found.push('unsupported node type "' + node.nodeType + '"');
            }
            if (typeof node.title !== 'string' || !node.title.trim()) {
                found.push('node ' + node.id + ' needs a non-blank title');
            }
            if (typeof node.position !== 'number' || node.position < 0) {
                found.push('node ' + node.id + ' has an invalid position');
            }
            if (node.nodeType === VIDEO && !(node.youtubeVideoId && String(node.youtubeVideoId).trim())) {
                found.push('video ' + node.id + ' needs a youtubeVideoId');
            }
            if (node.nodeType === SUBCATEGORY && node.youtubeVideoId) {
                found.push('container ' + node.id + ' must not carry a youtubeVideoId');
            }
            if (node.nodeType === CATEGORY && (node.youtubeVideoId || node.youtubePlaylistId)) {
                found.push('shelf ' + node.id + ' must not carry a YouTube identifier');
            }
            if (node.nodeType === CATEGORY && node.parentId) {
                found.push('shelf ' + node.id + ' must sit at ROOT');
            }
        });

        nodes.forEach(function (node) {
            var parentId = node.parentId || null;
            if (parentId === null) return;

            var parent = byId[parentId];
            if (!parent) {
                found.push('node ' + node.id + ' names a parent that does not exist');
                return;
            }
            if (childTypesOf(parent.nodeType).indexOf(node.nodeType) === -1) {
                found.push('a ' + parent.nodeType + ' cannot hold ' + node.nodeType + ' ' + node.id);
            }
        });

        nodes.forEach(function (node) {
            var seen = {};
            var current = node;
            var guard = 0;
            while (current && current.parentId && guard++ < 64) {
                if (seen[current.id]) {
                    found.push('node ' + node.id + ' is inside a cycle of parents');
                    return;
                }
                seen[current.id] = true;
                current = byId[current.parentId];
            }
        });

        var grouped = groupByParent(nodes);
        Object.keys(grouped).forEach(function (key) {
            var children = grouped[key];
            var positions = children.map(function (node) { return node.position; }).sort(function (a, b) { return a - b; });
            for (var i = 0; i < positions.length; i++) {
                if (positions[i] !== i) {
                    found.push('positions under ' + (key === '\u0000root' ? 'ROOT' : key) + ' must be 0..' + (positions.length - 1));
                    return;
                }
            }
        });

        // Thumbnails, once every node and parent is known: what a container says about its picture only
        // means something in terms of the tree around it. These mirror what the server checks, so a
        // document the browser lets through is not one the server is guaranteed to take - the server is
        // still the authority - but the parent is told here rather than after pressing Save.
        nodes.forEach(function (node) {
            if (node.thumbnailUrl) {
                found.push('node ' + node.id + ' names a picture URL; a picture comes from the app, not from a link');
            }

            var mode = thumbnailModeOf(node);
            if (mode === null) {
                found.push('node ' + node.id + ' has an unsupported thumbnail mode "' + node.thumbnailMode + '"');
                return;
            }

            var selection = typeof node.thumbnailVideoId === 'string' && node.thumbnailVideoId.trim()
                ? node.thumbnailVideoId.trim()
                : null;

            if (node.nodeType === VIDEO) {
                if (mode !== THUMBNAIL_AUTO || selection) {
                    found.push('video ' + node.id + ' uses its own picture and must not choose one');
                }
                return;
            }
            if (node.nodeType !== CATEGORY && node.nodeType !== SUBCATEGORY) return;

            if (mode === THUMBNAIL_AUTO) {
                if (selection) found.push('node ' + node.id + ' names a thumbnail video while its mode is AUTO');
                return;
            }

            if (!selection) {
                found.push('node ' + node.id + ' chooses a video as its picture but names none');
                return;
            }

            var chosen = byId[selection];
            if (!chosen) {
                found.push('node ' + node.id + ' names thumbnail video ' + selection + ', which is not in this catalog');
                return;
            }
            if (chosen.nodeType !== VIDEO) {
                found.push('node ' + node.id + ' names ' + selection + ' as its picture, and that is a ' + chosen.nodeType);
                return;
            }
            if (!isDescendantOf(session, selection, node.id)) {
                found.push('thumbnail video ' + selection + ' is not inside ' + node.id);
                return;
            }
            if (!thumbnailSourceOf(chosen)) {
                found.push('thumbnail video ' + selection + ' has no YouTube id to take a picture from');
            }
        });

        return found;
    }

    function groupByParent(nodes) {
        var groups = {};
        nodes.forEach(function (node) {
            var key = node.parentId || '\u0000root';
            if (!groups[key]) groups[key] = [];
            groups[key].push(node);
        });
        return groups;
    }

    // --- mutations ---------------------------------------------------------------------------
    //
    // Every mutation returns `{ ok: true, session }` or `{ ok: false, reason }`. Nothing throws and
    // nothing is written anywhere: a refused edit leaves the session exactly as it was, which is what
    // lets the view show a reason without having to undo a half-applied change.

    function refuse(reason) {
        return { ok: false, reason: reason };
    }

    function accept(session) {
        return { ok: true, session: normalize(session) };
    }

    function withNodes(session, nodes) {
        return { catalogVersion: session.catalogVersion, nodes: nodes, savedNodes: session.savedNodes, newId: session.newId };
    }

    function defaultNewId(type) {
        return String(type).toLowerCase() + '-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    }

    /** A fresh id that is not already in the tree, whatever generator the caller supplied. */
    function freshId(session, type) {
        return freshIdIn(session.nodes, session.newId, type);
    }

    function freshIdIn(nodes, newId, type) {
        var taken = {};
        nodes.forEach(function (node) { taken[node.id] = true; });

        for (var attempt = 0; attempt < 10; attempt++) {
            var candidate = newId(type);
            if (candidate && !taken[candidate]) return candidate;
        }
        // Last resort: a counter, so a pathological generator still cannot produce a duplicate.
        var n = 1;
        while (taken[String(type).toLowerCase() + '-' + n]) n++;
        return String(type).toLowerCase() + '-' + n;
    }

    function baseNode(id, parentId, nodeType, title, position) {
        return {
            id: id,
            parentId: parentId,
            nodeType: nodeType,
            title: title,
            position: position,
            enabled: true,
            // New nodes carry no timestamps; the server stamps its own clock when it stores them.
            youtubeVideoId: null,
            youtubePlaylistId: null,
            thumbnailMode: 'AUTO',
            thumbnailVideoId: null,
            thumbnailUrl: null,
            createdAt: 0,
            updatedAt: 0
        };
    }

    function cleanTitle(title) {
        return typeof title === 'string' ? title.trim() : '';
    }

    function addCategory(session, input) {
        var title = cleanTitle((input || {}).title);
        if (!title) return refuse('a shelf needs a name');

        var id = (input && input.id) || freshId(session, CATEGORY);
        if (nodeById(session, id)) return refuse('that identifier is already used');

        var node = baseNode(id, null, CATEGORY, title, roots(session).length);
        return accept(withNodes(session, session.nodes.concat([node])));
    }

    function addSubcategory(session, input) {
        var title = cleanTitle((input || {}).title);
        if (!title) return refuse('a subcategory needs a name');

        var parentId = (input || {}).parentId || null;
        var parent = parentId ? nodeById(session, parentId) : null;
        if (!parent) return refuse('choose a shelf to put the subcategory in');
        if (childTypesOf(parent.nodeType).indexOf(SUBCATEGORY) === -1) {
            return refuse('a ' + parent.nodeType + ' cannot hold a subcategory');
        }

        var id = (input && input.id) || freshId(session, SUBCATEGORY);
        if (nodeById(session, id)) return refuse('that identifier is already used');

        var node = baseNode(id, parent.id, SUBCATEGORY, title, childrenOf(session, parent.id).length);
        return accept(withNodes(session, session.nodes.concat([node])));
    }

    function addVideo(session, input) {
        var title = cleanTitle((input || {}).title);
        if (!title) return refuse('a video needs a name');

        var videoId = videoIdFrom((input || {}).youtubeVideoId);
        if (!videoId) return refuse('a video needs a YouTube video id or URL');

        var parentId = (input || {}).parentId || null;
        var parent = parentId ? nodeById(session, parentId) : null;
        if (!parent) return refuse('choose a shelf or subcategory to put the video in');
        if (childTypesOf(parent.nodeType).indexOf(VIDEO) === -1) {
            return refuse('a ' + parent.nodeType + ' cannot hold a video');
        }

        var id = (input && input.id) || freshId(session, VIDEO);
        if (nodeById(session, id)) return refuse('that identifier is already used');

        var node = baseNode(id, parent.id, VIDEO, title, childrenOf(session, parent.id).length);
        node.youtubeVideoId = videoId;
        return accept(withNodes(session, session.nodes.concat([node])));
    }

    function rename(session, input) {
        var id = (input || {}).id;
        var title = cleanTitle((input || {}).title);
        var node = id ? nodeById(session, id) : null;
        if (!node) return refuse('that node no longer exists');
        if (!title) return refuse('a name cannot be blank');
        if (node.nodeType === VIDEO) {
            return refuse('a video keeps the name it was added with; rename the shelf or subcategory instead');
        }

        var nodes = session.nodes.map(function (candidate) {
            return candidate.id === id ? copyWith(candidate, { title: title }) : candidate;
        });
        return accept(withNodes(session, nodes));
    }

    function setEnabled(session, input) {
        var id = (input || {}).id;
        var node = id ? nodeById(session, id) : null;
        if (!node) return refuse('that node no longer exists');

        var enabled = !!(input || {}).enabled;
        var nodes = session.nodes.map(function (candidate) {
            // Only the node itself: disabling a container must never delete or disable its children.
            return candidate.id === id ? copyWith(candidate, { enabled: enabled }) : candidate;
        });
        return accept(withNodes(session, nodes));
    }

    function remove(session, input) {
        var id = (input || {}).id;
        var node = id ? nodeById(session, id) : null;
        if (!node) return refuse('that node no longer exists');

        // A shelf or a container goes with everything inside it; a video is a leaf.
        var removed = subtreeIds(session, id);
        var nodes = session.nodes.filter(function (candidate) {
            return removed.indexOf(candidate.id) === -1;
        }).map(function (candidate) {
            // A picture chosen from the deleted subtree went with it. Clearing the choice is part of
            // removing what the parent deleted: an id that names nothing would leave the catalog
            // unpublishable until the parent found out why.
            if (candidate.thumbnailVideoId && removed.indexOf(candidate.thumbnailVideoId) !== -1) {
                return copyWith(candidate, { thumbnailMode: THUMBNAIL_AUTO, thumbnailVideoId: null });
            }
            return candidate;
        });
        return accept(withNodes(session, nodes));
    }

    function moveWithin(session, id, offset) {
        var node = nodeById(session, id);
        if (!node) return refuse('that node no longer exists');

        var siblings = childrenOf(session, node.parentId);
        var index = siblings.findIndex(function (candidate) { return candidate.id === id; });
        var target = index + offset;
        if (target < 0) return refuse('it is already first');
        if (target >= siblings.length) return refuse('it is already last');

        var reordered = siblings.slice();
        reordered.splice(index, 1);
        reordered.splice(target, 0, node);

        // Only the positions of that sibling group change: identities, content and subtrees do not.
        var positions = {};
        reordered.forEach(function (candidate, position) { positions[candidate.id] = position; });

        var nodes = session.nodes.map(function (candidate) {
            return positions[candidate.id] === undefined ? candidate : copyWith(candidate, { position: positions[candidate.id] });
        });
        return accept(withNodes(session, nodes));
    }

    function moveUp(session, input) {
        return moveWithin(session, (input || {}).id, -1);
    }

    function moveDown(session, input) {
        return moveWithin(session, (input || {}).id, 1);
    }

    /**
     * Attaches a node to another parent, at a given place among its new siblings.
     *
     * The old sibling group is closed up and the new one opens for it, in the same mutation, so the
     * tree is never observable with a gap: the whole list is renumbered before it is returned. The
     * node keeps its identifier, its content and its whole subtree - only `parentId` and its
     * position change - which is what makes a move different from a delete plus an insert.
     */
    function moveTo(session, input) {
        var id = (input || {}).id;
        var node = id ? nodeById(session, id) : null;
        if (!node) return refuse('that node no longer exists');

        var parentId = (input || {}).parentId === undefined ? null : (input || {}).parentId;
        var parent = parentId ? nodeById(session, parentId) : null;

        if (parentId && !parent) return refuse('that destination no longer exists');
        if (parentId === id) return refuse('a node cannot be placed inside itself');
        if (parentId && subtreeIds(session, id).indexOf(parentId) !== -1) {
            return refuse('a node cannot be moved inside its own subtree');
        }
        if (childTypesOf(parent ? parent.nodeType : null).indexOf(node.nodeType) === -1) {
            var where = parent ? 'a ' + parent.nodeType : 'ROOT';
            return refuse(where + ' cannot hold a ' + node.nodeType.toLowerCase());
        }

        var others = session.nodes.filter(function (candidate) {
            return candidate.id !== id && (candidate.parentId || null) === parentId;
        }).sort(byRenderOrder);

        var requested = (input || {}).position;
        var index = typeof requested === 'number' ? Math.max(0, Math.min(requested, others.length)) : others.length;
        others.splice(index, 0, copyWith(node, { parentId: parentId }));

        var positions = {};
        others.forEach(function (candidate, position) { positions[candidate.id] = position; });

        var nodes = session.nodes.map(function (candidate) {
            if (candidate.id === id) return copyWith(candidate, { parentId: parentId, position: positions[id] });
            return positions[candidate.id] === undefined ? candidate : copyWith(candidate, { position: positions[candidate.id] });
        });

        return accept(withNodes(session, nodes));
    }

    function copyWith(node, changes) {
        var copy = clone(node);
        Object.keys(changes).forEach(function (key) { copy[key] = changes[key]; });
        return copy;
    }

    // --- importing a YouTube playlist ----------------------------------------------------------
    //
    // A playlist is a way videos *arrive*, never a tile of its own. Nothing here creates a node for
    // the playlist: what is created is one ordinary VIDEO node per distinct video the playlist listed,
    // under the parent the parent chose - so the child-facing catalog has videos, and there is no
    // PLAYLIST node type to create in the first place.
    //
    // The identity of a video is its youtubeVideoId and nothing else: not its title, not its
    // thumbnail, not its position, not where it was found. A video that is already under the target
    // parent is the same node it was - same id, same position, same enabled state, same metadata - and
    // an import never reorders, never resurrects and never duplicates.

    /**
     * Applies a resolved playlist to a working copy.
     *
     * Returns `{ ok, session, summary }`, where the summary counts what happened: `added` (new
     * videos, appended in playlist order), `kept` (videos already under the target parent),
     * `sourcesRecorded` (existing videos that had no source and now name this playlist), `hidden`
     * (videos this playlist used to bring in and no longer lists - disabled, never deleted),
     * `keptOtherSource` (videos another playlist already accounts for, whose source is left alone),
     * and `skipped` (items the playlist listed that cannot become a video node).
     */
    function importPlaylist(session, input) {
        var settings = input || {};
        var parentId = settings.parentId || null;
        var playlistId = typeof settings.playlistId === 'string' ? settings.playlistId.trim() : '';
        if (!playlistId) return refuse('the playlist has no id');

        var parent = parentId ? nodeById(session, parentId) : null;
        if (!parent) return refuse('choose a shelf or subcategory to import into');
        if (childTypesOf(parent.nodeType).indexOf(VIDEO) === -1) {
            return refuse('a ' + parent.nodeType.toLowerCase() + ' cannot hold videos');
        }

        // Playlist order, with the same video listed twice counting once.
        var incoming = [];
        var incomingIds = {};
        var skipped = 0;
        (settings.videos || []).forEach(function (video) {
            var videoId = video && typeof video.videoId === 'string' ? video.videoId.trim() : '';
            if (!videoId) { skipped++; return; }
            if (incomingIds[videoId]) return;
            incomingIds[videoId] = true;
            incoming.push({
                videoId: videoId,
                title: (video.title && String(video.title).trim()) || videoId,
            });
        });

        var children = childrenOf(session, parentId);
        var existingByVideoId = {};
        children.forEach(function (node) {
            if (node.nodeType === VIDEO && node.youtubeVideoId) existingByVideoId[node.youtubeVideoId] = node;
        });

        var nodes = session.nodes.slice();
        var added = [];
        var kept = [];
        var sourcesRecorded = [];
        var keptOtherSource = [];
        var position = children.length;

        incoming.forEach(function (video) {
            var existing = existingByVideoId[video.videoId];

            if (existing) {
                kept.push(existing.id);
                if (!existing.youtubePlaylistId) {
                    // Provenance was missing, so this import supplies it. Nothing else is touched.
                    nodes = nodes.map(function (node) {
                        return node.id === existing.id ? copyWith(node, { youtubePlaylistId: playlistId }) : node;
                    });
                    sourcesRecorded.push(existing.id);
                } else if (existing.youtubePlaylistId !== playlistId) {
                    // The model records one source per node. An existing source is never overwritten:
                    // losing it would lose the only record of where that video came from.
                    keptOtherSource.push(existing.id);
                }
                return;
            }

            var created = baseNode(freshIdIn(nodes, session.newId, VIDEO), parentId, VIDEO, video.title, position++);
            created.youtubeVideoId = video.videoId;
            created.youtubePlaylistId = playlistId;
            nodes.push(created);
            added.push(created.id);
        });

        // Source removal is a *hide*, not a delete: a video this playlist no longer lists stops being
        // shown and keeps its node, its position, its history and its resume point, so it can come back.
        // Only nodes that name this playlist as their source are considered - a video the parent
        // curated by hand, or that another playlist accounts for, is not this playlist's to hide.
        var hidden = [];
        nodes = nodes.map(function (node) {
            if (node.parentId !== parentId) return node;
            if (node.nodeType !== VIDEO) return node;
            if (node.youtubePlaylistId !== playlistId) return node;
            if (node.youtubeVideoId && incomingIds[node.youtubeVideoId]) return node;
            if (node.enabled === false) return node;
            hidden.push(node.id);
            return copyWith(node, { enabled: false });
        });

        // A container under this parent that imports the same playlist: the parent is about to have
        // the same videos twice, and is told so rather than left to find out on the TV.
        var siblingContainer = children.find(function (node) {
            return node.nodeType === SUBCATEGORY && node.youtubePlaylistId === playlistId;
        });

        return {
            ok: true,
            session: normalize(withNodes(session, nodes)),
            summary: {
                added: added,
                kept: kept,
                sourcesRecorded: sourcesRecorded,
                keptOtherSource: keptOtherSource,
                hidden: hidden,
                skipped: skipped,
                total: incoming.length,
                existingContainerId: siblingContainer ? siblingContainer.id : null
            }
        };
    }

    /**
     * Resolves a playlist through the server and applies it, as one durable operation.
     *
     * `resolve(url)` and `put(body)` are the caller's transports (the dashboard's `apiCall`), so the
     * order that matters is testable here rather than in a page: **resolve everything first**, then
     * compute the new catalog, then publish it as a single document at the version the working copy was
     * read from. A playlist that cannot be resolved therefore changes nothing at all - not the catalog,
     * not its version - and a catalog that cannot be published reports a conflict or an error instead
     * of a half-applied import.
     */
    function importPlaylistFrom(session, input, resolve, put) {
        var settings = input || {};
        var url = typeof settings.url === 'string' ? settings.url.trim() : '';
        if (!url) {
            return Promise.resolve({ outcome: 'error', session: session, reason: 'Paste a YouTube playlist URL or id' });
        }

        return Promise.resolve(resolve(url)).then(function (response) {
            var status = response && response.status;
            var data = (response && response.data) || {};

            if (status !== 200 || !Array.isArray(data.videos) || !data.sourceId) {
                return {
                    outcome: 'error',
                    session: session,
                    reason: data.error || ('the playlist could not be resolved (status ' + status + ')')
                };
            }

            var applied = importPlaylist(session, {
                parentId: settings.parentId,
                playlistId: data.sourceId,
                videos: data.videos
            });
            if (!applied.ok) {
                return { outcome: 'error', session: session, reason: applied.reason };
            }

            var summary = applied.summary;
            summary.playlistTitle = data.title || data.sourceId;
            summary.truncated = data.truncated === true;
            summary.unusableItems = data.unusableItems || 0;
            summary.approved = data.approved === true;

            // The playlist changed nothing about the catalog - every video was already here - so there
            // is nothing to publish and the version must not move for nothing.
            if (canonicalJson(applied.session.nodes) === canonicalJson(session.nodes)) {
                return { outcome: 'no-changes', session: applied.session, summary: summary };
            }

            return save(applied.session, put).then(function (saved) {
                if (saved.outcome === 'saved') {
                    return { outcome: 'imported', session: saved.session, summary: summary };
                }
                if (saved.outcome === 'conflict') {
                    return {
                        outcome: 'conflict',
                        session: saved.session,
                        summary: summary,
                        serverVersion: saved.serverVersion
                    };
                }
                if (saved.outcome === 'rejected') {
                    return {
                        outcome: 'rejected',
                        session: saved.session,
                        summary: summary,
                        reasons: saved.reasons
                    };
                }
                return { outcome: 'failed', session: saved.session, summary: summary, reason: saved.reason };
            });
        }, function (error) {
            return { outcome: 'error', session: session, reason: (error && error.message) || 'the request failed' };
        });
    }

    // --- the picture a container shows ---------------------------------------------------------
    //
    // A shelf or a subcategory is a group, not a video, so something has to stand for it in the grid.
    // The parent either lets the app choose (`AUTO`) or names one of the videos *inside* it (`VIDEO`).
    // There is no third option here: `CUSTOM` is a reserved value in the stored model and no screen
    // can render one, so the editor neither offers it nor writes it - and it never stores a picture
    // URL, because the only artwork the TV has is the artwork its own approved sources provided.
    //
    // What is stored is the **node id** of the chosen video, not its YouTube id: a node id names
    // exactly one node, while the same video may legitimately sit under a shelf twice (curated onto
    // two shelves, or imported twice), and "which of those two did the parent pick" has to have one
    // answer. Everything below is expressed in node ids for that reason.
    //
    // `AUTO` is the same rule the TV applies, computed the same way - the first video inside, walking
    // the tree in `position ASC, id ASC`, skipping anything hidden *and everything inside it* - so the
    // editor can say truthfully which video a parent is about to get without asking the TV.

    var THUMBNAIL_AUTO = 'AUTO';
    var THUMBNAIL_VIDEO = 'VIDEO';
    var THUMBNAIL_MODES = [THUMBNAIL_AUTO, THUMBNAIL_VIDEO];

    /**
     * A node's thumbnail mode, as one of the two the editor can render.
     *
     * A node stored before pictures existed carries no key at all, and the server reads that as `AUTO`
     * - so this does too. An unknown or blank mode is reported as `null` rather than guessed at, which
     * is what lets `problems` name it.
     */
    function thumbnailModeOf(node) {
        var raw = node ? node.thumbnailMode : null;
        if (raw === undefined || raw === null) return THUMBNAIL_AUTO;
        return THUMBNAIL_MODES.indexOf(raw) === -1 ? null : raw;
    }

    /** Whether a video node can supply a picture at all: it has to name a video. */
    function thumbnailSourceOf(node) {
        var videoId = node && typeof node.youtubeVideoId === 'string' ? node.youtubeVideoId.trim() : '';
        return videoId || null;
    }

    /**
     * Every video inside a container, in the order `AUTO` considers them.
     *
     * Pre-order depth-first: a parent's children in `position ASC, id ASC`, each video taken where it
     * stands and each nested container descended into before the next sibling. That is the order the
     * resolver walks, so the first entry with `usable` true is exactly what `AUTO` would pick.
     *
     * Each entry says whether the child could actually reach that video: `reachable` is false when the
     * video is hidden, or when anything between the container and it is - a hidden node takes its whole
     * subtree with it. A video that is not reachable is still listed, because the parent is the one who
     * should decide what to do about it, but it is never usable as a picture.
     */
    function descendantVideos(session, containerId) {
        var container = nodeById(session, containerId);
        if (!container || container.nodeType === VIDEO) return [];

        var found = [];
        var visited = {};

        function walk(parentId, ancestorsShown, trail) {
            childrenOf(session, parentId).forEach(function (child) {
                // A hand-edited cycle must not loop forever, and a node is visited once.
                if (visited[child.id]) return;
                visited[child.id] = true;

                var shown = ancestorsShown && child.enabled !== false;
                var path = trail.concat([child.title]);

                if (child.nodeType === VIDEO) {
                    var source = thumbnailSourceOf(child);
                    found.push({
                        id: child.id,
                        title: child.title,
                        youtubeVideoId: source,
                        enabled: child.enabled !== false,
                        reachable: shown,
                        usable: shown && !!source,
                        path: path.join(' / ')
                    });
                    return;
                }
                walk(child.id, shown, path);
            });
        }

        walk(containerId, true, []);
        return found;
    }

    /** The video `AUTO` would use, or null when the container has nothing usable inside it. */
    function autoVideo(session, containerId) {
        var videos = descendantVideos(session, containerId);
        for (var i = 0; i < videos.length; i++) {
            if (videos[i].usable) return videos[i];
        }
        return null;
    }

    /** Whether [nodeId] sits anywhere below [containerId], by walking up its parents. */
    function isDescendantOf(session, nodeId, containerId) {
        var current = (nodeById(session, nodeId) || {}).parentId;
        var guard = 0;
        while (current && guard++ < 64) {
            if (current === containerId) return true;
            current = (nodeById(session, current) || {}).parentId;
        }
        return false;
    }

    /**
     * Chooses how a container is represented: `{ id, mode, videoNodeId }`.
     *
     * `AUTO` clears the selection outright - "let the app choose" and "use that specific video" are
     * two different answers and the node carries one. `VIDEO` requires a video that can actually take
     * the part: it exists, it is a `VIDEO`, it is *inside this container*, it names a YouTube id, and
     * it is not hidden. Anything less is refused with a reason rather than stored, because a stored
     * choice that silently renders as something else is worse than a refusal the parent can read.
     *
     * A video node is refused entirely: its own picture is itself, and a second answer would be a
     * contradiction the server refuses too.
     */
    function setThumbnail(session, input) {
        var settings = input || {};
        var node = settings.id ? nodeById(session, settings.id) : null;
        if (!node) return refuse('that node no longer exists');
        if (node.nodeType === VIDEO) {
            return refuse('a video is its own picture; choose one for a shelf or subcategory instead');
        }
        if (node.nodeType !== CATEGORY && node.nodeType !== SUBCATEGORY) {
            return refuse('a ' + node.nodeType + ' cannot choose a picture');
        }

        if (THUMBNAIL_MODES.indexOf(settings.mode) === -1) {
            return refuse('choose automatic, or one video inside this ' + node.nodeType.toLowerCase());
        }

        if (settings.mode === THUMBNAIL_AUTO) {
            var automatic = session.nodes.map(function (candidate) {
                return candidate.id === node.id
                    ? copyWith(candidate, { thumbnailMode: THUMBNAIL_AUTO, thumbnailVideoId: null })
                    : candidate;
            });
            return accept(withNodes(session, automatic));
        }

        var chosenId = typeof settings.videoNodeId === 'string' ? settings.videoNodeId.trim() : '';
        if (!chosenId) return refuse('choose a video to take the picture from');

        var chosen = nodeById(session, chosenId);
        if (!chosen) return refuse('that video no longer exists');
        if (chosen.nodeType !== VIDEO) return refuse('only a video can stand for a ' + node.nodeType.toLowerCase());
        if (chosen.id === node.id || !isDescendantOf(session, chosen.id, node.id)) {
            return refuse('that video is not inside this ' + node.nodeType.toLowerCase());
        }
        if (!thumbnailSourceOf(chosen)) return refuse('that video has no YouTube id to take a picture from');

        var listed = descendantVideos(session, node.id).filter(function (video) {
            return video.id === chosen.id;
        })[0];
        if (!listed || !listed.usable) {
            return refuse('that video is hidden from your child, and a hidden video cannot stand for this ' +
                node.nodeType.toLowerCase());
        }

        var nodes = session.nodes.map(function (candidate) {
            return candidate.id === node.id
                ? copyWith(candidate, { thumbnailMode: THUMBNAIL_VIDEO, thumbnailVideoId: chosen.id })
                : candidate;
        });
        return accept(withNodes(session, nodes));
    }

    // --- video identifiers --------------------------------------------------------------------

    /**
     * The YouTube video id inside whatever the parent pasted: a bare id, a watch URL, a short link,
     * a Shorts or embed URL.
     *
     * This is a convenience for the form, never a validator: the server checks the identifier with
     * the same parser the rest of the project uses, and refuses one it does not accept. Nothing here
     * invents a title or a thumbnail - the parent names the video, and any YouTube metadata stays
     * where it is.
     */
    function videoIdFrom(input) {
        if (typeof input !== 'string') return null;
        var text = input.trim();
        if (!text) return null;

        var patterns = [
            /[?&]v=([A-Za-z0-9_-]{1,64})/,
            /youtu\.be\/([A-Za-z0-9_-]{1,64})/,
            /\/shorts\/([A-Za-z0-9_-]{1,64})/,
            /\/embed\/([A-Za-z0-9_-]{1,64})/,
            /\/live\/([A-Za-z0-9_-]{1,64})/
        ];
        for (var i = 0; i < patterns.length; i++) {
            var match = text.match(patterns[i]);
            if (match) return match[1];
        }

        // A bare identifier, which is what the form asks for.
        return /^[A-Za-z0-9_-]{1,64}$/.test(text) ? text : null;
    }

    // --- talking to the server -----------------------------------------------------------------

    /**
     * Sends the working copy as one atomic document, at the version it was read from.
     *
     * `put` is the caller's transport: it receives the request body and resolves to
     * `{ status, data }`, exactly what the dashboard's `apiCall` returns. Nothing here reports
     * success - the caller only shows "Saved" when this resolves with a 200, which is what makes the
     * promise "the server committed it" rather than "the request was sent".
     *
     * The outcome shapes the view has to distinguish:
     *
     *  - `saved`    the server committed version N+1; the session adopts the server's own document
     *               (its version, its stamped timestamps) so what is on screen is what was stored;
     *  - `conflict` somebody else wrote first. The session - and therefore every unsaved edit - is
     *               left exactly as it was, the server's current version is reported so the view can
     *               say what happened, and nothing is retried or overwritten;
     *  - `rejected` the server refused the document; its reasons are passed through;
     *  - `failed`   the request itself failed, so the edits are still unsaved and still here.
     */
    function save(session, put) {
        var request = {
            schemaVersion: SCHEMA_VERSION,
            expectedCatalogVersion: session.catalogVersion,
            nodes: clone(session.nodes)
        };

        return Promise.resolve(put(request)).then(function (response) {
            var status = response && response.status;
            var data = (response && response.data) || {};

            if (status === 200 && data && Array.isArray(data.nodes)) {
                return {
                    outcome: 'saved',
                    session: {
                        catalogVersion: data.catalogVersion,
                        nodes: normalizeNodes(clone(data.nodes)),
                        savedNodes: normalizeNodes(clone(data.nodes)),
                        newId: session.newId
                    }
                };
            }

            if (status === 409) {
                return {
                    outcome: 'conflict',
                    session: session,
                    serverVersion: typeof data.catalogVersion === 'number' ? data.catalogVersion : null,
                    reason: 'The catalog changed on the server.'
                };
            }

            if (status === 400) {
                return {
                    outcome: 'rejected',
                    session: session,
                    reasons: Array.isArray(data.details) ? data.details : [data.error || 'the server refused the catalog']
                };
            }

            if (status === 401) {
                return { outcome: 'failed', session: session, reason: 'the dashboard session expired' };
            }

            return {
                outcome: 'failed',
                session: session,
                reason: data.error || ('the server did not answer (status ' + status + ')')
            };
        }, function (error) {
            return { outcome: 'failed', session: session, reason: (error && error.message) || 'the request failed' };
        });
    }

    /**
     * Replaces the working copy with the server's current document.
     *
     * This is the only way out of a conflict, and it is deliberately explicit: it discards whatever
     * was unsaved, because those edits were made against a catalog that no longer exists.
     */
    function reload(get, options) {
        return Promise.resolve(get()).then(function (response) {
            var status = response && response.status;
            var data = (response && response.data) || {};
            if (status === 200 && data && Array.isArray(data.nodes)) {
                return { outcome: 'reloaded', session: open(data, options) };
            }
            return {
                outcome: 'failed',
                reason: data.error || ('the server did not answer (status ' + status + ')')
            };
        }, function (error) {
            return { outcome: 'failed', reason: (error && error.message) || 'the request failed' };
        });
    }

    return {
        CATEGORY: CATEGORY,
        SUBCATEGORY: SUBCATEGORY,
        VIDEO: VIDEO,
        SCHEMA_VERSION: SCHEMA_VERSION,
        THUMBNAIL_AUTO: THUMBNAIL_AUTO,
        THUMBNAIL_VIDEO: THUMBNAIL_VIDEO,
        THUMBNAIL_MODES: THUMBNAIL_MODES,
        childTypesOf: childTypesOf,
        open: open,
        normalize: normalize,
        normalizeNodes: normalizeNodes,
        toDocument: toDocument,
        isDirty: isDirty,
        problems: problems,
        nodeById: nodeById,
        childrenOf: childrenOf,
        roots: roots,
        subtreeIds: subtreeIds,
        validParentsFor: validParentsFor,
        depthOf: depthOf,
        addCategory: addCategory,
        addSubcategory: addSubcategory,
        addVideo: addVideo,
        rename: rename,
        setEnabled: setEnabled,
        remove: remove,
        moveUp: moveUp,
        moveDown: moveDown,
        moveTo: moveTo,
        importPlaylist: importPlaylist,
        importPlaylistFrom: importPlaylistFrom,
        thumbnailModeOf: thumbnailModeOf,
        descendantVideos: descendantVideos,
        autoVideo: autoVideo,
        setThumbnail: setThumbnail,
        videoIdFrom: videoIdFrom,
        save: save,
        reload: reload
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = CatalogEditor;
}
