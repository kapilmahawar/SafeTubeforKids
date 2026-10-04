'use strict';

/*
 * What LEFT and RIGHT mean is a property of the code's *order*, and only a source check can hold it.
 *
 * The player deliberately gives those two keys three different meanings:
 *
 *   the fullscreen surface   LEFT/RIGHT are SeekBackward / SeekForward
 *   the settings row         LEFT/RIGHT move between the menu buttons
 *   the transport row        LEFT/RIGHT are returned *unconsumed*, so the explicit transport focus
 *                            graph walks Previous - Rewind - Play/Pause - Forward - Next
 *
 * The last one is the fragile one. If either row's branch moved below the seek handling - or was deleted
 * as "dead code" because the focus graph seems to do the work - LEFT/RIGHT would become a global seek and
 * a child on the transport row would jump the video instead of moving along it. The unit test beside this
 * (PlayerKeysTest) can only reach the mapping itself; the branches live in a composable with no unit-test
 * harness. So this check reads the source and asserts the structure and the order.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const mainDir = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'tv', 'safetubeforkids', 'app');
const screenFile = path.join(mainDir, 'ui', 'player', 'TvPlayerScreen.kt');
const controllerFile = path.join(mainDir, 'playback', 'PlaybackController.kt');

/** Source with comments removed, so a guard cannot be satisfied by prose. */
function code(file) {
    return fs.readFileSync(file, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*$/, ''))
        .join('\n');
}

const screen = code(screenFile);
const controller = code(controllerFile);

test('the surface meaning of LEFT and RIGHT is the seek mapping', () => {
    const keys = controller.slice(controller.indexOf('object PlaybackKeys'));
    const backward = keys.slice(keys.indexOf('KEYCODE_DPAD_LEFT'), keys.indexOf('KEYCODE_DPAD_LEFT') + 120);
    const forward = keys.slice(keys.indexOf('KEYCODE_DPAD_RIGHT'), keys.indexOf('KEYCODE_DPAD_RIGHT') + 120);
    assert.match(backward, /Action\.SeekBackward/, 'DPAD_LEFT must mean SeekBackward on the surface');
    assert.match(forward, /Action\.SeekForward/, 'DPAD_RIGHT must mean SeekForward on the surface');
    assert.match(keys, /KEYCODE_MEDIA_REWIND[\s\S]{0,80}SeekBackward/, 'the media keys must agree');
    assert.match(keys, /KEYCODE_MEDIA_FAST_FORWARD[\s\S]{0,80}SeekForward/, 'the media keys must agree');
});

test('the settings row keeps its own LEFT and RIGHT', () => {
    const at = screen.indexOf('if (buttonRowActive) {');
    assert.ok(at > -1, 'the settings-row branch is gone');
    const branch = screen.slice(at, at + 1600);
    assert.match(
        branch, /KEYCODE_DPAD_LEFT[\s\S]{0,220}buttonIndex/, 
        'LEFT in the settings row must move between the menu buttons',
    );
    assert.match(
        branch, /KEYCODE_DPAD_RIGHT[\s\S]{0,220}buttonIndex/,
        'RIGHT in the settings row must move between the menu buttons',
    );
});

test('the transport row leaves LEFT and RIGHT to the focus graph', () => {
    const at = screen.indexOf('if (transportFocused) {');
    assert.ok(at > -1, 'the transport-row branch is gone');
    const branch = screen.slice(at, at + 2000);
    assert.match(
        branch, /KEYCODE_DPAD_LEFT[\s\S]{0,120}KEYCODE_DPAD_RIGHT[\s\S]{0,120}return@onKeyEvent false/,
        'inside the transport row LEFT and RIGHT must be returned unconsumed',
    );
});

test('both rows are handled before the seek mapping is consulted', () => {
    const settingsAt = screen.indexOf('if (buttonRowActive)');
    const transportAt = screen.indexOf('if (transportFocused)');
    const seekAt = screen.indexOf('PlaybackKeys.actionOf(');
    assert.ok(seekAt > -1, 'the player no longer consults PlaybackKeys');
    assert.ok(
        settingsAt > -1 && settingsAt < seekAt,
        'the settings row must be asked before the surface seek handler',
    );
    assert.ok(
        transportAt > -1 && transportAt < seekAt,
        'the transport row must be asked before the surface seek handler, or LEFT/RIGHT become a global seek',
    );
});
