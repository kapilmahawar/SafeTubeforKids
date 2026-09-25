/*
 * The read-only catalog tree, as the dashboard renders it.
 *
 * This file is intentionally free of every ambient dependency: no DOM, no fetch, no localStorage, no
 * timers. Everything here is a pure function of the catalog document the server sent, which is what
 * makes the tree's ordering, nesting and disabled state testable without a browser - and what makes it
 * impossible for a view to become a second place the catalog is stored.
 *
 * The document is version 2: a flat list of nodes, each naming its `parentId`, exactly as the TV's
 * `catalog_nodes` table stores it. `position` orders siblings; `id` only breaks a tie two positions
 * should never share, which is the same rule the database's reads use.
 */
var CatalogTree = (function () {
    'use strict';

    var TYPE_LABELS = {
        CATEGORY: 'Category',
        SUBCATEGORY: 'Subcategory',
        VIDEO: 'Video'
    };

    function escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /** `position ASC, id ASC`, the tree's only ordering rule. */
    function byRenderOrder(a, b) {
        var pa = typeof a.position === 'number' ? a.position : 0;
        var pb = typeof b.position === 'number' ? b.position : 0;
        if (pa !== pb) return pa - pb;
        return String(a.id) < String(b.id) ? -1 : (String(a.id) > String(b.id) ? 1 : 0);
    }

    /**
     * Groups a flat document into parents and their children, in render order, and reports the nodes
     * that cannot be reached from ROOT.
     *
     * A stored document is only ever written after validation, so `unplaced` is normally empty. It is
     * still computed, because a hand-edited file must be *shown* as what it is rather than hidden
     * behind a renderer that would drop rows or recurse forever.
     */
    function order(nodes) {
        var list = (nodes || []).filter(function (n) { return n && n.id; });
        var childrenOf = {};

        var roots = [];
        list.forEach(function (node) {
            var parent = node.parentId;
            if (parent === null || parent === undefined || parent === '') {
                roots.push(node);
            } else {
                if (!childrenOf[parent]) childrenOf[parent] = [];
                childrenOf[parent].push(node);
            }
        });

        roots.sort(byRenderOrder);
        Object.keys(childrenOf).forEach(function (parent) { childrenOf[parent].sort(byRenderOrder); });

        // Reachability from ROOT, which is also what stops a cycle from recursing forever: a node in
        // a loop is never placed, and is reported instead of followed.
        var placed = {};
        var pending = roots.slice();
        while (pending.length) {
            var node = pending.shift();
            if (placed[node.id]) continue;
            placed[node.id] = true;
            (childrenOf[node.id] || []).forEach(function (child) { pending.push(child); });
        }

        var unplaced = list.filter(function (node) { return !placed[node.id]; });

        return { roots: roots, childrenOf: childrenOf, unplaced: unplaced, total: list.length };
    }

    function summary(nodes) {
        var result = { categories: 0, subcategories: 0, videos: 0, disabled: 0, unplaced: 0, total: 0 };
        var grouped = order(nodes);
        (nodes || []).forEach(function (node) {
            if (!node || !node.id) return;
            result.total++;
            if (node.nodeType === 'CATEGORY') result.categories++;
            else if (node.nodeType === 'SUBCATEGORY') result.subcategories++;
            else if (node.nodeType === 'VIDEO') result.videos++;
            if (node.enabled === false) result.disabled++;
        });
        result.unplaced = grouped.unplaced.length;
        return result;
    }

    function metadataChips(node) {
        var chips = [];

        if (node.youtubeVideoId) {
            chips.push('<span class="catalog-chip" title="YouTube video id">video ' + escapeHtml(node.youtubeVideoId) + '</span>');
        }
        if (node.youtubePlaylistId) {
            var label = node.nodeType === 'SUBCATEGORY' ? 'imports playlist ' : 'from playlist ';
            chips.push('<span class="catalog-chip" title="YouTube playlist id">' + label + escapeHtml(node.youtubePlaylistId) + '</span>');
        }
        if (node.thumbnailMode && node.thumbnailMode !== 'AUTO') {
            chips.push('<span class="catalog-chip">thumbnail ' + escapeHtml(node.thumbnailMode) + '</span>');
        }
        if (node.thumbnailVideoId) {
            chips.push('<span class="catalog-chip">thumbnail from ' + escapeHtml(node.thumbnailVideoId) + '</span>');
        }

        return chips.join('');
    }

    function renderRow(node, options) {
        var type = String(node.nodeType || 'UNKNOWN');
        var enabled = node.enabled !== false;
        var position = typeof node.position === 'number' ? node.position : 0;
        var selected = !!(options && options.selectedId && options.selectedId === node.id);

        return '<div class="catalog-node-row' + (selected ? ' is-selected' : '') +
            '" data-node-id="' + escapeHtml(node.id) + '">' +
            '<span class="catalog-node-title">' + escapeHtml(node.title) + '</span>' +
            '<span class="catalog-node-type catalog-type-' + escapeHtml(type.toLowerCase()) + '">' +
                escapeHtml(TYPE_LABELS[type] || type) + '</span>' +
            '<span class="catalog-node-position" title="Position among its siblings">#' + position + '</span>' +
            '<span class="catalog-node-enabled ' + (enabled ? 'is-enabled' : 'is-disabled') + '">' +
                (enabled ? 'enabled' : 'disabled') + '</span>' +
            '<span class="catalog-node-id">' + escapeHtml(node.id) + '</span>' +
            metadataChips(node) +
            '</div>';
    }

    function renderBranch(node, childrenOf, rendered, options) {
        if (rendered[node.id]) return '';
        rendered[node.id] = true;

        var children = childrenOf[node.id] || [];
        var html = '<li class="catalog-node catalog-node-' + escapeHtml(String(node.nodeType || 'unknown').toLowerCase()) +
            (node.enabled === false ? ' catalog-node-disabled' : '') + '">' + renderRow(node, options);

        if (children.length) {
            html += '<ul class="catalog-children">';
            children.forEach(function (child) { html += renderBranch(child, childrenOf, rendered, options); });
            html += '</ul>';
        }

        return html + '</li>';
    }

    /**
     * The whole tree as HTML.
     *
     * Every row carries its node id in a `data-node-id` attribute, so the dashboard can tell which row
     * a click landed on, and `options.selectedId` marks one row as selected. Nothing here is clickable,
     * editable or draggable in itself: the controls live in the editor panel beside the tree, and this
     * function stays a rendering of the tree.
     */
    function render(nodes, options) {
        var grouped = order(nodes);

        if (grouped.total === 0) {
            return '<p class="catalog-empty">No shelves configured yet. Add one below.</p>';
        }

        var rendered = {};
        var html = '<ul class="catalog-tree">';
        grouped.roots.forEach(function (root) { html += renderBranch(root, grouped.childrenOf, rendered, options); });
        html += '</ul>';

        if (grouped.unplaced.length) {
            html += '<div class="catalog-unplaced"><h3>Not reachable from ROOT</h3>' +
                '<p class="catalog-hint">These nodes name a parent that is missing, or sit in a cycle of ' +
                'parents, so no shelf can show them.</p><ul class="catalog-tree">';
            grouped.unplaced.forEach(function (node) {
                html += '<li class="catalog-node catalog-node-unplaced">' + renderRow(node, options) + '</li>';
            });
            html += '</ul></div>';
        }

        return html;
    }

    return {
        escapeHtml: escapeHtml,
        order: order,
        summary: summary,
        render: render
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = CatalogTree;
}
