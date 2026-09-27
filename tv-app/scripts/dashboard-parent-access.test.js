/*
 * Parent access on the dashboard: signing in, the way back in, and what the page is allowed to do
 * with a credential.
 *
 * Two kinds of test live here. The first kind exercises `parent-access.js`, which is a pure model -
 * what a PIN may be, how a Recovery Code is normalised, and what to say when the TV refuses - because
 * those rules are worth testing without a browser. The second kind are guards over `app.js` itself:
 * that the PIN is never stored, never reused as a bearer token, never put in a URL, and that the
 * password-recovery flow cannot become a way to change anything but the credential.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ParentAccess = require('../app/src/main/assets/parent-access.js');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');

/** Source with comments removed, so a guard cannot be satisfied by prose. */
function code(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .map((line) => line.replace(/\/\/.*/, ''))
        .join('\n');
}

function raw(file) {
    return fs.readFileSync(path.join(assets, file), 'utf8');
}

// --- what a PIN may be ------------------------------------------------------------------------

test('a Parent PIN is exactly six digits', () => {
    assert.equal(ParentAccess.isPin('482913'), true);
    assert.equal(ParentAccess.isPin('000000'), true, 'a leading zero is a digit like any other');

    assert.equal(ParentAccess.isPin('48291'), false, 'five digits');
    assert.equal(ParentAccess.isPin('4829134'), false, 'seven digits');
    assert.equal(ParentAccess.isPin('48291a'), false, 'a letter');
    assert.equal(ParentAccess.isPin('48 913'), false, 'a space');
    assert.equal(ParentAccess.isPin(''), false);
    assert.equal(ParentAccess.isPin(null), false);
    assert.equal(ParentAccess.isPin(undefined), false);
});

test('a numeric field keeps digits and stops at six', () => {
    assert.equal(ParentAccess.digitsOnly('4a8b2'), '482');
    assert.equal(ParentAccess.digitsOnly('4829137'), '482913', 'no seventh digit');
    assert.equal(ParentAccess.digitsOnly(' 4 8 2 '), '482');
    assert.equal(ParentAccess.digitsOnly(''), '');
    assert.equal(ParentAccess.digitsOnly(null), '');
});

// --- what a Recovery Code looks like ----------------------------------------------------------

test('a Recovery Code is normalised the way the TV normalises it', () => {
    // The two implementations have to agree, or a parent would type a code that is right and be told
    // it is wrong. `RecoveryCode.normalise` on the TV does exactly this.
    assert.equal(ParentAccess.normaliseCode('8k4p-7m2q-91tx'), '8K4P7M2Q91TX');
    assert.equal(ParentAccess.normaliseCode(' 8K4P 7M2Q 91TX '), '8K4P7M2Q91TX');
    assert.equal(ParentAccess.normaliseCode('8K4P—7M2Q—91TX'), '8K4P7M2Q91TX');
    assert.equal(ParentAccess.normaliseCode(''), '');
    assert.equal(ParentAccess.normaliseCode(null), '');
});

test('a code is grouped for reading, as it is typed', () => {
    assert.equal(ParentAccess.groupCode('8K4P7M2Q91TX'), '8K4P-7M2Q-91TX');
    assert.equal(ParentAccess.groupCode('8k4p7m2q91tx'), '8K4P-7M2Q-91TX');
    assert.equal(ParentAccess.groupCode('8K4P7M2Q'), '8K4P-7M2Q', 'a partial code is grouped as far as it goes');
    assert.equal(ParentAccess.groupCode('8K4P7M2Q91TXZ'), '8K4P-7M2Q-91TX-Z', 'and never loses a character');
    assert.equal(ParentAccess.groupCode(''), '');
});

test('a code is only sent once it is long enough to be one', () => {
    assert.equal(ParentAccess.looksLikeCode('8K4P-7M2Q-91TX'), true);
    assert.equal(ParentAccess.looksLikeCode('8k4p7m2q91tx'), true);
    assert.equal(ParentAccess.looksLikeCode('8K4P-7M2Q-91T'), false, 'eleven characters');
    assert.equal(ParentAccess.looksLikeCode(''), false);
});

// --- what a parent is told when it goes wrong -------------------------------------------------

test('each refusal is a sentence a parent can act on', () => {
    assert.match(ParentAccess.describeAuthProblem(401, { error: 'Invalid PIN' }, 'sign in'), /not right/i);
    assert.match(ParentAccess.describeAuthProblem(401, { error: 'That Recovery Code is not right' }, 'recover'), /Recovery Code/i);
    assert.match(ParentAccess.describeAuthProblem(429, {}, 'sign in'), /Too many tries/i);
    assert.match(ParentAccess.describeAuthProblem(0, {}, 'sign in'), /same wifi/i);
    assert.match(ParentAccess.describeAuthProblem(503, {}, 'sign in'), /busy or offline/i);
});

test('a TV with no Parent PIN is not reported as a wrong PIN', () => {
    const said = ParentAccess.describeAuthProblem(409, { error: 'whatever' }, 'sign in');

    assert.match(said, /no Parent PIN yet/i);
    assert.doesNotMatch(said, /not right/i, 'nothing was typed wrong: the TV has nothing to sign in to');
});

