/*
 * The parent's catalog editor: the working copy and every mutation a parent can make to it.
 *
 * This file is the *model*, and like `catalog-tree.js` it is deliberately free of the DOM, of fetch
 * and of browser storage: every function here takes a session and returns a new one, so every
 * mutation is testable without a browser and the view can only ever be a view.
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
        for (var attempt = 0; attempt < 10; attempt++) {
            var candidate = session.newId(type);
            if (candidate && !nodeById(session, candidate)) return candidate;
        }
        // Last resort: a counter, so a pathological generator still cannot produce a duplicate.
        var n = 1;
        while (nodeById(session, String(type).toLowerCase() + '-' + n)) n++;
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
        videoIdFrom: videoIdFrom,
        save: save,
        reload: reload
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = CatalogEditor;
}
