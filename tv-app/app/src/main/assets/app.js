/*
 * SafeTube's parent dashboard.
 *
 * This file is the only thing that talks to the server. It is written for one reader: a parent
 * holding a phone, standing next to the TV, who wants to change what their child can watch. Every
 * screen answers one of four questions - what is in the library, what is inside this shelf or
 * folder, how do I add something, and where is everything else - and the words are the parent's
 * words, not the project's: a "shelf" is a row on the TV, a "folder" is a card that opens a list, a
 * "video" is a video. Nothing on screen says catalog, node, position, schema or playlist id.
 *
 * Three rules shape the code below.
 *
 * 1. **The library is the server's document, never a local copy.** It is read from `GET /catalog`
 *    and written back with `PUT /catalog` carrying the version it was read from. Nothing about it is
 *    stored in the browser: the only three things in `localStorage` are the session token, the theme
 *    the parent chose, and whether the "add to home screen" hint was dismissed.
 * 2. **Every change is published immediately.** There is no Save button and no half-saved state: a
 *    rename, a reorder, an add and a delete each become one atomic document write, and a write the
 *    server refuses leaves the library exactly as it was.
 * 3. **Being in the library is not permission to watch.** A video the parent curates still cannot
 *    play until its source is allowed, and the screens say so in those words rather than leaving the
 *    parent to discover it on the TV.
 *
 * The pure model - every mutation, and the save/reload conversation - lives in `catalog-editor.js`
 * and is deliberately free of DOM, network and storage. This file is the view: it renders the model,
 * asks it to change, and publishes the result. Every control goes through one delegated click
 * listener and one action table, so the page carries no inline handlers at all.
 */
