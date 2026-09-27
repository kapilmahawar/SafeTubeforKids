/*
 * Parent access, as a model: what a PIN may be, what a Recovery Code looks like, and what to say when
 * the TV refuses something.
 *
 * Pure on purpose - no DOM, no fetch, no storage - for the same reason `catalog-editor.js` and
 * `catalog-yaml.js` are: the rules a parent's credential has to satisfy are worth testing without a
 * browser, and the sentences a parent reads when they get it wrong are worth testing without a TV.
 *
 * It knows nothing about playback. Nothing in this file can approve a source, play a video or change
 * what a child may watch; every function here is about a person proving who they are to the dashboard.
 */
var ParentAccess = (function () {
    var PIN_LENGTH = 6;
    var RECOVERY_LENGTH = 12;
    var RECOVERY_GROUP = 4;

    /** True for exactly six digits: the shape the TV will accept as a Parent PIN. */
    function isPin(value) {
        return typeof value === 'string' && new RegExp('^[0-9]{' + PIN_LENGTH + '}$').test(value.trim());
    }

    /** Digits only, at most six - what a numeric field should actually keep as it is typed. */
    function digitsOnly(value, limit) {
        return String(value === undefined || value === null ? '' : value)
            .replace(/[^0-9]/g, '')
            .slice(0, limit === undefined ? PIN_LENGTH : limit);
    }

    /**
     * A Recovery Code as the parent typed it, tidied into the shape the TV compares against.
     *
     * Dashes, spaces and lower case are things people introduce when copying twelve characters off a
     * television screen, and none of them are part of the secret. This mirrors `RecoveryCode.normalise`
     * on the TV exactly; the two must agree, or a parent would type a code that is right and be told
     * it is wrong.
     */
    function normaliseCode(value) {
        return String(value === undefined || value === null ? '' : value)
            .trim()
            .toUpperCase()
            .replace(/[^A-Z0-9]/g, '');
    }

    /** A code laid out for reading: `8K4P7M2Q91TX` -> `8K4P-7M2Q-91TX`. */
    function groupCode(value) {
        var normalised = normaliseCode(value);
        var groups = [];
        for (var i = 0; i < normalised.length; i += RECOVERY_GROUP) {
            groups.push(normalised.slice(i, i + RECOVERY_GROUP));
        }
        return groups.join('-');
    }

    /** Long enough to bother sending. The TV is the authority on whether it is right. */
    function looksLikeCode(value) {
        return normaliseCode(value).length === RECOVERY_LENGTH;
    }

    /**
     * What the TV said, as a sentence a parent can act on.
     *
     * The status is the server's, and the mapping is deliberately one-to-one with the auth routes: a
     * 409 is "there is nothing to sign in to yet" rather than "wrong PIN", because the parent staring
     * at that screen has not typed anything wrong - their TV has simply never been set up.
     */
    function describeAuthProblem(status, data, action) {
        var server = data && typeof data.error === 'string' ? data.error : '';
        var what = action || 'sign in';

        if (status === 0) return 'Could not reach the TV. Is it on the same wifi?';
        if (status === 503) return 'The TV is busy or offline. Try again in a moment.';
        if (status === 409) return 'This TV has no Parent PIN yet. Set one up on the TV first.';
        if (status === 429) return 'Too many tries. Wait a few minutes and try again.';
        if (status === 401) {
            if (/recovery/i.test(server)) return 'That Recovery Code is not right.';
            return 'That PIN was not right.';
        }
        if (status === 400 && server) return server;

        // Anything else, including a rejection this file has never heard of, keeps the server's own
        // words when they are short enough to be a sentence rather than a stack trace.
        if (server && server.length <= 160 && !/[{}]|Exception|\.kt:/.test(server)) return server;
        return 'That did not work. Please try again.';
    }

    /** The one line a parent needs when their TV is not set up yet. */
    var NOT_SET_UP = {
        title: 'Finish setting up on your TV',
        body: 'SafeTube needs a Parent PIN, and it is created on the television. ' +
            'Open SafeTube on the TV and follow the setup steps, then come back to this page.',
        hint: 'You can reach this page from the TV\u2019s Connect Phone screen.'
    };

    var api = {
        PIN_LENGTH: PIN_LENGTH,
        RECOVERY_LENGTH: RECOVERY_LENGTH,
        isPin: isPin,
        digitsOnly: digitsOnly,
        normaliseCode: normaliseCode,
        groupCode: groupCode,
        looksLikeCode: looksLikeCode,
        describeAuthProblem: describeAuthProblem,
        NOT_SET_UP: NOT_SET_UP
    };

    return api;
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = ParentAccess;
}
