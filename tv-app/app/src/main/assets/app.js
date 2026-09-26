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
        route: { name: 'library', id: null },
        // What the TV last said it was playing, and when it said it: the two facts the freshness
        // rule is made of.
        nowPlayingSeen: null,
        nowPlayingFrozen: 0,
        nowPlayingAt: 0
    };

    // The inputs of whichever screen is on screen, so the handlers can read them without looking
    // anything up by id.
    var fields = {};

    var deferredInstallPrompt = null;
    var statusTimer = null;
    var toastTimer = null;
    var playheadTimer = null;
    var pollIntervalMs = 20000;
    var saving = false;

    // The mounted Now Playing card's parts, so the playhead can be painted without a re-render.
    var npRefs = null;

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

    /**
     * A message a parent can act on, from whatever the server or the network said.
     *
     * Server failures arrive in two flavours: the ones this project wrote on purpose ("Paste a
     * YouTube playlist or video link", "A whole channel is not a shelf…") and the ones that are
     * somebody else's words - an extractor exception, a socket timeout, a JSON parser complaint.
     * The first kind is more useful than anything this page could invent, so it is passed through;
     * the second is a sentence a parent cannot act on, so it becomes one they can.
     */
    var FRIENDLY_FAILURES = [
        { match: /private|unavailable|not available|deleted|removed/i, says: 'That playlist is private or no longer on YouTube.' },
        { match: /offline|simulated/i, says: 'The TV could not reach YouTube just now. Try again in a moment.' },
        { match: /timeout|timed out|connect|socket|unreachable|503/i, says: 'The TV did not answer. Check that it is on and on the same wifi.' },
        { match: /not found|no such|404/i, says: 'Nothing was found at that link.' },
        { match: /list|playlist.*(empty|no videos)|no videos/i, says: 'That playlist has no videos that can be added.' },
    ];

    /** True when a server message is one the project wrote for a parent to read. */
    function isParentReadable(message) {
        if (!message || typeof message !== 'string') return false;
        if (message.length > 160) return false;
        return !/(exception|\bnull\b|undefined|\bat [a-z]+\.|\.kt:|\.java:|json|serializ|extract|url:|http[s]?:\/\/127|stack)/i
            .test(message);
    }

    function humanError(data, fallback) {
        var message = (data && data.error) || '';
        if (isParentReadable(message)) return message;

        for (var i = 0; i < FRIENDLY_FAILURES.length; i++) {
            if (FRIENDLY_FAILURES[i].match.test(message)) return FRIENDLY_FAILURES[i].says;
        }
        return fallback || 'That did not work. Please try again.';
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
        noteNowPlaying();
        paintStatusPill();
        paintNowPlaying();
        retunePolling();
    }

    // --- what the TV is playing right now ------------------------------------------------------
    //
    // The TV publishes this itself: `GET /status` answers from the player's own live state (the
    // video id, the title, the queue it came from, the playhead, whether it is playing), and the
    // player clears it when playback stops, moves to the next video or leaves the screen. So this
    // is not watch history read back, and it is not a guess: it is what Media3 is doing at the
    // moment the question was asked.
    //
    // What the endpoint does *not* carry is a timestamp, so freshness is derived from the one thing
    // that must move while a video plays: the playhead. Three consecutive polls reporting the same
    // position while the TV says it is playing means the TV has stopped reporting, and the screen
    // says exactly that rather than "nothing is playing" - which would be a different, and false,
    // statement.

    var NOW_PLAYING_STALE_POLLS = 3;

    /** The state of the TV, in the one place the screens read it from. */
    function nowPlayingModel() {
        if (state.reachable === null) return { state: 'connecting' };
        if (state.reachable === false) return { state: 'unreachable' };

        var playing = state.status && state.status.currentlyPlaying;
        if (!playing || !playing.videoId) return { state: 'idle' };
        if (state.nowPlayingFrozen >= NOW_PLAYING_STALE_POLLS) {
            return { state: 'stale', playing: playing };
        }
        return { state: playing.playing ? 'playing' : 'paused', playing: playing };
    }

    /** Compares this poll with the last one, so a frozen playhead can be told from a moving one. */
    function noteNowPlaying() {
        var playing = state.status && state.status.currentlyPlaying;

        if (!playing || !playing.videoId) {
            state.nowPlayingSeen = null;
            state.nowPlayingFrozen = 0;
            return;
        }

        var seen = state.nowPlayingSeen;
        var moved = !seen || seen.videoId !== playing.videoId ||
            seen.positionSec !== playing.positionSec || seen.playing !== playing.playing;

        state.nowPlayingFrozen = moved ? 0 : (state.nowPlayingFrozen || 0) + 1;
        state.nowPlayingSeen = {
            videoId: playing.videoId,
            positionSec: playing.positionSec,
            playing: playing.playing
        };
        // When the TV said it. The playhead is advanced locally from here, so the readout moves
        // between polls instead of jumping once every five seconds.
        state.nowPlayingAt = Date.now();
    }

    /** A picture for the playing video: the TV's own artwork when it has one. */
    function nowPlayingArtwork(playing) {
        var cached = state.artwork.videos[playing.videoId];
        if (cached) return cached;
        // The TV has no picture for this video, which happens when it is playing something the
        // library does not name. YouTube's own thumbnail for a video id is public and derivable, and
        // it is a picture of the thing being described - not an invented fact about it. If it does
        // not load, the card falls back to the placeholder like every other picture here.
        return /^[A-Za-z0-9_-]{6,20}$/.test(playing.videoId || '')
            ? 'https://i.ytimg.com/vi/' + playing.videoId + '/hqdefault.jpg'
            : '';
    }

    function formatClock(totalSeconds) {
        var seconds = Math.max(0, Math.floor(totalSeconds || 0));
        var minutes = Math.floor(seconds / 60);
        var rest = seconds % 60;
        if (minutes < 60) return minutes + ':' + (rest < 10 ? '0' : '') + rest;
        return Math.floor(minutes / 60) + ':' + ('0' + (minutes % 60)).slice(-2) + ':' + ('0' + rest).slice(-2);
    }

    /** Where the playhead is now: the TV's last report, plus the time since it was made. */
    function playheadNow(playing) {
        var position = playing.positionSec || 0;
        if (playing.playing && state.nowPlayingFrozen === 0 && state.nowPlayingAt) {
            position += (Date.now() - state.nowPlayingAt) / 1000;
        }
        if (playing.durationSec > 0) position = Math.min(position, playing.durationSec);
        return Math.max(0, position);
    }

    /**
     * The Now Playing card.
     *
     * Five states, and the difference between them matters: a TV that cannot be reached is not a TV
     * that is playing nothing, and a TV that has stopped reporting is neither. Showing the wrong one
     * would make a parent think their child is watching when they are not - or the reverse, which is
     * worse.
     *
     * The card records its own parts as it is built, which is how the playhead and the state can be
     * painted in place later without rebuilding the screen.
     */
    function nowPlayingCard() {
        var model = nowPlayingModel();

        if (model.state !== 'playing' && model.state !== 'paused') {
            // The one-line states are all the same shape, so only their words change - but they must
            // change: "Nothing is playing" becoming "Unable to reach the TV" is the difference
            // between a child who stopped watching and a TV that has gone quiet, and a card that
            // never repainted would keep telling the parent the reassuring one.
            npRefs = { mode: 'quiet', quietState: model.state };
            return quietNowPlaying(model.state);
        }

        var playing = model.playing;
        var position = playheadNow(playing);
        var title = playing.title || playing.videoId;

        var stateLine = h('p', { class: 'now-playing__state' });
        var elapsed = h('span', { text: formatClock(position) });
        var duration = h('span', { class: 'now-playing__of', text: playing.durationSec > 0 ? ' / ' + formatClock(playing.durationSec) : '' });
        var time = h('p', { class: 'now-playing__time' }, [elapsed, duration]);
        var fill = h('span', {
            class: 'progress__bar',
            style: 'width: ' + (playing.durationSec > 0 ? Math.round((position / playing.durationSec) * 100) : 0) + '%'
        });
        var progress = h('div', { class: 'progress now-playing__progress' }, [fill]);
        var pause = actionButton(model.state === 'playing' ? 'Pause' : 'Resume', 'playback-pause', {}, 'btn--sm');
        var actions = h('div', { class: 'now-playing__actions' }, [
            pause,
            actionButton('Stop', 'playback-stop', {}, 'btn--sm')
        ]);
        var picture = pictureNode(title, nowPlayingArtwork(playing), 'now-playing__art');

        npRefs = {
            mode: 'card',
            videoId: playing.videoId,
            state: stateLine,
            elapsed: elapsed,
            time: time,
            duration: duration,
            fill: fill,
            progress: progress,
            actions: actions,
            title: h('p', { class: 'now-playing__title', text: title }),
            picture: picture
        };

        paintNowPlayingState(model, stateLine);

        return h('section', { class: 'now-playing', 'aria-live': 'polite' }, [
            h('p', { class: 'now-playing__label', text: 'Now playing' }),
            h('div', { class: 'now-playing__row' }, [
                picture,
                h('div', { class: 'now-playing__body' }, [npRefs.title, stateLine, time])
            ]),
            playing.durationSec > 0 ? progress : null,
            actions
        ]);
    }

    function paintNowPlayingState(model, stateLine) {
        stateLine.textContent = '';
        stateLine.appendChild(h('span', {
            class: 'now-playing__icon',
            'aria-hidden': 'true',
            text: model.state === 'playing' ? '▶' : '❙❙'
        }));
        stateLine.appendChild(document.createTextNode(
            model.state === 'playing' ? 'Playing on TV' : 'Paused on TV'));
    }

    /** The one-line version: what a TV that is idle, unreachable or quiet gets instead of a card. */
    function quietNowPlaying(state) {
        var says = {
            connecting: 'Connecting…',
            unreachable: 'Unable to reach the TV.',
            stale: 'The TV stopped reporting what it is playing.',
            idle: 'Nothing is playing right now'
        }[state] || 'Nothing is playing right now';

        var actions = state === 'unreachable' || state === 'stale'
            ? [actionButton('Try again', 'retry-status', {}, 'btn--sm')]
            : [];

        return h('section', { class: 'now-playing now-playing--quiet', 'aria-live': 'polite' }, [
            h('p', { class: 'now-playing__label', text: 'TV' }),
            h('div', { class: 'now-playing__quiet-body' }, [
                h('p', { class: 'now-playing__state', text: says }),
                actions.length ? h('div', { class: 'now-playing__actions' }, actions) : null
            ])
        ]);
    }

    /**
     * Updates the card in place.
     *
     * The playhead moves every second and the TV is polled every few seconds, and rebuilding the
     * whole screen on either would throw away the parent's scroll position and focus for a number
     * that changed by one. So the card is painted, not re-rendered - except when its *shape* has to
     * change (a video starting or stopping), which is rare and worth a render.
     */
    function paintNowPlaying() {
        if (!state.token || state.route.name !== 'library') return;

        var model = nowPlayingModel();
        var wanted = (model.state === 'playing' || model.state === 'paused') ? 'card' : 'quiet';

        if (!npRefs || npRefs.mode !== wanted) {
            render();
            return;
        }

        if (wanted === 'quiet') {
            if (npRefs.quietState !== model.state) render();
            return;
        }

        var playing = model.playing;
        var position = playheadNow(playing);
        paintNowPlayingState(model, npRefs.state);
        npRefs.elapsed.textContent = formatClock(position);
        npRefs.duration.textContent = playing.durationSec > 0 ? ' / ' + formatClock(playing.durationSec) : '';
        npRefs.progress.hidden = playing.durationSec <= 0;
        if (playing.durationSec > 0) {
            npRefs.fill.style.width = Math.round((position / playing.durationSec) * 100) + '%';
        }

        if (npRefs.videoId !== playing.videoId) {
            npRefs.videoId = playing.videoId;
            npRefs.title.textContent = playing.title || playing.videoId;
            if (npRefs.picture.tagName === 'IMG') {
                npRefs.picture.src = nowPlayingArtwork(playing);
                npRefs.picture.alt = playing.title || playing.videoId;
            }
            npRefs.actions.replaceChild(
                actionButton(model.state === 'playing' ? 'Pause' : 'Resume', 'playback-pause', {}, 'btn--sm'),
                npRefs.actions.firstChild
            );
        }
    }

    /** The playhead ticker: one timer, only while a video is playing and the page is visible. */
    function retunePlayheadTicker() {
        var wanted = nowPlayingModel().state === 'playing' && !document.hidden &&
            state.route.name === 'library';

        if (wanted && !playheadTimer) {
            playheadTimer = setInterval(function () {
                if (document.hidden) return;
                var model = nowPlayingModel();
                if (model.state !== 'playing' || !npRefs || npRefs.mode !== 'card') return;
                var position = playheadNow(model.playing);
                npRefs.elapsed.textContent = formatClock(position);
                if (model.playing.durationSec > 0) {
                    npRefs.fill.style.width =
                        Math.round((position / model.playing.durationSec) * 100) + '%';
                }
            }, 1000);
        } else if (!wanted && playheadTimer) {
            clearInterval(playheadTimer);
            playheadTimer = null;
        }
    }

    /**
     * How often to ask the TV.
     *
     * The old dashboard used 30 s while playing and 120 s when idle, which is why its progress bar
     * jumped. Playing is worth a short interval; a paused video changes only when somebody acts on
     * it; and an idle TV changes only when the child picks something, so 20 s is enough and keeps
     * the phone's radio quiet. The timer is retuned, never duplicated, and it never runs while the
     * page is hidden.
     */
    function retunePolling() {
        if (!state.token) return;

        var model = nowPlayingModel();
        var wanted = model.state === 'playing' ? 5000 : (model.state === 'paused' ? 10000 : 20000);
        if (wanted === pollIntervalMs && statusTimer) return;

        pollIntervalMs = wanted;
        stopPolling();
        startPolling();
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

    var TYPE_WORDS = { CATEGORY: 'Category', SUBCATEGORY: 'Collection', VIDEO: 'Video' };

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

    /** How many videos, collections and hidden things a category or collection holds. */
    function countsFor(nodeId) {
        var inside = descendantsOf(nodeId);
        var videos = inside.filter(function (child) { return child.nodeType === CatalogEditor.VIDEO; });
        return {
            collections: inside.filter(function (child) { return child.nodeType === CatalogEditor.SUBCATEGORY; }).length,
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
        return found ? 'From ' + found : 'From a YouTube source that is not allowed yet';
    }

    function nodeMeta(node, maps) {
        if (node.nodeType === CatalogEditor.VIDEO) {
            var parts = [sourceNameFor(node)];
            if (node.enabled === false) parts.push('hidden');
            if (!canPlay(node, maps)) parts.push('can\'t play yet');
            return parts.join(' · ');
        }

        var counts = countsFor(node.id);
        var videos = isContainer(node) ? containerVideoCount(node) : counts.videos;
        var collections = isContainer(node) ? 0 : counts.collections;

        var pieces = [];
        if (collections) pieces.push(words(collections, 'collection'));
        pieces.push(words(videos, 'video'));
        if (counts.hidden) pieces.push(words(counts.hidden, 'hidden item', 'hidden items'));
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
            screen = screenCategory(state.route.id);
        } else if (state.route.name === 'folder') {
            screen = screenCollection(state.route.id);
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
            tvState.classList.add(playing.playing ? 'pill--ok' : 'pill--warn');
            tvState.textContent = (playing.playing ? '▶ ' : '❙❙ ') + playing.title;
            tvState.title = playing.playing ? 'Playing on the TV right now' : 'Paused on the TV';
            return;
        }

        tvState.classList.add('pill--warn');
        tvState.textContent = 'TV on';
        tvState.title = 'Nothing is playing on the TV';
    }

    function screenNotLoaded() {
        return h('div', { class: 'screen' }, [
            screenHead('Your library', null, 'Loading what your child can watch…', []),
            h('div', { class: 'skeletons' }, [
                h('div', { class: 'skeleton skeleton--row' }),
                h('div', { class: 'skeleton skeleton--row' }),
                h('div', { class: 'skeleton skeleton--row' })
            ]),
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
        return pictureNode(node.title, artworkFor(node), className,
            node.nodeType === CatalogEditor.VIDEO ? '▶' : '🗂');
    }

    /**
     * A picture for something, with the two things every picture here needs: a name, and a way to
     * fail. A URL that stops resolving becomes the same placeholder the rest of the interface uses
     * rather than the browser's broken-image glyph.
     */
    function pictureNode(title, url, className, glyph) {
        var placeholder = function () {
            return h('div', {
                class: className + ' ' + className + '--placeholder',
                'aria-hidden': 'true',
                text: glyph || '▶'
            });
        };

        if (!url) return placeholder();

        var image = h('img', {
            class: className,
            src: url,
            alt: title || '',
            loading: 'lazy',
            decoding: 'async'
        });
        image.addEventListener('error', function () {
            if (image.parentNode) image.parentNode.replaceChild(placeholder(), image);
        });
        return image;
    }

    /**
     * The two reorder arrows, on whatever list the child's order comes from.
     *
     * One tap each, and visible without opening a menu - the difference between "order is yours to
     * set" and "order is a feature you have to discover". The longer journeys (to the top, to the
     * bottom, into another category) stay in the menu.
     */
    function orderButtons(node, order) {
        return [
            actionButton('▲', 'move-up', {
                'data-id': node.id,
                'aria-label': 'Move ' + node.title + ' up',
                disabled: order.index <= 0
            }, 'btn--icon btn--icon--sm'),
            actionButton('▼', 'move-down', {
                'data-id': node.id,
                'aria-label': 'Move ' + node.title + ' down',
                disabled: order.index >= order.last
            }, 'btn--icon btn--icon--sm')
        ];
    }

    /** One category, as a card: a titled row on the TV, with no picture of its own (W6.1). */
    function categoryTile(node, order) {
        var main = withListener(h('button', { type: 'button', class: 'tile__main' }, [
            h('span', { class: 'tile__body' }, [
                h('span', { class: 'tile__title', text: node.title }),
                h('span', { class: 'tile__meta', text: nodeMeta(node) })
            ]),
            h('span', { class: 'tile__chevron', 'aria-hidden': 'true', text: '›' })
        ]), 'click', function () { go('#/shelf/' + encodeURIComponent(node.id)); });
        main.setAttribute('aria-label', 'Open ' + node.title);

        return h('li', { class: 'tile' + (node.enabled === false ? ' tile--hidden' : '') }, [
            main,
            h('span', { class: 'tile__actions' },
                (order ? orderButtons(node, order) : []).concat([menuButton(node)]))
        ]);
    }

    function menuButton(node) {
        return withListener(h('button', {
            type: 'button',
            class: 'btn btn--icon btn--menu',
            'aria-label': 'More options for ' + node.title,
            text: '⋯'
        }), 'click', function () { openItemSheet(node.id); });
    }

    /** One collection or video, as a line with its picture: parents recognise these by sight. */
    function itemRow(node, maps, order) {
        var badges = [];
        if (node.enabled === false) badges.push(h('span', { class: 'badge badge--hidden', text: 'Hidden' }));
        if (node.nodeType === CatalogEditor.VIDEO && !canPlay(node, maps)) {
            badges.push(h('span', { class: 'badge badge--blocked', text: 'Can\'t play yet' }));
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

        var actions = [];
        if (order) actions = orderButtons(node, order);
        actions.push(menuButton(node));

        return h('li', { class: 'row' + (node.enabled === false ? ' row--hidden' : '') }, [
            main,
            h('span', { class: 'row__actions' }, actions)
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

    function addActions(parent) {
        // A collection holds videos, never other collections, so the only place a new collection can
        // be made is a category. Offering it anywhere else would be a button that can only fail.
        var actions = [actionButton('Add to library', 'add-from-youtube',
            { 'data-parent': parent ? parent.id : '' }, 'btn--primary')];
        if (!parent || parent.nodeType === CatalogEditor.CATEGORY) {
            actions.push(parent
                ? actionButton('New collection', 'new-folder', { 'data-parent': parent.id })
                : actionButton('New category', 'new-shelf', {}));
        }
        return actions;
    }

    /** Videos that cannot play yet *and that the child can currently reach*. */
    function unplayableHere(nodes) {
        var maps = allowedSourceMaps();
        var videos = [];
        nodes.forEach(function (node) {
            videos = videos.concat(descendantsOf(node.id).filter(function (child) {
                return child.nodeType === CatalogEditor.VIDEO;
            }));
        });

        // A hidden video is not a problem to report: the child cannot see it, so it cannot
        // disappoint them. It keeps its own badge on its own row, where the parent put it.
        return videos.filter(function (video) {
            return video.enabled !== false && !canPlay(video, maps);
        });
    }

    function unplayableBanner(nodes) {
        var stuck = unplayableHere(nodes);
        if (!stuck.length) return null;

        var sources = {};
        stuck.forEach(function (video) {
            if (video.youtubePlaylistId) sources[video.youtubePlaylistId] = 'playlist';
            else if (video.youtubeVideoId) sources[video.youtubeVideoId] = 'video';
        });
        var howMany = Object.keys(sources).length;

        return banner('warn', words(stuck.length, 'video') + ' can\'t play yet',
            'They are in your library, but the YouTube source they came from is not allowed for your ' +
            'child yet.',
            [actionButton(howMany === 1 ? 'Allow this source' : 'Allow these ' + howMany + ' sources',
                'allow-sources', { 'data-sources': JSON.stringify(sources) }, 'btn--primary')]);
    }

    // --- the library -------------------------------------------------------------------------

    /** Everything inside a category, counted once, for the library summary. */
    function libraryTotals(categories) {
        var collections = 0;
        var videos = 0;
        var hidden = 0;
        categories.forEach(function (category) {
            var counts = countsFor(category.id);
            collections += counts.collections;
            videos += counts.videos;
            hidden += counts.hidden;
        });
        return { collections: collections, videos: videos, hidden: hidden };
    }

    function screenLibrary() {
        var categories = CatalogEditor.roots(state.session);
        var totals = libraryTotals(categories);
        var behind = state.session.catalogVersion > 0 &&
            state.artwork.installedCatalogVersion < state.session.catalogVersion;

        var summary = categories.length
            ? words(categories.length, 'category', 'categories') + ' · ' +
              words(totals.collections, 'collection') + ' · ' + words(totals.videos, 'video')
            : 'Nothing here yet — this is where your child\'s videos live.';

        var children = [
            // What the TV is doing comes first: it is the question a parent opens this page to ask
            // while standing in the kitchen, and it is answered before "what do you have".
            nowPlayingCard(),
            screenHead('Your library', null, summary, addActions(null))
        ];

        if (state.versionMismatch) {
            children.push(banner('warn', 'This page is out of date',
                'Reload the page to get the version that matches your TV.', []));
        }

        children.push(unplayableBanner(categories));

        if (behind) {
            children.push(banner('info', 'Your TV does not have the latest yet',
                'It will pick the change up on its own, or you can send it now.',
                [actionButton('Update the TV now', 'send-to-tv', {}, 'btn--primary')]));
        }

        if (!categories.length) {
            children.push(emptyState('🎬', 'Your library is empty',
                'Add a YouTube video or playlist to get started.',
                [actionButton('Add to library', 'add-from-youtube', {}, 'btn--primary')]));
        } else {
            var many = categories.length > 1;
            children.push(h('ul', { class: 'tiles' }, categories.map(function (node, index) {
                return categoryTile(node, many ? { index: index, last: categories.length - 1 } : null);
            })));
        }

        // The home-screen hint comes last: it is a convenience, and a convenience must never sit
        // above the thing the parent opened the page for.
        if (homescreenHintWanted()) children.push(homescreenBanner());

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

    function screenCategory(categoryId) {
        var category = nodeById(categoryId);

        if (!category || category.nodeType !== CatalogEditor.CATEGORY) {
            return goneScreen('That category is gone');
        }

        var children = [
            screenHead(category.title, [crumb('Your library', '#/library'), crumb(category.title, null)],
                nodeMeta(category), addActions(category).concat([menuButton(category)]))
        ];

        if (category.enabled === false) {
            children.push(banner('warn', 'This category is hidden',
                'Your child cannot see it on the TV until you show it again.',
                [actionButton('Show this category', 'show-node',
                    { 'data-id': category.id }, 'btn--primary')]));
        }

        children.push(unplayableBanner([category]));
        children.push(listOf(childrenOf(category.id), category));

        return h('div', { class: 'screen' }, children);
    }

    function screenCollection(collectionId) {
        var collection = nodeById(collectionId);

        if (!collection || collection.nodeType !== CatalogEditor.SUBCATEGORY) {
            return goneScreen('That collection is gone');
        }

        var crumbs = [crumb('Your library', '#/library')];
        var parent = collection.parentId ? nodeById(collection.parentId) : null;
        if (parent) crumbs.push(crumb(parent.title, '#/shelf/' + encodeURIComponent(parent.id)));
        crumbs.push(crumb(collection.title, null));

        var children = [
            screenHead(collection.title, crumbs, nodeMeta(collection),
                addActions(collection).concat([menuButton(collection)]))
        ];

        if (collection.enabled === false) {
            children.push(banner('warn', 'This collection is hidden',
                'Your child cannot see it on the TV until you show it again.',
                [actionButton('Show this collection', 'show-node',
                    { 'data-id': collection.id }, 'btn--primary')]));
        }

        children.push(unplayableBanner([collection]));
        children.push(listOf(childrenOf(collection.id), collection));

        return h('div', { class: 'screen' }, children);
    }

    function goneScreen(title) {
        return h('div', { class: 'screen' }, [
            screenHead(title, [crumb('Library', '#/library')],
                'It may have been deleted from another phone or browser.', [
                    actionButton('Back to the library', 'go-library', {}, 'btn--primary'),
                    actionButton('Reload the library', 'reload-catalog', {})
                ])
        ]);
    }

    function listOf(items, parent) {
        if (!items.length) {
            // A collection can legitimately have nothing in the *document* while the TV shows a great
            // many videos: episodes of a playlist the TV keeps up to date itself. Saying "nothing
            // here yet" over a collection whose own heading counts them would be a lie.
            var fromTv = state.artwork.containers[parent.id];
            if (fromTv && fromTv.videoCount > 0) {
                return emptyState('📺', 'These videos come from the TV',
                    'This collection plays ' + words(fromTv.videoCount, 'video') + ' that the TV keeps up ' +
                    'to date from the playlist itself, so there is nothing to list here. Anything you ' +
                    'add sits alongside them.',
                    [actionButton('Add to library', 'add-from-youtube',
                        { 'data-parent': parent.id }, 'btn--primary')]);
            }

            return emptyState('📼', 'Nothing here yet',
                parent.nodeType === CatalogEditor.SUBCATEGORY
                    ? 'Add a YouTube video to this collection.'
                    : 'Add a playlist or video to this category.',
                [actionButton('Add to library', 'add-from-youtube',
                    { 'data-parent': parent.id }, 'btn--primary')]);
        }

        var maps = allowedSourceMaps();
        var many = items.length > 1;
        return h('ul', { class: 'panel panel--flush' }, items.map(function (node, index) {
            return itemRow(node, maps, many ? { index: index, last: items.length - 1 } : null);
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
            text: 'Continue'
        }), 'click', checkLink);

        urlInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') checkLink();
        });

        var crumbs = [crumb('Your library', '#/library')];
        var parent = parentId ? nodeById(parentId) : null;
        if (parent) crumbs.push(crumb(parent.title, routeFor(parent)));
        crumbs.push(crumb('Add to library', null));

        setTimeout(function () { urlInput.focus(); }, 40);

        return h('div', { class: 'screen' }, [
            screenHead('Add to library', crumbs,
                'Paste a YouTube link — a playlist, or one video — and choose where it goes.', null),
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
            fields.addError.textContent = humanError(result.data, 'That link could not be read.');
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
            text: 'Add to library'
        }), 'click', confirmAdd);

        var children = [
            h('h2', { class: 'screen__section-title', text: 'What did you find?' }),
            h('div', { class: 'panel' }, [
                art,
                h('div', { class: 'card__body' }, [
                    h('p', { class: 'card__title', text: resolved.title || first.title || 'This link' }),
                    h('p', { class: 'card__meta', text: facts.join(' · ') })
                ])
            ])
        ];

        if (!isVideo && !resolved.videos.length) {
            children.push(banner('warn', 'This playlist has no videos that can be added',
                'It may be empty, or every video in it may be private or unavailable.', [
                    actionButton('Try another link', 'add-from-youtube', {}, 'btn--primary')
                ]));
            return h('div', { class: 'screen' }, children);
        }

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
                h('span', { class: 'field__label', text: 'Where should it go?' }),
                destinationSelect
            ]));
        } else {
            form.push(banner('warn', 'Make a category first',
                'A video has to go inside a category or a collection. Make one, then come back to this link.', [
                    actionButton('New category', 'new-shelf', {}, 'btn--primary')
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
                toast(humanError(allowed.data, 'That source could not be allowed'), 'error');
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

    /** The one-line state a parent cares about: can my child watch this, or not? */
    function stateLine(node) {
        if (node.nodeType !== CatalogEditor.VIDEO) {
            return TYPE_WORDS[node.nodeType] + ' · ' + nodeMeta(node);
        }
        if (canPlay(node)) return 'Video · Ready to watch';
        return 'Video · Can\'t play yet — its YouTube source is not allowed for your child';
    }

    async function openItemSheet(nodeId) {
        var node = nodeById(nodeId);
        if (!node) return;

        var siblings = childrenOf(node.parentId);
        var index = siblings.findIndex(function (candidate) { return candidate.id === node.id; });
        var last = siblings.length - 1;
        var options = [];

        options.push({
            id: 'rename',
            title: 'Edit name',
            meta: 'What this is called on the TV'
        });

        options.push({
            id: 'toggle',
            title: node.enabled === false ? 'Show to my child again' : 'Hide from my child',
            meta: node.enabled === false
                ? 'It will appear on the TV again'
                : 'It stays in your library, but the TV stops showing it'
        });

        // Four ways to move, in the order a parent reaches for them: the two neighbours, then the
        // two ends. Nothing here needs a drag, and every one of them is one decision.
        options.push({ id: 'up', title: 'Move up', disabled: index <= 0 });
        options.push({ id: 'down', title: 'Move down', disabled: index < 0 || index >= last });
        options.push({ id: 'top', title: 'Move to the top', disabled: index <= 0 });
        options.push({ id: 'bottom', title: 'Move to the bottom', disabled: index < 0 || index >= last });

        if (node.nodeType !== CatalogEditor.CATEGORY) {
            options.push({
                id: 'move',
                title: 'Move to another category…',
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
            title: 'Remove from library',
            meta: node.nodeType === CatalogEditor.VIDEO
                ? 'It disappears from your child\'s library; nothing is deleted from YouTube'
                : 'Removes this and the ' + words(descendantsOf(node.id).length, 'item') + ' inside it',
            danger: true
        });

        var choice = await chooseFrom({
            title: node.title,
            body: stateLine(node),
            image: artworkFor(node) || null,
            options: options
        });

        if (choice) runItemAction(choice, node);
    }

    async function runItemAction(choice, node) {
        if (choice === 'rename') {
            var name = await askForText({
                title: 'Edit name',
                body: node.nodeType === CatalogEditor.VIDEO
                    ? 'The video itself is unchanged; only the name your child sees.'
                    : null,
                label: 'Name',
                value: node.title
            });
            if (name === null) return;
            var renamed = CatalogEditor.rename(state.session, { id: node.id, title: name });
            if (!renamed.ok) return void toast(renamed.reason, 'error');
            state.session = renamed.session;
            return void publish('Name changed');
        }

        if (choice === 'toggle') {
            var shown = node.enabled === false;
            var toggled = CatalogEditor.setEnabled(state.session, { id: node.id, enabled: shown });
            if (!toggled.ok) return void toast(toggled.reason, 'error');
            state.session = toggled.session;
            return void publish(shown ? 'Shown again' : 'Hidden from your child');
        }

        if (choice === 'up' || choice === 'down') {
            return void moveNode(node.id, choice === 'up' ? -1 : 1);
        }

        if (choice === 'top' || choice === 'bottom') {
            var siblings = childrenOf(node.parentId).filter(function (candidate) {
                return candidate.id !== node.id;
            });
            var moved = CatalogEditor.moveTo(state.session, {
                id: node.id,
                parentId: node.parentId || null,
                position: choice === 'top' ? 0 : siblings.length
            });
            if (!moved.ok) return void toast(moved.reason, 'error');
            state.session = moved.session;
            return void publish('Moved');
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
            title: 'Remove “' + node.title + '” from your library?',
            body: 'This removes it' + (inside ? ' and the ' + words(inside, 'item') + ' inside it' : '') +
                ' from your child\'s SafeTube library, on this phone and on the TV. ' +
                'It does not delete anything from YouTube.',
            confirmLabel: 'Remove',
            danger: true
        });

        if (!confirmed) return;

        var parentId = node.parentId;
        var removed = CatalogEditor.remove(state.session, { id: node.id });
        if (!removed.ok) return void toast(removed.reason, 'error');
        state.session = removed.session;

        var saved = await publish('Removed from your library');
        if (saved && !parentId) go('#/library');
    }

    // --- settings ----------------------------------------------------------------------------

    function panel(title, note, body, actions) {
        return h('section', { class: 'panel' }, [
            h('div', { class: 'panel__head' }, [
                h('h3', { class: 'panel__title', text: title }),
                actions && actions.length ? h('div', { class: 'btn-group' }, actions) : null
            ]),
            note ? h('p', { class: 'panel__note', text: note }) : null,
            body
        ]);
    }

    /** A named group of cards. Grouping is what turns a list of panels into settings. */
    function settingsGroup(title, panels) {
        return h('section', { class: 'settings-group' }, [
            h('h2', { class: 'settings-group__title', text: title }),
            h('div', { class: 'settings-group__body' }, panels)
        ]);
    }

    function screenSettings() {
        var children = [
            screenHead('Settings', null, 'Your TV, what your child may watch, and how this page looks.', [])
        ];

        children.push(settingsGroup('TV', [tvPanel()]));
        children.push(settingsGroup('Time limits', [screenTimePanel()]));

        if (state.session) {
            children.push(settingsGroup('Content', [allowedSourcesPanel()]));
        }

        children.push(settingsGroup('What has been watched', [watchHistoryPanel()]));
        children.push(settingsGroup('Appearance', [lookPanel()]));
        children.push(settingsGroup('Troubleshooting', [libraryPanel(), helpPanel()]));

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
            actionButton('Update the TV now', 'send-to-tv', {}, 'btn--primary')
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
            toast(humanError(result.data, 'The limits could not be saved'), 'error');
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

        return panel('Allowed YouTube sources', null, h('div', {}, body));
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
            toast(humanError(result.data, 'That link could not be allowed'), 'error');
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
            toast(humanError(result.data, 'That could not be removed'), 'error');
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
                        : 'Your TV is on an older version — update it now.')
            }),
            h('div', { class: 'btn-group' }, [
                actionButton('Update the TV now', 'send-to-tv', {}, 'btn--primary'),
                actionButton('Check for problems', 'check-library', {}),
                actionButton('Reload the library', 'reload-catalog', {})
            ])
        ];

        return panel('Your library', 'The categories, collections and videos you have made.', h('div', {}, body));
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
        'move-up': function (element) { moveNode(element.getAttribute('data-id'), -1); },
        'move-down': function (element) { moveNode(element.getAttribute('data-id'), 1); },
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
        'retry-status': function () { checkTvAgain(); },
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
        var isCategory = type === 'CATEGORY';
        var title = await askForText({
            title: isCategory ? 'New category' : 'New collection',
            body: isCategory
                ? 'A category is a row of videos on the TV, like “Cartoons” or “Bedtime”.'
                : 'A collection is a group of videos inside a category, like “Songs”.',
            label: 'Name',
            confirmLabel: 'Create'
        });

        if (title === null) return;

        var applied = isCategory
            ? CatalogEditor.addCategory(state.session, { title: title })
            : CatalogEditor.addSubcategory(state.session, { title: title, parentId: parentId });

        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;
        publish(isCategory ? 'New category' : 'New collection');
    }

    /** One tap of ▲ or ▼: the sibling order is the only thing that changes. */
    async function moveNode(id, offset) {
        if (!nodeById(id)) return;
        var moved = offset < 0
            ? CatalogEditor.moveUp(state.session, { id: id })
            : CatalogEditor.moveDown(state.session, { id: id });
        if (!moved.ok) return void toast(moved.reason, 'error');
        state.session = moved.session;
        publish('Moved');
    }

    async function showNode(id) {
        var applied = CatalogEditor.setEnabled(state.session, { id: id, enabled: true });
        if (!applied.ok) return void toast(applied.reason, 'error');
        state.session = applied.session;
        publish('Shown again');
    }

    /** "Try again" on the Now Playing card: ask the TV once, without waiting for the next poll. */
    async function checkTvAgain() {
        await loadStatus();
        if (state.reachable === false) toast('The TV did not answer.', 'error');
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
            toast(humanError(resolved.data, 'That video could not be read'), 'error');
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
            toast(humanError(result.data, 'That did not work'), 'error');
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
                toast(humanError(result.data, 'That file could not be read'), 'error');
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
        }, pollIntervalMs);
        retunePlayheadTicker();
    }

    function stopPolling() {
        if (statusTimer) clearInterval(statusTimer);
        statusTimer = null;
        retunePlayheadTicker();
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

        // The shell is painted before the first request, so the parent never looks at a blank page:
        // either the PIN screen, or the library with its loading state already in it.
        render();

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
