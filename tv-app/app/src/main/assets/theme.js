/*
 * The theme, decided before the first paint.
 *
 * This file is loaded synchronously from <head>, before the stylesheet is applied to any content, so
 * the page never flashes the wrong palette on the way to the right one. It is deliberately tiny and
 * dependency-free: it runs on a phone on the same wifi as a TV, and it has to finish before the
 * browser would otherwise paint.
 *
 * The parent's choice wins and is remembered; with no choice on record the phone's own preference
 * decides. Both are ordinary browser facts - `localStorage` and `prefers-color-scheme` - and neither
 * is the catalog: this file never touches anything else, and `app.js` is the only place that talks to
 * the server.
 *
 * `resolveTheme` is exported for the Node test suite, so the rule is tested rather than eyeballed:
 * the whole file is inert outside a browser.
 */
(function () {
    'use strict';

    var THEME_KEY = 'safetube.theme';
    var THEMES = ['light', 'dark'];

    /** The theme to render: the remembered choice, else the phone's preference, else light. */
    function resolveTheme(stored, prefersDark) {
        if (THEMES.indexOf(stored) !== -1) return stored;
        return prefersDark ? 'dark' : 'light';
    }

    /** Whether the theme on screen came from the parent or from the phone. */
    function themeSource(stored) {
        return THEMES.indexOf(stored) !== -1 ? 'chosen' : 'system';
    }

    function readStoredTheme() {
        try {
            return window.localStorage.getItem(THEME_KEY);
        } catch (error) {
            // A browser that refuses storage (private mode) still gets a usable page.
            return null;
        }
    }

    function systemPrefersDark() {
        return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    }

    if (typeof document !== 'undefined' && document.documentElement) {
        var stored = readStoredTheme();
        var theme = resolveTheme(stored, systemPrefersDark());

        document.documentElement.setAttribute('data-theme', theme);
        document.documentElement.setAttribute('data-theme-source', themeSource(stored));

        // The browser chrome on a phone follows the page, so the address bar is not a bright band
        // above a dark screen.
        var meta = document.querySelector('meta[name="theme-color"]');
        if (meta) meta.setAttribute('content', theme === 'dark' ? '#0f1216' : '#ffffff');
    }

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = {
            THEME_KEY: THEME_KEY,
            THEMES: THEMES,
            resolveTheme: resolveTheme,
            themeSource: themeSource,
        };
    }
})();