(function () {
    'use strict';

    // --- where this page is running ----------------------------------------------------------
    //
    // The same bundle is served by the TV itself (`http://<tv>:8080/`) and by the relay
    // (`https://relay.parentapproved.tv/tv/<id>/`), and the two need different API prefixes.

    function extractTvId() {
        var parts = window.location.pathname.split('/');
        if (parts.length >= 3 && parts[1] === 'tv') return parts[2];
        return null;
    }

    function extractApiBase() {
        var id = extractTvId();
        return id ? '/tv/' + id + '/api' : '';
    }

    function extractPin() {
        return new URLSearchParams(window.location.search).get('pin');
    }

    var tvId = extractTvId();
    var API_BASE = extractApiBase();
    var TOKEN_KEY = tvId ? 'kw_token_' + tvId : 'kw_token';
    var HOMESCREEN_KEY = tvId ? 'kw_homescreen_dismissed_' + tvId : 'kw_homescreen_dismissed';
    var THEME_KEY = 'safetube.theme';
    var PROTOCOL_VERSION = 1;

    // --- the shell ---------------------------------------------------------------------------
    //
    // Only elements that exist in index.html are looked up here. Every other element is created by
    // the screen that needs it, which is what keeps the page and the script honestly in step.

    var view = document.getElementById('view');
    var topbar = document.getElementById('topbar');
    var tabbar = document.getElementById('tabbar');
    var tvState = document.getElementById('tv-state');
    var themeToggle = document.getElementById('theme-toggle');
    var themeIcon = document.getElementById('theme-icon');
    var themeLabel = document.getElementById('theme-label');
    var tabLibrary = document.getElementById('tab-library');
    var tabSettings = document.getElementById('tab-settings');
    var toasts = document.getElementById('toasts');

    // --- state -------------------------------------------------------------------------------
    //
    // Everything the screens render from. `session` is not a second copy of the library: it is the
    // working copy the editor handed back, replaced by the server's document after every write.

    var state = {
        token: readToken(),
        session: null,
        artwork: { videos: {}, containers: {}, installedCatalogVersion: 0 },
        playlists: [],
        limits: null,
        stats: null,
        recent: [],
        crash: null,
        status: null,
        reachable: null,
        versionMismatch: false,
        publishing: false,
        route: { name: 'library', id: null }
    };

    // The inputs of whichever screen is on screen, so the handlers can read them without looking
    // anything up by id.
    var fields = {};

    var deferredInstallPrompt = null;
    var statusTimer = null;
    var toastTimer = null;
    var saving = false;

    // --- tiny DOM helpers --------------------------------------------------------------------

    /**
     * Builds an element: `h('div', { class: 'row' }, [child, 'text'])`.
     *
     * Text goes in as text and attributes as attributes, never as HTML, so a name the parent typed
     * can never become markup.
     */
    function h(tag, attrs, children) {
        var node = document.createElement(tag);
        var settings = attrs || {};

        Object.keys(settings).forEach(function (key) {
            var value = settings[key];
            if (value === null || value === undefined || value === false) return;
            if (key === 'text') node.textContent = String(value);
            else if (key === 'class') node.className = String(value);
            else if (key === 'style') node.setAttribute('style', String(value));
            else node.setAttribute(key, value === true ? '' : String(value));
        });

        (children || []).forEach(function (child) {
            if (child === null || child === undefined || child === false) return;
            node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
        });

        return node;
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
        return node;
    }

    function actionButton(label, action, attrs, extraClass) {
        var settings = { type: 'button', 'data-action': action, class: ('btn ' + (extraClass || '')).trim() };
        Object.keys(attrs || {}).forEach(function (key) { settings[key] = attrs[key]; });
        return h('button', settings, [label]);
    }

    function withListener(element, type, handler) {
        element.addEventListener(type, handler);
        return element;
    }

    // --- talking to the server ---------------------------------------------------------------

    function authHeaders() {
        var headers = { 'Content-Type': 'application/json' };
        if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
        return headers;
    }

    function readToken() {
        try {
            return window.localStorage.getItem(TOKEN_KEY);
        } catch (error) {
            return null;
        }
    }

    function rememberToken(token) {
        state.token = token;
        try {
            window.localStorage.setItem(TOKEN_KEY, token);
        } catch (error) {
            // A browser that refuses storage still works for this visit.
        }
    }

    /**
     * One request, in the shape the rest of the file expects: `{ status, data }`.
     *
     * A 401 means the TV forgot this browser (it was restarted, or the PIN changed), so the parent
     * is sent back to the connect screen rather than left with a page that silently does nothing. A
     * 503 is the TV's own "not ready" answer, and a thrown fetch is the phone having lost the TV -
     * both are reported as a connection problem, which is something the parent can act on.
     */
    async function apiCall(method, path, body) {
        var options = { method: method, headers: authHeaders() };
        if (body !== undefined && body !== null) options.body = JSON.stringify(body);

        try {
            var response = await fetch(API_BASE + path, options);
            if (response.status === 503) {
                return { status: 503, data: { error: 'The TV is busy or offline' } };
            }
            if (response.status === 401) {
                forgetSession();
                return { status: 401, data: { error: 'This browser is no longer connected' } };
            }

            var text = await response.text();
            var data = {};
            try {
                data = text ? JSON.parse(text) : {};
            } catch (error) {
                data = { error: 'The TV sent something this page could not read' };
            }
            return { status: response.status, data: data };
        } catch (error) {
            return { status: 0, data: { error: 'Could not reach the TV. Is it on the same wifi?' } };
        }
    }

    async function connect(pin) {
        try {
            var response = await fetch(API_BASE + '/auth', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pin: pin })
            });

            var data = {};
            try {
                data = await response.json();
            } catch (error) {
                data = {};
            }

            if (response.ok && data.token) {
                rememberToken(data.token);
                // The PIN is a secret: it does not stay in the address bar or in history.
                if (window.history && window.history.replaceState) {
                    window.history.replaceState({}, document.title,
                        window.location.pathname + window.location.hash);
                }
                return { ok: true };
            }

            if (response.status === 429) return { ok: false, reason: 'Too many tries. Wait a minute and try again.' };
            if (response.status === 503) return { ok: false, reason: 'The TV is busy or offline' };
            return { ok: false, reason: data.error || 'That PIN was not right' };
        } catch (error) {
            return { ok: false, reason: 'Could not reach the TV. Is it on the same wifi?' };
        }
    }

    async function refreshSession() {
        if (!state.token) return false;
        var result = await apiCall('POST', '/auth/refresh');
        if (result.status === 200 && result.data && result.data.token) {
            rememberToken(result.data.token);
            return true;
        }
        // Being offline is not a reason to throw the parent out; an explicit 401 already forgot the
        // token inside `apiCall`.
        return result.status === 0 || result.status === 503;
    }

    function forgetSession() {
        state.token = null;
        state.session = null;
        state.status = null;
        state.reachable = null;
        try {
            window.localStorage.removeItem(TOKEN_KEY);
        } catch (error) {
            // Nothing to clean up.
        }
        stopPolling();
        render();
    }

    // --- the theme ---------------------------------------------------------------------------

    function currentTheme() {
        return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    }

    function applyTheme(theme, remember) {
        document.documentElement.setAttribute('data-theme', theme);
        document.documentElement.setAttribute('data-theme-source', remember ? 'chosen' : 'system');
        if (remember) {
            try {
                window.localStorage.setItem(THEME_KEY, theme);
            } catch (error) {
                // The choice simply will not outlive this visit.
            }
        }
        var meta = document.querySelector('meta[name="theme-color"]');
        if (meta) meta.setAttribute('content', theme === 'dark' ? '#0f1216' : '#ffffff');
        paintThemeControl();
    }

    function toggleTheme() {
        applyTheme(currentTheme() === 'dark' ? 'light' : 'dark', true);
    }

    function paintThemeControl() {
        var dark = currentTheme() === 'dark';
        themeIcon.textContent = dark ? '☀️' : '🌙';
        themeLabel.textContent = dark ? 'Light' : 'Dark';
        themeToggle.setAttribute('aria-pressed', dark ? 'true' : 'false');
        themeToggle.setAttribute('aria-label', dark ? 'Switch to the light look' : 'Switch to the dark look');
    }

    // --- dialogs -----------------------------------------------------------------------------
    //
    // The browser's own <dialog>, so focus trapping, Escape and the backdrop come for free. Each
    // dialog is built when it opens and removed when it closes, which keeps a screen's DOM from
    // quietly going stale.

    /**
     * Opens a modal, calls `build(inner, close)`, and resolves with whatever `close` was given.
     * Closing happens exactly once, whichever way the dialog went away.
     */
    function openModal(build) {
        var dialog = h('dialog', { class: 'dialog' });
        var inner = h('div', { class: 'dialog__inner' });
        dialog.appendChild(inner);
        document.body.appendChild(dialog);

        var settled = false;
        var resolveClosed = null;
        var closed = new Promise(function (resolve) { resolveClosed = resolve; });

        function close(value) {
            if (settled) return;
            settled = true;
            if (dialog.open) dialog.close();
            if (dialog.parentNode) dialog.parentNode.removeChild(dialog);
            resolveClosed(value);
        }

        dialog.addEventListener('cancel', function (event) {
            event.preventDefault();
            close(null);
        });

        build(inner, close);
        dialog.showModal();

        return { inner: inner, closed: closed, close: close };
    }

    /** Yes/no, with the destructive answer marked as such. Resolves true or false. */
    function confirmDialog(options) {
        var modal = openModal(function (inner, close) {
            inner.appendChild(h('h2', { class: 'dialog__title', text: options.title }));
            if (options.body) inner.appendChild(h('p', { class: 'dialog__body', text: options.body }));

            var go = withListener(h('button', {
                type: 'button',
                class: 'btn ' + (options.danger ? 'btn--danger' : 'btn--primary'),
                text: options.confirmLabel || 'Yes'
            }), 'click', function () { close(true); });

            var cancel = withListener(h('button', {
                type: 'button',
                class: 'btn',
                text: options.cancelLabel || 'Cancel'
            }), 'click', function () { close(false); });

            inner.appendChild(h('div', { class: 'dialog__actions' }, [go, cancel]));
            setTimeout(function () { cancel.focus(); }, 30);
        });

        return modal.closed.then(function (value) { return value === true; });
    }

    /** One line of text. Resolves with the trimmed value, or null when dismissed. */
    function askForText(options) {
        var modal = openModal(function (inner, close) {
            inner.appendChild(h('h2', { class: 'dialog__title', text: options.title }));
            if (options.body) inner.appendChild(h('p', { class: 'dialog__body', text: options.body }));

            var input = h('input', {
                class: 'input',
                type: 'text',
                value: options.value || '',
                maxlength: '200',
                'aria-label': options.label || 'Name'
            });
            var error = h('p', { class: 'field__error', hidden: true });

            inner.appendChild(h('label', { class: 'field' }, [
                h('span', { class: 'field__label', text: options.label || 'Name' }),
                input
            ]));
            inner.appendChild(error);

            var confirm = withListener(h('button', {
                type: 'button',
                class: 'btn btn--primary',
                text: options.confirmLabel || 'Save'
            }), 'click', function () {
                var value = input.value.trim();
                if (!value) {
                    error.textContent = options.requiredMessage || 'Please type a name';
                    error.hidden = false;
                    input.focus();
                    return;
                }
                close(value);
            });

            var cancel = withListener(h('button', { type: 'button', class: 'btn', text: 'Cancel' }),
                'click', function () { close(null); });

            input.addEventListener('keydown', function (event) {
                if (event.key === 'Enter') confirm.click();
            });

            inner.appendChild(h('div', { class: 'dialog__actions' }, [confirm, cancel]));
            setTimeout(function () { input.focus(); input.select(); }, 30);
        });

        return modal.closed;
    }

    /**
     * A list of choices - the item menu, "move to", and choosing a picture all use it.
     * Resolves with the chosen option's id, or null when dismissed.
     */
    function chooseFrom(options) {
        var modal = openModal(function (inner, close) {
            inner.appendChild(h('h2', { class: 'dialog__title', text: options.title }));
            if (options.body) inner.appendChild(h('p', { class: 'dialog__body', text: options.body }));
            if (options.image) {
                inner.appendChild(h('img', { class: 'dialog__image', src: options.image, alt: '', loading: 'lazy' }));
            }

            var list = h('div', { class: 'pick-list' });
            options.options.forEach(function (option) {
                var item = h('button', {
                    type: 'button',
                    class: 'pick-item' + (option.selected ? ' is-selected' : '') +
                        (option.danger ? ' pick-item--danger' : ''),
                    disabled: option.disabled || false
                }, [
                    h('span', { class: 'pick-item__body' }, [
                        h('span', { class: 'pick-item__title', text: option.title }),
                        option.meta ? h('span', { class: 'pick-item__meta', text: option.meta }) : null
                    ]),
                    option.selected ? h('span', { class: 'pick-item__tick', text: '✓', 'aria-hidden': 'true' }) : null
                ]);

                if (option.disabled) item.setAttribute('aria-disabled', 'true');
                item.addEventListener('click', function () {
                    if (option.disabled) return;
                    close(option.id);
                });
                list.appendChild(item);
            });
            inner.appendChild(list);

            var cancel = withListener(h('button', {
                type: 'button',
                class: 'btn',
                text: options.cancelLabel || 'Close'
            }), 'click', function () { close(null); });
            inner.appendChild(h('div', { class: 'dialog__actions' }, [cancel]));
        });

        return modal.closed;
    }

    /** A short-lived message. Errors stay longer, because they ask the parent to do something. */
    function toast(message, kind) {
        if (!message) return;
        if (toastTimer) clearTimeout(toastTimer);
        clear(toasts);
        toasts.appendChild(h('p', { class: 'toast' + (kind ? ' toast--' + kind : ''), role: 'status', text: message }));
        toastTimer = setTimeout(function () { clear(toasts); }, kind === 'error' ? 6500 : 4200);
    }

    // --- reading the library -----------------------------------------------------------------

    /** Reads the parent's document and opens it as a working copy. */
    async function loadCatalog() {
        var result = await CatalogEditor.reload(function () {
            return apiCall('GET', '/catalog');
        });

        if (result.outcome !== 'reloaded') {
            return { ok: false, reason: result.reason || 'The library could not be read' };
        }

        state.session = result.session;
        return { ok: true };
    }

    /**
     * The pictures and counts only the TV knows: what each card shows, and how many videos are
     * really inside each folder (an imported playlist's episodes live on the TV, not in the
     * document). Best effort: a library without pictures still works.
     */
    async function loadArtwork() {
        var result = await apiCall('GET', '/catalog/artwork');
        if (result.status === 200 && result.data) {
            state.artwork = {
                videos: result.data.videos || {},
                containers: result.data.containers || {},
                installedCatalogVersion: result.data.installedCatalogVersion || 0
            };
        } else {
            state.artwork = { videos: {}, containers: {}, installedCatalogVersion: 0 };
        }
    }

    async function loadPlaylists() {
        var result = await apiCall('GET', '/playlists');
        state.playlists = result.status === 200 && Array.isArray(result.data) ? result.data : [];
    }

    async function loadStatus() {
        var result = await apiCall('GET', '/status');
        if (result.status === 200) {
            state.status = result.data;
            state.reachable = true;
            if (result.data.protocolVersion && result.data.protocolVersion !== PROTOCOL_VERSION) {
                state.versionMismatch = true;
            }
        } else {
            state.status = null;
            state.reachable = false;
        }
        paintStatusPill();
    }

    async function loadLimits() {
        var result = await apiCall('GET', '/time-limits');
        state.limits = result.status === 200 ? result.data : null;
    }

    async function loadStats() {
        var stats = await apiCall('GET', '/stats');
        var recent = await apiCall('GET', '/stats/recent');
        state.stats = stats.status === 200 ? stats.data : null;
        state.recent = recent.status === 200 && Array.isArray(recent.data) ? recent.data : [];
    }

    async function loadCrash() {
        var result = await apiCall('GET', '/crash-log');
        state.crash = result.status === 200
            ? (result.data.log || result.data.crashLog || JSON.stringify(result.data))
            : '';
    }

    /** Everything the library screen needs, in the order that lets it paint once. */
    async function loadLibrary() {
        var loaded = await loadCatalog();
        if (!loaded.ok) return loaded;
        await Promise.all([loadArtwork(), loadPlaylists()]);
        return loaded;
    }

    // --- writing the library -----------------------------------------------------------------

    function explainProblems(problems, title) {
        openModal(function (inner, close) {
            inner.appendChild(h('h2', { class: 'dialog__title', text: title || 'This cannot be saved yet' }));
            inner.appendChild(h('p', {
                class: 'dialog__body',
                text: problems.length === 1
                    ? 'Nothing was changed on the TV. This is what is wrong:'
                    : 'Nothing was changed on the TV. This is what is wrong:'
            }));

            var list = h('ul', { class: 'dialog__list' });
            problems.forEach(function (problem) {
                list.appendChild(h('li', { class: 'dialog__list-item', text: problem }));
            });
            inner.appendChild(list);

            var ok = withListener(h('button', { type: 'button', class: 'btn btn--primary', text: 'OK' }),
                'click', function () { close(null); });
            inner.appendChild(h('div', { class: 'dialog__actions' }, [ok]));
        });
    }

    /**
     * Asks the TV to fetch the library it has just been sent.
     *
     * This is the same work the TV's own Refresh button does. It exists because the TV would
     * otherwise wait up to fifteen minutes, which is a long time to stand next to a television
     * wondering whether the change worked.
     */
    async function refreshTv() {
        var result = await apiCall('POST', '/catalog/refresh');
        return {
            ok: result.status === 200,
            message: (result.data && result.data.message) || ''
        };
    }

    function paintSaving(active) {
        saving = active;
        if (saving) {
            tvState.hidden = false;
            tvState.className = 'pill';
            tvState.textContent = 'Saving…';
        } else {
            paintStatusPill();
        }
    }

    /** Publishes the working copy as one document, at the version it was read from. */
    async function publish(reason) {
        if (saving || !state.session) return false;

        var problems = CatalogEditor.problems(state.session);
        if (problems.length) {
            explainProblems(problems);
            return false;
        }

        paintSaving(true);

        var outcome = await CatalogEditor.save(state.session, function (body) {
            return apiCall('PUT', '/catalog', body);
        });

        paintSaving(false);

        if (outcome.outcome === 'saved') {
            state.session = outcome.session;
            await afterPublish(reason);
            return true;
        }

        if (outcome.outcome === 'conflict') {
            state.session = outcome.session;
            openConflictDialog(outcome, reason);
            return false;
        }

        if (outcome.outcome === 'rejected') {
            explainProblems(outcome.reasons, 'The TV would not accept this');
            return false;
        }

        toast(outcome.reason || 'The change could not be saved', 'error');
        return false;
    }

    /**
     * After a write the server accepted: tell the TV, then say truthfully what happened.
     *
     * "Saved" on its own is not the whole truth from the parent's point of view - the TV is what
     * their child actually watches - so the message distinguishes "your TV has it now" from "your TV
     * will pick it up on its own", rather than leaving the difference to be discovered later.
     */
    async function afterPublish(reason) {
        var refreshed = await refreshTv();
        var said = reason ? reason + ' — saved.' : 'Saved.';

        toast(said + (refreshed.ok ? ' Your TV has it now.' : ' Your TV will pick it up within 15 minutes.'),
            'ok');

        await loadArtwork();
        render();
    }

    function openConflictDialog(outcome, reason) {
        var theirs = outcome.serverVersion;

        openModal(function (inner, close) {
            inner.appendChild(h('h2', { class: 'dialog__title', text: 'The library changed somewhere else' }));
            inner.appendChild(h('p', {
                class: 'dialog__body',
                text: 'Another phone or browser saved a change first' +
                    (typeof theirs === 'number' ? ' (the TV is now on version ' + theirs + ')' : '') +
                    ', so this change was not saved. Nothing on the TV changed.'
            }));

            var reload = withListener(h('button', {
                type: 'button',
                class: 'btn btn--primary',
                text: 'Load the latest and start again'
            }), 'click', async function () {
                close(null);
                var loaded = await loadLibrary();
                if (!loaded.ok) toast(loaded.reason, 'error');
                toast('Loaded the latest library. Your change was not applied.', 'ok');
                render();
            });

            var retry = withListener(h('button', {
                type: 'button',
                class: 'btn',
                text: 'Keep my change and save again'
            }), 'click', async function () {
                close(null);
                await publish(reason);
            });

            var stop = withListener(h('button', { type: 'button', class: 'btn btn--quiet', text: 'Leave it for now' }),
                'click', function () { close(null); });

            inner.appendChild(h('div', { class: 'dialog__actions' }, [reload, retry, stop]));
        });
    }

    // --- the parent's words ------------------------------------------------------------------

    var TYPE_WORDS = { CATEGORY: 'Shelf', SUBCATEGORY: 'Folder', VIDEO: 'Video' };

    function isContainer(node) {
        return node.nodeType === CatalogEditor.SUBCATEGORY;
    }

    function nodeById(id) {
        return state.session ? CatalogEditor.nodeById(state.session, id) : null;
    }

    function childrenOf(parentId) {
        return state.session ? CatalogEditor.childrenOf(state.session, parentId) : [];
    }

    function descendantsOf(nodeId) {
        var found = [];
        (function walk(parentId) {
            childrenOf(parentId).forEach(function (child) {
                found.push(child);
                if (child.nodeType !== CatalogEditor.VIDEO) walk(child.id);
            });
        })(nodeId);
        return found;
    }

    function pathOf(nodeId) {
        var parts = [];
        var node = nodeById(nodeId);
        var guard = 0;
        while (node && guard++ < 32) {
            parts.unshift(node.title);
            node = node.parentId ? nodeById(node.parentId) : null;
        }
        return parts.join(' / ');
    }

    function words(count, singular, plural) {
        return count + ' ' + (count === 1 ? singular : (plural || singular + 's'));
    }

    /** How many videos, folders and hidden things a shelf or folder holds. */
    function countsFor(nodeId) {
        var inside = descendantsOf(nodeId);
        var videos = inside.filter(function (child) { return child.nodeType === CatalogEditor.VIDEO; });
        return {
            folders: inside.filter(function (child) { return child.nodeType === CatalogEditor.SUBCATEGORY; }).length,
            videos: videos.length,
            hidden: inside.filter(function (child) { return child.enabled === false; }).length,
            videosList: videos
        };
    }

    /** The TV's own count, which knows about episodes the document has never seen. */
    function containerVideoCount(node) {
        var fromDocument = countsFor(node.id).videos;
        var fromTv = state.artwork.containers[node.id];
        if (fromTv && typeof fromTv.videoCount === 'number' && fromTv.videoCount > fromDocument) {
            return fromTv.videoCount;
        }
        return fromDocument;
    }

    function artworkFor(node) {
        if (node.nodeType === CatalogEditor.VIDEO) {
            return state.artwork.videos[node.youtubeVideoId] || '';
        }
        if (isContainer(node)) {
            var art = state.artwork.containers[node.id];
            if (art && art.thumbnailUrl) return art.thumbnailUrl;
            // A folder whose videos are not on the TV yet borrows its first child's picture rather
            // than showing nothing.
            var children = childrenOf(node.id);
            for (var i = 0; i < children.length; i++) {
                var picture = artworkFor(children[i]);
                if (picture) return picture;
            }
        }
        return '';
    }

    /**
     * Whether a video can actually play, mirroring the rule the TV enforces.
     *
     * The app approves a video only while its *source* is allowed, so a video is playable when its
     * playlist is allowed, or when it was allowed on its own. Nothing about being in the library
     * makes a video playable - which is exactly why this is computed from the allowed sources rather
     * than from the library.
     */
    function allowedSourceMaps() {
        var playlists = {};
        var videos = {};
        state.playlists.forEach(function (source) {
            if (source.sourceType === 'yt_playlist') playlists[source.sourceId] = true;
            if (source.sourceType === 'yt_video') videos[source.sourceId] = true;
        });
        return { playlists: playlists, videos: videos };
    }

    function canPlay(node, maps) {
        if (node.nodeType !== CatalogEditor.VIDEO) return true;
        if (!node.youtubeVideoId) return false;
        var allowed = maps || allowedSourceMaps();
        if (node.youtubePlaylistId && allowed.playlists[node.youtubePlaylistId]) return true;
        return !!allowed.videos[node.youtubeVideoId];
    }

    function sourceNameFor(node) {
        if (!node.youtubePlaylistId) return 'Added by you';
        var found = null;
        state.playlists.forEach(function (source) {
            if (source.sourceType === 'yt_playlist' && source.sourceId === node.youtubePlaylistId) {
                found = source.displayName || source.sourceId;
            }
        });
        return found ? 'From ' + found : 'From a playlist you have not allowed yet';
    }

    function nodeMeta(node, maps) {
        if (node.nodeType === CatalogEditor.VIDEO) {
            var parts = [sourceNameFor(node)];
            if (node.enabled === false) parts.push('hidden');
            if (!canPlay(node, maps)) parts.push('cannot play yet');
            return parts.join(' · ');
        }

        var videos = isContainer(node) ? containerVideoCount(node) : countsFor(node.id).videos;
        var folders = isContainer(node) ? 0 : countsFor(node.id).folders;
        var hidden = countsFor(node.id).hidden;

        var pieces = [];
        if (folders) pieces.push(words(folders, 'folder'));
        pieces.push(words(videos, 'video'));
        if (hidden) pieces.push(words(hidden, 'hidden item', 'hidden items'));
        return pieces.join(' · ');
    }

    // --- the router --------------------------------------------------------------------------

    function parseHash() {
        var raw = (window.location.hash || '').replace(/^#\/?/, '');
        var parts = raw.split('/').filter(function (part) { return part.length > 0; });
        var name = parts[0] || 'library';

        if (name === 'shelf' || name === 'folder') {
            return { name: name, id: parts[1] ? decodeURIComponent(parts[1]) : null };
        }
        if (name === 'add') {
            return { name: 'add', id: parts[1] ? decodeURIComponent(parts[1]) : null };
        }
        if (name === 'settings') return { name: 'settings', id: null };
        return { name: 'library', id: null };
    }

    function go(hash) {
        if (window.location.hash === hash) {
            render();
            return;
        }
        window.location.hash = hash;
    }

    /** Where a node lives: a folder opens its own screen, everything else opens its shelf. */
    function routeFor(node) {
        if (!node) return '#/library';
        if (node.nodeType === CatalogEditor.SUBCATEGORY) return '#/folder/' + encodeURIComponent(node.id);
        if (node.nodeType === CatalogEditor.VIDEO) {
            var parent = node.parentId ? nodeById(node.parentId) : null;
            return routeFor(parent);
        }
        return '#/shelf/' + encodeURIComponent(node.id);
    }

    function render() {
        if (!state.token) {
            topbar.hidden = true;
            tabbar.hidden = true;
            clear(view).appendChild(screenConnect());
            return;
        }

        topbar.hidden = false;
        tabbar.hidden = false;
        state.route = parseHash();
        paintTabs();

        var screen;
        if (state.route.name === 'settings') {
            screen = screenSettings();
        } else if (!state.session) {
            screen = screenNotLoaded();
        } else if (state.route.name === 'add') {
            screen = screenAdd(state.route.id);
        } else if (state.route.name === 'shelf') {
            screen = screenShelf(state.route.id);
        } else if (state.route.name === 'folder') {
            screen = screenFolder(state.route.id);
        } else {
            screen = screenLibrary();
        }

        clear(view).appendChild(screen);
        paintStatusPill();
    }

    function paintTabs() {
        var onSettings = state.route.name === 'settings';
        tabLibrary.classList.toggle('is-active', !onSettings);
        tabSettings.classList.toggle('is-active', onSettings);
        tabLibrary.setAttribute('aria-current', onSettings ? 'false' : 'page');
        tabSettings.setAttribute('aria-current', onSettings ? 'page' : 'false');
    }

    function paintStatusPill() {
        if (saving) return;
        if (state.reachable === null) {
            tvState.hidden = true;
            return;
        }

        tvState.hidden = false;
        tvState.classList.remove('pill--ok', 'pill--warn', 'pill--off');

        if (!state.reachable) {
            tvState.classList.add('pill--off');
            tvState.textContent = 'TV offline';
            tvState.title = 'The TV is not answering this page';
            return;
        }

        var playing = state.status && state.status.currentlyPlaying;
        if (playing && playing.title) {
            tvState.classList.add('pill--ok');
            tvState.textContent = '▶ ' + playing.title;
            tvState.title = 'Playing on the TV right now';
            return;
        }

        tvState.classList.add('pill--warn');
        tvState.textContent = 'TV on';
        tvState.title = 'Nothing is playing on the TV';
    }

    function screenNotLoaded() {
        return h('div', { class: 'screen' }, [
            screenHead('Connecting to your TV', null, 'Reading your library…', []),
            h('p', { class: 'inline-status' }, [h('span', { class: 'spinner' }), 'One moment…']),
            h('div', { class: 'btn-group' }, [
                actionButton('Try again', 'reload-catalog', {}, 'btn--primary'),
                actionButton('Disconnect this phone', 'disconnect', {})
            ])
        ]);
    }

    // --- the connect screen ------------------------------------------------------------------

    function screenConnect() {
        var pinInput = h('input', {
            class: 'input',
            type: 'text',
            inputmode: 'numeric',
            autocomplete: 'one-time-code',
            maxlength: '8',
            'aria-label': 'PIN shown on the TV'
        });
        var error = h('p', { class: 'field__error', hidden: true });
        var submit = h('button', { type: 'button', class: 'btn btn--primary btn--block', text: 'Connect' });

        async function attempt() {
            var pin = pinInput.value.trim();
            if (!pin) return;
            error.hidden = true;
            submit.disabled = true;
            submit.textContent = 'Connecting…';

            var result = await connect(pin);

            submit.disabled = false;
            submit.textContent = 'Connect';

            if (!result.ok) {
                error.textContent = result.reason;
                error.hidden = false;
                pinInput.select();
                return;
            }

            var loaded = await loadLibrary();
            if (!loaded.ok) toast(loaded.reason, 'error');
            startPolling();
            loadStats();
            loadLimits();
            loadCrash();
            render();
        }

        submit.addEventListener('click', attempt);
        pinInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') attempt();
        });

        var children = [
            h('h1', { class: 'screen__title', text: 'SafeTube' }),
            h('p', {
                class: 'screen__sub',
                text: 'Type the PIN your TV is showing. This page is how you choose what your child can watch.'
            }),
            h('div', { class: 'panel' }, [
                h('label', { class: 'field' }, [
                    h('span', { class: 'field__label', text: 'PIN from the TV' }),
                    pinInput
                ]),
                error,
                submit
            ])
        ];

        if (state.versionMismatch) {
            children.splice(1, 0, banner('warn', 'This page is out of date',
                'Reload the page to get the version that matches your TV.', []));
        }

        setTimeout(function () { pinInput.focus(); }, 40);

        return h('div', { class: 'screen' }, children);
    }

    // --- shared pieces of a screen -----------------------------------------------------------

    function screenHead(title, crumbs, subtitle, actions) {
        return h('div', { class: 'screen__head' }, [
            crumbs && crumbs.length ? h('div', { class: 'crumbs' }, crumbs) : null,
            h('h1', { class: 'screen__title', text: title }),
            subtitle ? h('p', { class: 'screen__sub', text: subtitle }) : null,
            actions && actions.length ? h('div', { class: 'screen__actions' }, actions) : null
        ]);
    }

    function crumb(label, route) {
        if (!route) return h('span', { class: 'crumb crumb--here', text: label });
        return withListener(h('button', { type: 'button', class: 'crumb', text: label }),
            'click', function () { go(route); });
    }

    function artworkNode(node, className) {
        var url = artworkFor(node);
        if (!url) {
            return h('div', {
                class: className + ' ' + className + '--placeholder',
                'aria-hidden': 'true',
                text: node.nodeType === CatalogEditor.VIDEO ? '▶' : '🗂'
            });
        }
        return h('img', { class: className, src: url, alt: '', loading: 'lazy' });
    }

    /** One shelf, as a line: a shelf is a row on the TV, and it has no picture of its own. */
    function shelfRow(node) {
        var main = withListener(h('button', { type: 'button', class: 'row__main' }, [
            h('span', { class: 'row__body' }, [
                h('span', { class: 'row__title', text: node.title }),
                h('span', { class: 'row__meta', text: nodeMeta(node) })
            ]),
            h('span', { class: 'row__chevron', 'aria-hidden': 'true', text: '›' })
        ]), 'click', function () { go('#/shelf/' + encodeURIComponent(node.id)); });
        main.setAttribute('aria-label', 'Open ' + node.title);

        return h('li', { class: 'row' + (node.enabled === false ? ' row--hidden' : '') }, [
            main,
            menuButton(node)
        ]);
    }

    function menuButton(node) {
        return withListener(h('button', {
            type: 'button',
            class: 'btn btn--icon',
            'aria-label': 'More options for ' + node.title,
            text: '⋯'
        }), 'click', function () { openItemSheet(node.id); });
    }

    /** One folder or video, as a line with its picture: parents recognise these by sight. */
    function itemRow(node, maps) {
        var badges = [];
        if (node.enabled === false) badges.push(h('span', { class: 'badge badge--hidden', text: 'Hidden' }));
        if (node.nodeType === CatalogEditor.VIDEO && !canPlay(node, maps)) {
            badges.push(h('span', { class: 'badge badge--blocked', text: 'Cannot play yet' }));
        }

        var main = withListener(h('button', { type: 'button', class: 'row__main' }, [
            artworkNode(node, 'row__art'),
            h('span', { class: 'row__body' }, [
                h('span', { class: 'row__title', text: node.title }),
                h('span', { class: 'row__meta', text: nodeMeta(node, maps) }),
                badges.length ? h('span', { class: 'badges' }, badges) : null
            ])
        ]), 'click', function () {
            if (isContainer(node)) go('#/folder/' + encodeURIComponent(node.id));
            else openItemSheet(node.id);
        });
        main.setAttribute('aria-label', 'Open ' + node.title);

        return h('li', { class: 'row' + (node.enabled === false ? ' row--hidden' : '') }, [
            main,
            menuButton(node)
        ]);
    }

    function emptyState(icon, title, note, actions) {
        return h('div', { class: 'empty' }, [
            h('p', { class: 'empty__icon', 'aria-hidden': 'true', text: icon }),
            h('p', { class: 'empty__title', text: title }),
            h('p', { class: 'empty__note', text: note }),
            actions && actions.length ? h('div', { class: 'btn-group' }, actions) : null
        ]);
    }

    function banner(kind, title, text, actions) {
        return h('div', { class: 'banner banner--' + kind }, [
            title ? h('p', { class: 'banner__title', text: title }) : null,
            text ? h('p', { text: text }) : null,
            actions && actions.length ? h('div', { class: 'banner__actions' }, actions) : null
        ]);
    }

    function addActions(parentId) {
        return [
            actionButton('Add from YouTube', 'add-from-youtube', { 'data-parent': parentId || '' }, 'btn--primary'),
            parentId
                ? actionButton('New folder', 'new-folder', { 'data-parent': parentId })
                : actionButton('New shelf', 'new-shelf', {})
        ];
    }

    /** Every video below these nodes that cannot play yet, and the sources that would fix it. */
    function unplayableBanner(nodes) {
        var maps = allowedSourceMaps();
        var videos = [];
        nodes.forEach(function (node) {
            videos = videos.concat(descendantsOf(node.id).filter(function (child) {
                return child.nodeType === CatalogEditor.VIDEO;
            }));
        });

        var stuck = videos.filter(function (video) { return !canPlay(video, maps); });
        if (!stuck.length) return null;

        var sources = {};
        stuck.forEach(function (video) {
            if (video.youtubePlaylistId) sources[video.youtubePlaylistId] = 'playlist';
            else if (video.youtubeVideoId) sources[video.youtubeVideoId] = 'video';
        });

        return banner('warn', words(stuck.length, 'video') + ' cannot play yet',
            'Your child can see them, but nothing plays until you allow the playlist they came from.',
            [actionButton('Allow ' + words(Object.keys(sources).length, 'source'),
                'allow-sources', { 'data-sources': JSON.stringify(sources) }, 'btn--primary')]);
    }

    // --- the library -------------------------------------------------------------------------

    function screenLibrary() {
        var shelves = CatalogEditor.roots(state.session);
        var children = [
            screenHead('Library', null, 'This is what your child sees on the TV.', addActions(null))
        ];

        if (state.versionMismatch) {
            children.push(banner('warn', 'This page is out of date',
                'Reload the page to get the version that matches your TV.', []));
        }

        if (homescreenHintWanted()) children.push(homescreenBanner());
        children.push(unplayableBanner(shelves));

        if (!shelves.length) {
            children.push(emptyState('🎬', 'Your library is empty',
                'Start with a shelf — a row of videos on the TV, like “Cartoons” or “Bedtime”.',
                [
                    actionButton('Add from YouTube', 'add-from-youtube', {}, 'btn--primary'),
                    actionButton('New shelf', 'new-shelf', {})
                ]));
        } else {
            children.push(h('ul', { class: 'panel panel--flush' }, shelves.map(shelfRow)));
            children.push(h('div', { class: 'screen__actions' }, [
                actionButton('Send to TV now', 'send-to-tv', {}, 'btn--quiet')
            ]));
        }

        return h('div', { class: 'screen' }, children);
    }

    function homescreenHintWanted() {
        var standalone = (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) ||
            window.navigator.standalone;
        var mobile = 'ontouchstart' in window || window.innerWidth <= 768;
        if (standalone || !mobile) return false;
        try {
            return !window.localStorage.getItem(HOMESCREEN_KEY);
        } catch (error) {
            return false;
        }
    }

    function homescreenBanner() {
        var isIOS = /iPhone|iPad/.test(navigator.userAgent);
        var actions = [actionButton('Not now', 'dismiss-homescreen', {}, 'btn--quiet')];
        if (!isIOS) {
            actions.unshift(actionButton('Add to home screen', 'install-homescreen', {}, 'btn--primary'));
        }

        return banner('info', 'Keep SafeTube handy',
            isIOS
                ? 'Tap Share, then “Add to Home Screen”, so this page is one tap away.'
                : 'Add this page to your home screen, so it is one tap away next time.',
            actions);
    }

    // --- a shelf, and a folder ---------------------------------------------------------------

    function screenShelf(shelfId) {
        var shelf = nodeById(shelfId);

        if (!shelf || shelf.nodeType !== CatalogEditor.CATEGORY) {
            return goneScreen('That shelf is gone');
        }

        var children = [
            screenHead(shelf.title, [crumb('Library', '#/library'), crumb(shelf.title, null)],
                nodeMeta(shelf), addActions(shelf.id).concat([menuButton(shelf)]))
        ];

        if (shelf.enabled === false) {
            children.push(banner('warn', 'This shelf is hidden',
                'Your child cannot see it on the TV until you show it again.',
                [actionButton('Show this shelf', 'show-node', { 'data-id': shelf.id }, 'btn--primary')]));
        }

        children.push(unplayableBanner([shelf]));
        children.push(listOf(childrenOf(shelf.id), shelf));

        return h('div', { class: 'screen' }, children);
    }

    function screenFolder(folderId) {
        var folder = nodeById(folderId);

        if (!folder || folder.nodeType !== CatalogEditor.SUBCATEGORY) {
            return goneScreen('That folder is gone');
        }

        var crumbs = [crumb('Library', '#/library')];
        var parent = folder.parentId ? nodeById(folder.parentId) : null;
        if (parent) crumbs.push(crumb(parent.title, '#/shelf/' + encodeURIComponent(parent.id)));
        crumbs.push(crumb(folder.title, null));

        var children = [
            screenHead(folder.title, crumbs, nodeMeta(folder),
                addActions(folder.id).concat([menuButton(folder)]))
        ];

        if (folder.enabled === false) {
            children.push(banner('warn', 'This folder is hidden',
                'Your child cannot see it on the TV until you show it again.',
                [actionButton('Show this folder', 'show-node', { 'data-id': folder.id }, 'btn--primary')]));
        }

        children.push(unplayableBanner([folder]));
        children.push(listOf(childrenOf(folder.id), folder));

        return h('div', { class: 'screen' }, children);
    }

    function goneScreen(title) {
        return h('div', { class: 'screen' }, [
            screenHead(title, [crumb('Library', '#/library')],
                'It may have been deleted from another phone or browser.', [
                    actionButton('Back to the library', 'go-library', {}, 'btn--primary'),
                    actionButton('Reload from the TV', 'reload-catalog', {})
                ])
        ]);
    }

    function listOf(items, parent) {
        if (!items.length) {
            // A folder can legitimately have nothing in the *document* while the TV shows a great
            // many videos: episodes of a playlist the TV materialises itself. Saying "nothing here
            // yet" over a folder whose own heading says how many videos it holds would be a lie, so
            // that case is explained instead.
            var fromTv = state.artwork.containers[parent.id];
            if (fromTv && fromTv.videoCount > 0) {
                return emptyState('📺', 'These videos come from the TV',
                    'This folder plays ' + words(fromTv.videoCount, 'video') +
                    ' that the TV keeps up to date from the playlist itself, so there is nothing to ' +
                    'list here. Anything you add sits alongside them.',
                    [actionButton('Add from YouTube', 'add-from-youtube',
                        { 'data-parent': parent.id }, 'btn--primary')]);
            }

            return emptyState('📼', 'Nothing here yet',
                'Add videos from YouTube, or make a folder to group them.',
                [actionButton('Add from YouTube', 'add-from-youtube',
                    { 'data-parent': parent.id }, 'btn--primary')]);
        }

        var maps = allowedSourceMaps();
        return h('ul', { class: 'panel panel--flush' }, items.map(function (node) {
            return itemRow(node, maps);
        }));
    }

    // --- adding from YouTube -----------------------------------------------------------------

    function screenAdd(parentId) {
        fields = {};

        var urlInput = h('input', {
            class: 'input',
            type: 'url',
            inputmode: 'url',
            autocapitalize: 'off',
            autocorrect: 'off',
            spellcheck: 'false',
            placeholder: 'https://www.youtube.com/playlist?list=…',
            'aria-label': 'YouTube link'
        });
        var status = h('div', { class: 'inline-status' });
        var error = h('p', { class: 'field__error', hidden: true });
        var preview = h('div', { class: 'screen' });

        fields.addUrl = urlInput;
        fields.addStatus = status;
        fields.addError = error;
        fields.addPreview = preview;

        var check = withListener(h('button', {
            type: 'button',
            class: 'btn btn--primary btn--block',
            text: 'Check this link'
        }), 'click', checkLink);

        urlInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') checkLink();
        });

        var crumbs = [crumb('Library', '#/library')];
        var parent = parentId ? nodeById(parentId) : null;
        if (parent) crumbs.push(crumb(parent.title, routeFor(parent)));
        crumbs.push(crumb('Add from YouTube', null));

        setTimeout(function () { urlInput.focus(); }, 40);

        return h('div', { class: 'screen' }, [
            screenHead('Add from YouTube', crumbs,
                'Paste a link to a playlist or a single video, then choose where it goes.', null),
            h('div', { class: 'panel' }, [
                h('label', { class: 'field' }, [
                    h('span', { class: 'field__label', text: 'YouTube link' }),
                    urlInput,
                    h('span', { class: 'field__hint', text: 'A playlist link, or a link to one video.' })
                ]),
                error,
                check
            ]),
            status,
            preview
        ]);
    }

    async function checkLink() {
        var url = (fields.addUrl && fields.addUrl.value || '').trim();

        fields.addError.hidden = true;
        clear(fields.addPreview);

        if (!url) {
            fields.addError.textContent = 'Paste a YouTube link first.';
            fields.addError.hidden = false;
            return;
        }

        clear(fields.addStatus).appendChild(h('span', { class: 'spinner' }));
        fields.addStatus.appendChild(document.createTextNode('Looking this up on YouTube…'));

        var result = await apiCall('POST', '/catalog/import/resolve', { url: url });
        clear(fields.addStatus);

        if (result.status !== 200 || !result.data || !result.data.kind) {
            fields.addError.textContent = (result.data && result.data.error) || 'That link could not be read.';
            fields.addError.hidden = false;
            return;
        }

        fields.link = { url: url, resolved: result.data };
        fields.addPreview.appendChild(addPreviewCard(result.data, url));
    }

    function addPreviewCard(resolved, url) {
        var isVideo = resolved.kind === 'video';
        var first = (resolved.videos || [])[0] || {};
        var picture = resolved.thumbnailUrl || first.thumbnailUrl || '';

        var art = picture
            ? h('img', { class: 'card__art', src: picture, alt: '', loading: 'lazy' })
            : h('div', { class: 'card__art card__art--placeholder', 'aria-hidden': 'true', text: '▶' });

        var facts = isVideo ? ['One video'] : [words(resolved.videos.length, 'video')];

        var destinationSelect = h('select', { class: 'select', 'aria-label': 'Where this goes' });
        var destinations = addDestinations();
        destinations.forEach(function (option) {
            destinationSelect.appendChild(h('option', { value: option.id, text: option.title }));
        });
        if (state.route.id && destinations.some(function (option) { return option.id === state.route.id; })) {
            destinationSelect.value = state.route.id;
        }
        fields.addParent = destinationSelect;

        var titleInput = h('input', {
            class: 'input',
            type: 'text',
            maxlength: '200',
            value: isVideo ? (first.title || resolved.title || '') : '',
            'aria-label': 'Name on the TV'
        });
        fields.addTitle = titleInput;

        var allowSwitch = h('input', { type: 'checkbox', checked: !resolved.approved });
        fields.addAllow = allowSwitch;

        var confirm = withListener(h('button', {
            type: 'button',
            class: 'btn btn--primary btn--block',
            text: isVideo ? 'Add this video' : 'Add ' + words(resolved.videos.length, 'video')
        }), 'click', confirmAdd);

        var children = [
            h('div', { class: 'panel' }, [
                art,
                h('div', { class: 'card__body' }, [
                    h('p', { class: 'card__title', text: resolved.title || first.title || 'This link' }),
                    h('p', { class: 'card__meta', text: facts.join(' · ') })
                ])
            ])
        ];

        if (resolved.truncated) {
            children.push(banner('warn', 'A long playlist',
                'Only the first ' + resolved.videos.length + ' videos can be added at once. ' +
                'Add it again later for the rest.', []));
        }
        if (resolved.unusableItems) {
            children.push(banner('info', null,
                words(resolved.unusableItems, 'item') + ' in this link cannot be added (deleted or private).', []));
        }

        var form = [];

        if (destinations.length) {
            form.push(h('label', { class: 'field' }, [
                h('span', { class: 'field__label', text: 'Add to' }),
                destinationSelect
            ]));
        } else {
            form.push(banner('warn', 'Make a shelf first',
                'A video has to go inside a shelf or a folder. Make one, then come back to this link.', [
                    actionButton('New shelf', 'new-shelf', {}, 'btn--primary')
                ]));
        }

        if (isVideo) {
            form.push(h('label', { class: 'field' }, [
                h('span', { class: 'field__label', text: 'Name on the TV' }),
                titleInput,
                h('span', { class: 'field__hint', text: 'YouTube’s title, which you can change.' })
            ]));
        }

        if (resolved.approved) {
            allowSwitch.checked = false;
            form.push(banner('info', null,
                'This is already one of your allowed sources, so it will play as soon as the TV gets it.', []));
        } else {
            form.push(h('label', { class: 'switch' }, [
                allowSwitch,
                h('span', { class: 'switch__track', 'aria-hidden': 'true' }),
                h('span', { class: 'switch__text' }, [
                    h('span', { class: 'switch__title', text: 'Let my child watch it' }),
                    h('span', { class: 'field__hint', text: 'Adds this to your allowed sources, so its videos can play.' })
                ])
            ]));
        }

        if (destinations.length) form.push(confirm);

        children.push(h('div', { class: 'panel' }, form));

        // A link that names a playlist *and* a video is read as the playlist by the rest of the app
        // (the same parser approves sources), so the parent is offered the other reading rather than
        // being quietly given fifty videos.
        var ownVideoId = CatalogEditor.videoIdFrom(url);
        if (!isVideo && ownVideoId) {
            children.push(banner('info', 'This link also names one video',
                'You can add just that video instead of the whole playlist.',
                [actionButton('Add only that video', 'add-single-video',
                    { 'data-video-id': ownVideoId }) ]));
        }

        return h('div', { class: 'screen' }, children);
    }

    function addDestinations() {
        return CatalogEditor.validParentsFor(state.session, CatalogEditor.VIDEO)
            .filter(function (id) { return id !== null && !!nodeById(id); })
            .map(function (id) { return { id: id, title: pathOf(id) }; });
    }

    /** The parent pressed "Add": allow the source if asked, then publish one document. */
    async function confirmAdd() {
        if (!fields.link || !fields.addParent) return;

        var resolved = fields.link.resolved;
        var url = fields.link.url;
        var parentId = fields.addParent.value;
        var parent = nodeById(parentId);

        if (!parent) {
            toast('Choose where this should go', 'error');
            return;
        }

        if (fields.addAllow && fields.addAllow.checked) {
            var allowed = await apiCall('POST', '/playlists', { url: url });
            if (allowed.status !== 200 && allowed.status !== 409) {
                toast((allowed.data && allowed.data.error) || 'That source could not be allowed', 'error');
                return;
            }
            await loadPlaylists();
        }

        var applied = resolved.kind === 'video'
            ? CatalogEditor.addVideo(state.session, {
                parentId: parentId,
                title: (fields.addTitle && fields.addTitle.value || '').trim() || resolved.title,
                youtubeVideoId: resolved.sourceId
            })
            : CatalogEditor.importPlaylist(state.session, {
                parentId: parentId,
                playlistId: resolved.sourceId,
                videos: resolved.videos
            });

        if (!applied.ok) {
            toast(applied.reason, 'error');
            return;
        }

        state.session = applied.session;

        if (resolved.kind !== 'video' && !CatalogEditor.isDirty(state.session)) {
            toast('Those videos are already in ' + parent.title + '.', 'ok');
            go(routeFor(parent));
            return;
        }

        var howMany = resolved.kind === 'video'
            ? 1
            : ((applied.summary && applied.summary.added.length) || resolved.videos.length);

        var reason = resolved.kind === 'video'
            ? 'Added one video'
            : 'Added ' + words(howMany, 'video');

        if (applied.summary && applied.summary.hidden && applied.summary.hidden.length) {
            reason += ', and hid ' + words(applied.summary.hidden.length, 'video') + ' that left that playlist';
        }

        var saved = await publish(reason);
        if (saved) go(routeFor(parent));
    }

    // --- the item menu -----------------------------------------------------------------------

    async function openItemSheet(nodeId) {
        var node = nodeById(nodeId);
        if (!node) return;

        var siblings = childrenOf(node.parentId);
        var index = siblings.findIndex(function (candidate) { return candidate.id === node.id; });
        var options = [];

        if (node.nodeType !== CatalogEditor.VIDEO) {
            options.push({ id: 'rename', title: 'Rename', meta: 'Change what it is called on the TV' });
        }

        options.push({
            id: 'toggle',
            title: node.enabled === false ? 'Show it to my child again' : 'Hide it from my child',
            meta: node.enabled === false
                ? 'It will appear on the TV again'
                : 'It stays in your library, but the TV stops showing it'
        });

        options.push({ id: 'up', title: 'Move up', disabled: index <= 0 });
        options.push({ id: 'down', title: 'Move down', disabled: index < 0 || index >= siblings.length - 1 });

        if (node.nodeType !== CatalogEditor.CATEGORY) {
            options.push({
                id: 'move',
                title: 'Move to another shelf…',
                meta: 'Keeps the video and everything inside it'
            });
        }

        if (node.nodeType !== CatalogEditor.VIDEO) {
            var auto = CatalogEditor.autoVideo(state.session, node.id);
            var fromTv = state.artwork.containers[node.id];
            options.push({
                id: 'picture',
                title: 'Choose the picture',
                meta: auto
                    ? 'Now showing “' + auto.title + '”'
                    : (fromTv && fromTv.videoCount > 0
                        ? 'The TV picks one from the playlist itself'
                        : 'Nothing inside to take a picture from')
            });
        }

        options.push({
            id: 'delete',
            title: 'Delete',
            meta: node.nodeType === CatalogEditor.VIDEO
                ? 'Removes it from your library and from the TV'
                : 'Deletes this and everything inside it',
            danger: true
        });

        var choice = await chooseFrom({
            title: node.title,
            body: TYPE_WORDS[node.nodeType] + ' · ' + nodeMeta(node),
            image: artworkFor(node) || null,
            options: options
        });

        if (choice) runItemAction(choice, node);
    }

    async function runItemAction(choice, node) {
        if (choice === 'rename') {
            var title = await askForText({ title: 'Rename', label: 'Name', value: node.title });
            if (title === null) return;
            var renamed = CatalogEditor.rename(state.session, { id: node.id, title: title });
            if (!renamed.ok) return void toast(renamed.reason, 'error');
            state.session = renamed.session;
            return void publish('Renamed');
        }

        if (choice === 'toggle') {
            var shown = node.enabled === false;
            var toggled = CatalogEditor.setEnabled(state.session, { id: node.id, enabled: shown });
            if (!toggled.ok) return void toast(toggled.reason, 'error');
            state.session = toggled.session;
            return void publish(shown ? 'Shown again' : 'Hidden');
        }

        if (choice === 'up' || choice === 'down') {
            var moved = choice === 'up'
                ? CatalogEditor.moveUp(state.session, { id: node.id })
                : CatalogEditor.moveDown(state.session, { id: node.id });
            if (!moved.ok) return void toast(moved.reason, 'error');
            state.session = moved.session;
            return void publish('Reordered');
        }

        if (choice === 'move') return void moveToOtherParent(node);
        if (choice === 'picture') return void choosePicture(node);
        if (choice === 'delete') return void askDelete(node);
    }

    async function moveToOtherParent(node) {
        var forbidden = CatalogEditor.subtreeIds(state.session, node.id);
        var options = CatalogEditor.validParentsFor(state.session, node.nodeType)
            .filter(function (id) { return id !== null && forbidden.indexOf(id) === -1; })
            .map(function (id) {
                return {
                    id: id,
                    title: pathOf(id),
                    selected: id === node.parentId,
                    meta: id === node.parentId ? 'Where it is now' : ''
                };
            });

        if (!options.length) {
            toast('There is nowhere else to put it yet.', 'ok');
            return;
        }

        var destination = await chooseFrom({
            title: 'Move “' + node.title + '”',
            body: 'Choose the shelf or folder it should go in.',
            options: options
        });

        if (!destination) return;

        var moved = CatalogEditor.moveTo(state.session, { id: node.id, parentId: destination });
        if (!moved.ok) return void toast(moved.reason, 'error');
        state.session = moved.session;
        publish('Moved');
    }

    async function choosePicture(node) {
        var videos = CatalogEditor.descendantVideos(state.session, node.id);
        var options = [{
            id: 'AUTO',
            title: 'Automatic',
            meta: 'Show the first video inside — what the TV does today',
            selected: CatalogEditor.thumbnailModeOf(node) === 'AUTO'
        }];

        videos.forEach(function (video) {
            if (!video.usable) return;
            options.push({
                id: video.id,
                title: video.title,
                meta: video.path,
                selected: node.thumbnailVideoId === video.id
            });
        });

        if (options.length === 1) {
            var fromTv = state.artwork.containers[node.id];
            toast(fromTv && fromTv.videoCount > 0
                ? 'The TV picks this folder’s picture from the playlist itself.'
                : 'This has no videos in it yet, so there is nothing to show.', 'ok');
            return;
        }

        var chosen = await chooseFrom({
            title: 'Picture for “' + node.title + '”',
            body: 'What should this show on the TV?',
            options: options
        });

        if (!chosen) return;

        var applied = chosen === 'AUTO'
            ? CatalogEditor.setThumbnail(state.session, { id: node.id, mode: 'AUTO' })
            : CatalogEditor.setThumbnail(state.session, { id: node.id, mode: 'VIDEO', videoNodeId: chosen });

        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;
        publish('Picture changed');
    }

    async function askDelete(node) {
        var inside = descendantsOf(node.id).length;
        var confirmed = await confirmDialog({
            title: 'Delete “' + node.title + '”?',
            body: inside
                ? 'This deletes it and the ' + words(inside, 'thing') + ' inside it, from your library and from the TV. ' +
                  'You can add them again later.'
                : 'This removes it from your library and from the TV. You can add it again later.',
            confirmLabel: 'Delete',
            danger: true
        });

        if (!confirmed) return;

        var parentId = node.parentId;
        var removed = CatalogEditor.remove(state.session, { id: node.id });
        if (!removed.ok) return void toast(removed.reason, 'error');
        state.session = removed.session;

        var saved = await publish('Deleted');
        if (saved && !parentId) go('#/library');
    }

    // --- settings ----------------------------------------------------------------------------

    function panel(title, note, body, actions) {
        return h('section', { class: 'panel' }, [
            h('div', { class: 'panel__head' }, [
                h('h2', { class: 'panel__title', text: title }),
                actions && actions.length ? h('div', { class: 'btn-group' }, actions) : null
            ]),
            note ? h('p', { class: 'panel__note', text: note }) : null,
            body
        ]);
    }

    function screenSettings() {
        var children = [
            screenHead('Settings', null, 'The TV itself, screen time, and what your child is allowed to watch.', [])
        ];

        children.push(tvPanel());
        children.push(screenTimePanel());

        if (state.session) {
            children.push(allowedSourcesPanel());
            children.push(libraryPanel());
        }

        children.push(watchHistoryPanel());
        children.push(lookPanel());
        children.push(helpPanel());

        return h('div', { class: 'screen' }, children);
    }

    function tvPanel() {
        var status = state.status;
        var playing = status && status.currentlyPlaying;

        var body = [
            h('p', {
                class: 'inline-status',
                text: state.reachable === false
                    ? 'The TV is not answering. Check that it is switched on and on the same wifi.'
                    : (playing && playing.title
                        ? 'Now playing: ' + playing.title
                        : 'The TV is on, and nothing is playing.')
            })
        ];

        if (playing && playing.title) {
            body.push(h('div', { class: 'btn-group' }, [
                actionButton(playing.playing ? 'Pause' : 'Play', 'playback-pause', {}, 'btn--sm'),
                actionButton('Next video', 'playback-skip', {}, 'btn--sm'),
                actionButton('Stop', 'playback-stop', {}, 'btn--sm')
            ]));
        }

        body.push(h('div', { class: 'btn-group' }, [
            actionButton('Send my library to the TV now', 'send-to-tv', {}, 'btn--primary')
        ]));

        if (status && status.version) {
            body.push(h('p', { class: 'panel__note', text: 'SafeTube ' + status.version + ' on the TV' }));
        }

        return panel('Your TV', 'What the television is doing right now.', h('div', {}, body));
    }

    function screenTimePanel() {
        var limits = state.limits;

        if (!limits) {
            return panel('Screen time', 'How long your child may watch, and when.',
                h('p', { class: 'inline-status' }, [h('span', { class: 'spinner' }), 'Loading…']));
        }

        var used = limits.todayUsedMin || 0;
        var limit = limits.todayLimitMin;
        var percent = limit && limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;

        var body = [
            h('div', { class: 'stats' }, [
                h('div', { class: 'stat' }, [
                    h('p', { class: 'stat__value', text: used + ' min' }),
                    h('p', { class: 'stat__label', text: 'Watched today' })
                ]),
                h('div', { class: 'stat' }, [
                    h('p', {
                        class: 'stat__value',
                        text: limits.todayRemainingMin === null || limits.todayRemainingMin === undefined
                            ? 'No limit'
                            : limits.todayRemainingMin + ' min'
                    }),
                    h('p', { class: 'stat__label', text: 'Left today' })
                ])
            ])
        ];

        if (limit && limit > 0) {
            var barClass = 'progress__bar' +
                (percent >= 100 ? ' progress__bar--danger' : (percent >= 80 ? ' progress__bar--warn' : ''));
            body.push(h('div', {
                class: 'progress',
                role: 'img',
                'aria-label': used + ' of ' + limit + ' minutes used today'
            }, [h('span', { class: barClass, style: 'width: ' + percent + '%' })]));
        }

        if (limits.hasTimeRequest) {
            body.push(banner('info', 'Your child asked for more time',
                'Give a little extra, or leave it as it is.',
                [
                    actionButton('+15 minutes', 'bonus', { 'data-minutes': '15' }, 'btn--primary'),
                    actionButton('+30 minutes', 'bonus', { 'data-minutes': '30' })
                ]));
        }

        if (limits.manuallyLocked) {
            body.push(banner('warn', 'The TV is locked', 'Nothing can play until you unlock it.', []));
        } else if (limits.currentStatus === 'blocked') {
            body.push(banner('warn', 'Screen time is over for today',
                limits.lockReason === 'bedtime' ? 'It is past bedtime.' : 'Today’s limit has been reached.', []));
        }

        body.push(h('div', { class: 'btn-group' }, [
            actionButton(limits.manuallyLocked ? 'Unlock the TV' : 'Lock the TV', 'lock-tv', {},
                limits.manuallyLocked ? 'btn--primary' : ''),
            actionButton('+15 minutes', 'bonus', { 'data-minutes': '15' }),
            actionButton('Change the daily limits', 'edit-limits', {})
        ]));

        body.push(limitEditor(limits));

        return panel('Screen time', 'How long your child may watch, and when.', h('div', {}, body));
    }

    function limitEditor(limits) {
        var days = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
        var labels = {
            monday: 'Monday', tuesday: 'Tuesday', wednesday: 'Wednesday', thursday: 'Thursday',
            friday: 'Friday', saturday: 'Saturday', sunday: 'Sunday'
        };

        var inputs = {};
        fields.limitInputs = inputs;

        var rows = days.map(function (day) {
            var value = limits.dailyLimits ? limits.dailyLimits[day] : null;
            var input = h('input', {
                class: 'input',
                type: 'number',
                inputmode: 'numeric',
                min: '-1',
                max: '480',
                value: value === undefined || value === null ? '-1' : String(value),
                'aria-label': labels[day] + ', minutes allowed'
            });
            inputs[day] = input;
            return h('label', { class: 'field' }, [
                h('span', { class: 'field__label', text: labels[day] }),
                input
            ]);
        });

        var bedtimeStart = h('input', {
            class: 'input',
            type: 'time',
            value: (limits.bedtime && limits.bedtime.start) || '20:00',
            'aria-label': 'Bedtime starts'
        });
        var bedtimeEnd = h('input', {
            class: 'input',
            type: 'time',
            value: (limits.bedtime && limits.bedtime.end) || '07:00',
            'aria-label': 'Bedtime ends'
        });
        fields.bedtimeStart = bedtimeStart;
        fields.bedtimeEnd = bedtimeEnd;

        var save = withListener(h('button', { type: 'button', class: 'btn btn--primary', text: 'Save the limits' }),
            'click', saveLimits);

        var details = h('details', { class: 'panel' }, [
            h('summary', { class: 'panel__title', text: 'Daily limits and bedtime' }),
            h('p', { class: 'field__hint', text: 'Minutes allowed each day. Use −1 for no limit.' }),
            h('div', {}, rows),
            h('div', { class: 'two-up' }, [
                h('label', { class: 'field' }, [h('span', { class: 'field__label', text: 'Bedtime starts' }), bedtimeStart]),
                h('label', { class: 'field' }, [h('span', { class: 'field__label', text: 'Bedtime ends' }), bedtimeEnd])
            ]),
            save
        ]);
        fields.limitsDetails = details;

        return details;
    }

    async function saveLimits() {
        var dailyLimits = {};
        Object.keys(fields.limitInputs || {}).forEach(function (day) {
            var raw = parseInt(fields.limitInputs[day].value, 10);
            dailyLimits[day] = isNaN(raw) ? -1 : Math.max(-1, Math.min(480, raw));
        });

        var result = await apiCall('PUT', '/time-limits', {
            dailyLimits: dailyLimits,
            bedtimeStartMin: toMinutes(fields.bedtimeStart.value),
            bedtimeEndMin: toMinutes(fields.bedtimeEnd.value)
        });

        if (result.status !== 200) {
            toast((result.data && result.data.error) || 'The limits could not be saved', 'error');
            return;
        }

        await loadLimits();
        toast('Screen time saved.', 'ok');
        render();
        if (fields.limitsDetails) fields.limitsDetails.open = true;
    }

    function toMinutes(value) {
        var parts = String(value || '').split(':');
        if (parts.length !== 2) return -1;
        var hours = parseInt(parts[0], 10);
        var minutes = parseInt(parts[1], 10);
        if (isNaN(hours) || isNaN(minutes)) return -1;
        return hours * 60 + minutes;
    }

    function allowedSourcesPanel() {
        var list = state.playlists.length
            ? h('ul', {}, state.playlists.map(function (source) {
                return h('li', { class: 'row' }, [
                    h('span', { class: 'row__body' }, [
                        h('span', { class: 'row__title', text: source.displayName || source.sourceId }),
                        h('span', { class: 'row__meta', text: describeSource(source) })
                    ]),
                    h('span', { class: 'row__actions' }, [
                        actionButton('Remove', 'remove-source', { 'data-id': String(source.id) },
                            'btn--sm btn--danger')
                    ])
                ]);
            }))
            : h('p', {
                class: 'panel__note',
                text: 'Nothing is allowed yet, so nothing in your library can play on the TV.'
            });

        var urlInput = h('input', {
            class: 'input',
            type: 'url',
            inputmode: 'url',
            autocapitalize: 'off',
            placeholder: 'https://www.youtube.com/@channel',
            'aria-label': 'Channel, playlist or video link'
        });
        fields.sourceUrl = urlInput;

        var add = withListener(h('button', { type: 'button', class: 'btn btn--primary', text: 'Allow it' }),
            'click', addSource);

        var body = [
            h('p', {
                class: 'panel__note',
                text: 'Only what is listed here can play on the TV. Adding something to your library does not allow it.'
            }),
            list,
            h('label', { class: 'field' }, [
                h('span', { class: 'field__label', text: 'Allow a channel, playlist or video' }),
                urlInput
            ]),
            h('div', { class: 'btn-group' }, [
                add,
                actionButton('Save the list to a file', 'export-sources', {}),
                actionButton('Load a saved list', 'import-sources', {})
            ])
        ];

        return panel('Allowed sources', null, h('div', {}, body));
    }

    function describeSource(source) {
        var kind = source.sourceType === 'yt_channel' ? 'Channel'
            : (source.sourceType === 'yt_video' ? 'Video' : 'Playlist');
        var videos = typeof source.videoCount === 'number' && source.videoCount > 0
            ? ' · ' + words(source.videoCount, 'video') + ' ready'
            : '';
        return kind + videos;
    }

    async function addSource() {
        var url = (fields.sourceUrl && fields.sourceUrl.value || '').trim();
        if (!url) {
            toast('Paste a link first.', 'error');
            return;
        }

        var result = await apiCall('POST', '/playlists', { url: url });
        if (result.status !== 200 && result.status !== 409) {
            toast((result.data && result.data.error) || 'That link could not be allowed', 'error');
            return;
        }

        fields.sourceUrl.value = '';
        await loadPlaylists();
        await refreshTv();
        toast(result.status === 409 ? 'That was already allowed.' : 'Allowed. It will be ready in a moment.', 'ok');
        render();
    }

    async function removeSource(id) {
        var source = state.playlists.filter(function (candidate) {
            return String(candidate.id) === String(id);
        })[0];
        if (!source) return;

        var confirmed = await confirmDialog({
            title: 'Stop allowing “' + (source.displayName || source.sourceId) + '”?',
            body: 'Videos from it stay in your library, but they will not play on the TV any more.',
            confirmLabel: 'Stop allowing',
            danger: true
        });
        if (!confirmed) return;

        var result = await apiCall('DELETE', '/playlists/' + encodeURIComponent(id));
        if (result.status !== 200) {
            toast((result.data && result.data.error) || 'That could not be removed', 'error');
            return;
        }

        await loadPlaylists();
        toast('That source is no longer allowed.', 'ok');
        render();
    }

    function libraryPanel() {
        var count = state.session.nodes.length;
        var version = state.session.catalogVersion;
        var installed = state.artwork.installedCatalogVersion;
        var upToDate = version > 0 && installed >= version;

        var body = [
            h('p', {
                class: 'panel__note',
                text: words(count, 'item') + ' in your library. ' +
                    (upToDate
                        ? 'Your TV has this version.'
                        : 'Your TV is on an older version — send it now.')
            }),
            h('div', { class: 'btn-group' }, [
                actionButton('Send to TV now', 'send-to-tv', {}, 'btn--primary'),
                actionButton('Check my library', 'check-library', {}),
                actionButton('Reload from the TV', 'reload-catalog', {})
            ])
        ];

        return panel('Your library', 'The shelves, folders and videos you have made.', h('div', {}, body));
    }

    function watchHistoryPanel() {
        var stats = state.stats;
        var body = [];

        if (!stats) {
            body.push(h('p', { class: 'inline-status' }, [h('span', { class: 'spinner' }), 'Loading…']));
        } else {
            body.push(h('div', { class: 'stats' }, [
                h('div', { class: 'stat' }, [
                    h('p', { class: 'stat__value', text: String(stats.totalEventsToday) }),
                    h('p', { class: 'stat__label', text: 'Videos today' })
                ]),
                h('div', { class: 'stat' }, [
                    h('p', {
                        class: 'stat__value',
                        text: Math.round((stats.totalWatchTimeToday || 0) / 60) + ' min'
                    }),
                    h('p', { class: 'stat__label', text: 'Watched today' })
                ]),
                h('div', { class: 'stat' }, [
                    h('p', { class: 'stat__value', text: String(stats.totalEventsAllTime) }),
                    h('p', { class: 'stat__label', text: 'Videos all time' })
                ])
            ]));
        }

        if (state.recent.length) {
            body.push(h('ul', {}, state.recent.slice(0, 8).map(function (event) {
                return h('li', { class: 'row' }, [
                    h('span', { class: 'row__body' }, [
                        h('span', { class: 'row__title', text: event.title || event.videoId }),
                        h('span', { class: 'row__meta', text: whenWords(event.startedAt) })
                    ])
                ]);
            })));
        } else if (stats) {
            body.push(h('p', { class: 'panel__note', text: 'Nothing has been watched yet.' }));
        }

        return panel('What has been watched', 'The last videos played on the TV, newest first.',
            h('div', {}, body));
    }

    function whenWords(stamp) {
        if (!stamp) return '';
        var date = new Date(stamp);
        var time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        if (date.toDateString() === new Date().toDateString()) return 'Today at ' + time;
        return date.toLocaleDateString() + ' at ' + time;
    }

    function lookPanel() {
        var dark = currentTheme() === 'dark';

        var light = withListener(h('button', {
            type: 'button',
            class: 'segment__option' + (dark ? '' : ' is-selected'),
            text: 'Light'
        }), 'click', function () { applyTheme('light', true); render(); });

        var darkButton = withListener(h('button', {
            type: 'button',
            class: 'segment__option' + (dark ? ' is-selected' : ''),
            text: 'Dark'
        }), 'click', function () { applyTheme('dark', true); render(); });

        return panel('Look', 'The colours of this page. Your choice is remembered on this phone.',
            h('div', { class: 'segment' }, [light, darkButton]));
    }

    function helpPanel() {
        var log = h('textarea', { class: 'textarea', readonly: true, 'aria-label': 'Last error report' });
        log.value = state.crash || 'No errors have been recorded.';

        var copy = withListener(h('button', { type: 'button', class: 'btn', text: 'Copy the error report' }),
            'click', async function () {
                try {
                    await navigator.clipboard.writeText(log.value);
                    toast('Copied.', 'ok');
                } catch (error) {
                    log.focus();
                    log.select();
                    toast('Press and hold the text to copy it.', 'ok');
                }
            });

        return panel('Something wrong?',
            'If the TV misbehaves, copy the last error report and send it to whoever set SafeTube up for you.',
            h('div', {}, [
                log,
                h('div', { class: 'btn-group' }, [copy]),
                h('div', { class: 'btn-group' }, [
                    actionButton('Disconnect this phone', 'disconnect', {}, 'btn--danger')
                ])
            ]));
    }

    // --- the action table --------------------------------------------------------------------
    //
    // Every control in the page - in index.html and in the screens built above - ends up here. It is
    // the one place that turns a tap into work, and the guard tests check that nothing on the page
    // names an action this table does not have.

    var ACTIONS = {
        'go-library': function () { go('#/library'); },
        'go-settings': function () { go('#/settings'); },
        'toggle-theme': function () {
            toggleTheme();
            // The look is also offered inside Settings, so that screen has to catch up with the
            // header's switch rather than showing the other answer until the next navigation.
            if (state.route.name === 'settings') render();
        },

        'new-shelf': function () { createNode('CATEGORY', null); },
        'new-folder': function (element) { createNode('SUBCATEGORY', element.getAttribute('data-parent')); },
        'add-from-youtube': function (element) {
            var parent = element.getAttribute('data-parent');
            go('#/add' + (parent ? '/' + encodeURIComponent(parent) : ''));
        },
        'add-single-video': function (element) {
            addSingleVideo(element.getAttribute('data-video-id'));
        },
        'show-node': function (element) { showNode(element.getAttribute('data-id')); },

        'send-to-tv': function () { sendToTv(); },
        'reload-catalog': function () { reloadFromTv(); },
        'check-library': function () { checkLibrary(); },
        'allow-sources': function (element) { allowSources(element.getAttribute('data-sources')); },

        'playback-stop': function () { playback('stop', 'Stopped'); },
        'playback-pause': function () { playback('pause', 'Done'); },
        'playback-skip': function () { playback('skip', 'Skipped'); },

        'lock-tv': function () { toggleLock(); },
        'bonus': function (element) { grantBonus(element.getAttribute('data-minutes')); },
        'edit-limits': function () {
            if (!fields.limitsDetails) return;
            fields.limitsDetails.open = true;
            fields.limitsDetails.scrollIntoView({ block: 'center', behavior: 'smooth' });
        },

        'remove-source': function (element) { removeSource(element.getAttribute('data-id')); },
        'export-sources': function () { exportSources(); },
        'import-sources': function () { importSources(); },

        'install-homescreen': function () { installHomescreen(); },
        'dismiss-homescreen': function () { dismissHomescreen(); },
        'disconnect': function () { disconnect(); }
    };

    document.addEventListener('click', function (event) {
        var element = event.target && event.target.closest ? event.target.closest('[data-action]') : null;
        if (!element) return;
        var handler = ACTIONS[element.getAttribute('data-action')];
        if (!handler) return;
        event.preventDefault();
        handler(element);
    });

    // --- the actions themselves --------------------------------------------------------------

    async function createNode(type, parentId) {
        var shelf = type === 'CATEGORY';
        var title = await askForText({
            title: shelf ? 'New shelf' : 'New folder',
            body: shelf
                ? 'A shelf is a row of videos on the TV, like “Cartoons”.'
                : 'A folder groups videos together inside a shelf, like “Songs”.',
            label: 'Name',
            confirmLabel: 'Create'
        });

        if (title === null) return;

        var applied = shelf
            ? CatalogEditor.addCategory(state.session, { title: title })
            : CatalogEditor.addSubcategory(state.session, { title: title, parentId: parentId });

        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;
        publish(shelf ? 'New shelf' : 'New folder');
    }

    async function showNode(id) {
        var applied = CatalogEditor.setEnabled(state.session, { id: id, enabled: true });
        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;
        publish('Shown again');
    }

    async function sendToTv() {
        var result = await refreshTv();
        await loadArtwork();
        render();
        toast(result.ok
            ? (result.message || 'Your TV has the latest library.')
            : 'The TV did not answer. It will try again on its own.',
            result.ok ? 'ok' : 'error');
    }

    async function reloadFromTv() {
        var loaded = await loadLibrary();
        if (!loaded.ok) return void toast(loaded.reason, 'error');
        toast('Loaded your library.', 'ok');
        render();
    }

    function checkLibrary() {
        var problems = CatalogEditor.problems(state.session);
        if (!problems.length) {
            toast('Everything checks out.', 'ok');
            return;
        }
        explainProblems(problems);
    }

    async function allowSources(raw) {
        var sources = {};
        try {
            sources = JSON.parse(raw || '{}');
        } catch (error) {
            sources = {};
        }

        var ids = Object.keys(sources);
        if (!ids.length) return;

        var allowed = 0;
        for (var i = 0; i < ids.length; i++) {
            var url = sources[ids[i]] === 'video'
                ? 'https://www.youtube.com/watch?v=' + ids[i]
                : 'https://www.youtube.com/playlist?list=' + ids[i];
            var result = await apiCall('POST', '/playlists', { url: url });
            if (result.status === 200 || result.status === 409) allowed++;
        }

        await loadPlaylists();
        await refreshTv();
        toast(allowed
            ? 'Allowed. Those videos can play once the TV has them.'
            : 'That could not be allowed.', allowed ? 'ok' : 'error');
        render();
    }

    async function addSingleVideo(videoId) {
        var parentId = fields.addParent ? fields.addParent.value : null;
        if (!parentId) {
            toast('Choose where this should go', 'error');
            return;
        }

        var resolved = await apiCall('POST', '/catalog/import/resolve', {
            url: 'https://www.youtube.com/watch?v=' + videoId
        });
        if (resolved.status !== 200) {
            toast((resolved.data && resolved.data.error) || 'That video could not be read', 'error');
            return;
        }

        var applied = CatalogEditor.addVideo(state.session, {
            parentId: parentId,
            title: resolved.data.title || videoId,
            youtubeVideoId: videoId
        });
        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;

        var parent = nodeById(parentId);
        var saved = await publish('Added “' + (resolved.data.title || videoId) + '”');
        if (saved) go(routeFor(parent));
    }

    async function playback(kind, label) {
        var result = await apiCall('POST', '/playback/' + kind);
        toast(result.status === 200 ? label + '.' : 'The TV did not answer.',
            result.status === 200 ? 'ok' : 'error');
        await loadStatus();
        render();
    }

    async function toggleLock() {
        var locked = !(state.limits && state.limits.manuallyLocked);
        var result = await apiCall('POST', '/time-limits/lock', { locked: locked });
        if (result.status !== 200) {
            toast('That did not work', 'error');
            return;
        }
        await loadLimits();
        toast(locked ? 'The TV is locked.' : 'The TV is unlocked.', 'ok');
        render();
    }

    async function grantBonus(minutes) {
        var result = await apiCall('POST', '/time-limits/bonus', { minutes: parseInt(minutes, 10) });
        if (result.status !== 200) {
            toast((result.data && result.data.error) || 'That did not work', 'error');
            return;
        }
        await loadLimits();
        toast('Added ' + minutes + ' minutes for today.', 'ok');
        render();
    }

    async function exportSources() {
        var result = await apiCall('GET', '/sources/export');
        if (result.status !== 200) {
            toast('The list could not be saved', 'error');
            return;
        }

        var blob = new Blob([JSON.stringify(result.data, null, 2)], { type: 'application/json' });
        var link = h('a', { href: URL.createObjectURL(blob), download: 'safetube-allowed-sources.json' });
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        toast('Saved to your downloads.', 'ok');
    }

    function importSources() {
        var fileInput = h('input', { type: 'file', accept: 'application/json,.json', 'aria-label': 'Choose a file' });

        fileInput.addEventListener('change', async function () {
            var file = fileInput.files && fileInput.files[0];
            if (!file) return;

            var text = await file.text();
            var payload;
            try {
                payload = JSON.parse(text);
            } catch (error) {
                toast('That file is not a SafeTube list.', 'error');
                return;
            }

            var result = await apiCall('POST', '/sources/import', payload);
            if (result.status !== 200) {
                toast((result.data && result.data.error) || 'That file could not be read', 'error');
                return;
            }

            await loadPlaylists();
            toast('Loaded: ' + describeImport(result.data), 'ok');
            render();
        });

        fileInput.click();
    }

    function describeImport(summary) {
        if (!summary || typeof summary.added !== 'number') return 'done';
        var parts = [words(summary.added, 'source') + ' added'];
        if (summary.skipped) parts.push(words(summary.skipped, 'source') + ' already there');
        if (summary.failed) parts.push(words(summary.failed, 'source') + ' could not be read');
        return parts.join(', ');
    }

    function installHomescreen() {
        if (!deferredInstallPrompt) {
            toast('Use your browser’s menu to add this page to the home screen.', 'ok');
            return;
        }
        deferredInstallPrompt.prompt();
        deferredInstallPrompt.userChoice.then(function () {
            deferredInstallPrompt = null;
            render();
        });
    }

    function dismissHomescreen() {
        try {
            window.localStorage.setItem(HOMESCREEN_KEY, '1');
        } catch (error) {
            // The hint simply comes back next time.
        }
        render();
    }

    async function disconnect() {
        var confirmed = await confirmDialog({
            title: 'Disconnect this phone?',
            body: 'Nothing on the TV changes. You will need the PIN from the TV to come back.',
            confirmLabel: 'Disconnect',
            danger: true
        });
        if (!confirmed) return;
        forgetSession();
        toast('Disconnected.', 'ok');
    }

    // --- polling -----------------------------------------------------------------------------

    function startPolling() {
        stopPolling();
        loadStatus();
        statusTimer = setInterval(function () {
            if (document.hidden) return;
            loadStatus();
        }, 20000);
    }

    function stopPolling() {
        if (statusTimer) clearInterval(statusTimer);
        statusTimer = null;
    }

    // --- startup -----------------------------------------------------------------------------

    window.addEventListener('hashchange', function () {
        render();
        view.focus();
    });

    window.addEventListener('beforeinstallprompt', function (event) {
        event.preventDefault();
        deferredInstallPrompt = event;
        render();
    });

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && state.token) loadStatus();
    });

    async function boot() {
        // The theme is already on the document - `theme.js` ran in the head, before anything painted -
        // so all that is left is to say on the control which way round it is.
        paintThemeControl();

        // The public status answers without a session, which is how the page can tell a parent that
        // the TV is unreachable - or that this page is too old - before they hunt for the PIN.
        await loadStatus();

        var pin = extractPin();
        if (pin && !state.token) {
            var result = await connect(pin);
            if (!result.ok) toast(result.reason, 'error');
        }

        if (state.token) {
            var valid = await refreshSession();
            if (!valid) {
                forgetSession();
                return;
            }

            var loaded = await loadLibrary();
            if (!loaded.ok) toast(loaded.reason, 'error');
            startPolling();
            loadStats();
            loadLimits();
            loadCrash();
        }

        render();
    }

    boot();
})();