test('the not-set-up screen says where setup happens', () => {
    assert.equal(ParentAccess.NOT_SET_UP.title, 'Finish setting up on your TV');
    assert.match(ParentAccess.NOT_SET_UP.body, /created on the television/i);
    assert.doesNotMatch(
        JSON.stringify(ParentAccess.NOT_SET_UP),
        /yaml|schema|api|token/i,
        'a parent reads product words, not implementation words',
    );
});

test('a server sentence is passed through only when it is really a sentence', () => {
    assert.equal(
        ParentAccess.describeAuthProblem(400, { error: 'A Parent PIN is six digits.' }, 'change'),
        'A Parent PIN is six digits.',
    );
    assert.doesNotMatch(
        ParentAccess.describeAuthProblem(500, { error: 'java.lang.IllegalStateException: boom at Foo.kt:12' }, 'change'),
        /Exception|\.kt:/,
        'a stack trace must never reach the screen',
    );
});

// --- the page never keeps a credential --------------------------------------------------------

test('the Parent PIN is never stored, and never becomes the bearer token', () => {
    const app = code('app.js');

    // The only things that go into browser storage are the session token, the theme and one dismissal
    // flag. A credential is not on that list, and nothing here may add it.
    const stored = [...app.matchAll(/localStorage\.setItem\(([^,]+),/g)].map((m) => m[1]);
    stored.forEach((key) => assert.match(key, /TOKEN_KEY|THEME_KEY|HOMESCREEN_KEY/,
        'only the session, the theme and one dismissal flag are remembered, not ' + key));
    assert.doesNotMatch(app, /localStorage\.setItem\([^)]*\bpin\b/i);
    assert.doesNotMatch(app, /sessionStorage/);

    // The PIN is sent once, to /auth, and the token is what every other request carries.
    assert.match(app, /headers\['Authorization'\] = 'Bearer ' \+ state\.token/);
    assert.doesNotMatch(app, /'Bearer ' \+ .*pin/i, 'the PIN is never a bearer token');
});

test('no credential is ever put in the address bar', () => {
    const app = code('app.js');
    const html = raw('index.html');

    assert.doesNotMatch(app, /location\.search/, 'the ?pin= sign-in path is gone');
    assert.doesNotMatch(app, /history\.replaceState/, 'and so is the code that used to scrub it');
    assert.doesNotMatch(html, /pin=/i);
});

test('the sign-in screen asks for the Parent PIN and offers the way back', () => {
    const app = code('app.js');
    const login = app.slice(app.indexOf('function screenLogin'), app.indexOf('function screenNotSetUp'));

    assert.match(login, /text: 'Parent PIN'/, 'the field is labelled for a parent');
    assert.match(login, /text: 'Sign In'/);
    assert.match(login, /text: 'Forgot PIN\?'/);
    assert.match(login, /go\('#\/recovery'\)/, 'and it goes to the recovery route');
    assert.doesNotMatch(login, /screenConnect|PIN from the TV/, 'the pairing-code wording is gone');
});

test('a TV with no Parent PIN gets its own screen instead of a form', () => {
    const app = code('app.js');
    const notSetUp = app.slice(app.indexOf('function screenNotSetUp'), app.indexOf('function screenRecovery'));

    assert.match(notSetUp, /ParentAccess\.NOT_SET_UP/);
    assert.doesNotMatch(notSetUp, /pinInput|type: 'password'/, 'there is nothing to type on such a TV');
});

// --- the way back in ---------------------------------------------------------------------------

test('forgot PIN checks the Recovery Code before asking for a new one', () => {
    const app = code('app.js');
    const recovery = app.slice(app.indexOf('function screenRecovery'), app.indexOf('function showNewRecoveryCode'));

    const verifyAt = recovery.indexOf("'/auth/recovery/verify'");
    const newPinAt = recovery.indexOf("'/auth/recovery'");
    assert.ok(verifyAt > -1 && newPinAt > -1, 'both steps call the server');
    assert.ok(verifyAt < newPinAt, 'the code is verified before a new PIN is sent');
    assert.match(recovery, /step = 'pin'/, 'and only then does the second step appear');
});

test('a Recovery Code is not a session either', () => {
    const app = code('app.js');

    // The recovery call never stores anything: a code that signed a browser in would be a permanent
    // second password the parent cannot revoke from memory.
    const recovery = app.slice(app.indexOf("'/auth/recovery/verify'"), app.indexOf('function showNewRecoveryCode'));
    assert.doesNotMatch(recovery, /rememberToken|setItem/);
    assert.match(recovery, /state\.recoveryCode = code/, 'the code is held only in memory, for the next step');
    assert.doesNotMatch(app, /localStorage\.setItem\([^)]*recovery/i);
});

test('the page says what to do when both the PIN and the code are gone', () => {
    const app = code('app.js');
    const recovery = app.slice(app.indexOf('function screenRecovery'), app.indexOf('function showNewRecoveryCode'));

    assert.match(recovery, /Don\\u2019t have your Recovery Code\?|Don’t have your Recovery Code\?/);
    assert.match(recovery, /complete SafeTube reset from the TV/i);
    // And no route of its own: the reset cannot be triggered from a browser at all.
    assert.doesNotMatch(app, /'\/reset'|'\/auth\/reset'|'\/wipe'/);
});

test('the new Recovery Code is shown once and cannot be waved away', () => {
    const app = code('app.js');
    const dialog = app.slice(app.indexOf('function showNewRecoveryCode'), app.indexOf('function parentAccessPanel'));

    assert.match(dialog, /openModal\(/);
    assert.match(dialog, /ParentAccess\.groupCode\(code\)/, 'shown in the shape it is written down in');
    assert.match(dialog, /only time it is shown/i);
    assert.match(dialog, /I\\u2019ve written it down|I’ve written it down/);
    // One way out of the dialog, through a button the parent has to read.
    assert.equal([...dialog.matchAll(/withListener\(h\('button'/g)].length, 1, 'no second dismissal path');
});

// --- changing the credential -------------------------------------------------------------------

test('changing the PIN asks for the current one and signs everybody out', () => {
    const app = code('app.js');
    const dialog = app.slice(app.indexOf('function changePinDialog'), app.indexOf('function newRecoveryCode'));

    ['Current PIN', 'New PIN', 'Confirm New PIN'].forEach((label) => {
        assert.ok(dialog.includes("text: '" + label + "'"), 'the dialog asks for ' + label);
    });
    assert.match(dialog, /apiCall\('POST', '\/auth\/pin'/);
    assert.match(dialog, /forgetSession\(\)/, 'the session this page holds is gone with the change');
    assert.match(dialog, /ParentAccess\.isPin\(|ParentAccess\.isPin\(next/, 'the new PIN is checked before it is sent');
});

test('the parent-access panel offers exactly the three operations', () => {
    const app = code('app.js');
    const panel = app.slice(app.indexOf('function parentAccessPanel'), app.indexOf('function changePinDialog'));

    assert.match(panel, /actionButton\('Change PIN', 'change-pin'/);
    assert.match(panel, /actionButton\('Generate new Recovery Code', 'new-recovery-code'/);
    assert.match(panel, /actionButton\('Sign out all sessions', 'sign-out-everywhere'/);

    // Every action is wired, and nothing on this panel can touch the library or the TV.
    const actions = ['change-pin', 'new-recovery-code', 'sign-out-everywhere'];
    actions.forEach((action) => assert.match(app, new RegExp("'" + action + "': function \\(\\) \\{")));
    assert.doesNotMatch(panel, /catalog|playlist|playback|approve/i);
});

test('signing out everywhere really means everywhere', () => {
    const app = code('app.js');
    const block = app.slice(app.indexOf('async function signOutEverywhere'), app.indexOf('function refreshAuthState'));

    assert.match(block, /apiCall\('POST', '\/auth\/sessions\/revoke'\)/);
    assert.match(block, /forgetSession\(\)/, 'including the browser that asked');
});

test('the first-run state is asked for without a session, and answered honestly', () => {
    const app = code('app.js');

    assert.match(app, /apiCall\('GET', '\/auth\/state'\)/);
    assert.match(app, /state\.authState = result\.data\.configured/);
    assert.match(app, /typeof result\.data\.configured === 'boolean'/, 'only a clear answer is believed');
    // A TV that cannot be reached is not a TV without a PIN.
    const login = app.slice(app.indexOf('function screenLogin'), app.indexOf('function screenNotSetUp'));
    assert.match(login, /state\.authState === false/, 'the not-set-up screen needs a definite answer');
});

test('the dashboard never brings back the printed PIN', () => {
    const app = code('app.js');

    ['PIN from the TV', 'PIN shown on the TV', 'screenConnect', 'getCurrentPin', 'The PIN your TV is showing']
        .forEach((gone) => assert.ok(!app.includes(gone), 'the ephemeral-PIN wording must be gone: ' + gone));
});

test('the parent-access model touches no DOM, no network and no storage', () => {
    const source = code('parent-access.js');

    ['document.', 'window.', 'fetch(', 'XMLHttpRequest', 'localStorage', 'sessionStorage', 'indexedDB']
        .forEach((forbidden) => assert.ok(!source.includes(forbidden),
            'parent-access.js must not reference ' + forbidden));
});

test('the parent-access model knows nothing about playback', () => {
    const source = code('parent-access.js');

    assert.doesNotMatch(source, /catalog|playlist|video|approve|PlaybackAuthorization/i,
        'a credential model must not be able to grant anything');
});

test('the page loads the parent-access model before the script that uses it', () => {
    const html = raw('index.html');

    assert.match(html, /<script src="parent-access\.js"><\/script>/);
    const model = html.indexOf('parent-access.js');
    const app = html.indexOf('src="app.js"');
    assert.ok(model > -1 && model < app, 'the model has to be there before app.js runs');
    assert.ok(fs.existsSync(path.join(assets, 'parent-access.js')));
});
