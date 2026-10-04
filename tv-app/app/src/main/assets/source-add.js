'use strict';

/**
 * What the TV's answer to "allow this source" means.
 *
 * The TV has three ways of saying a source is allowed, and every one of them is success:
 *
 *   201 Created   the source was stored by this request - the ordinary first-click answer
 *   200 OK        the source was already stored, or nothing needed creating
 *   409 Conflict  the source was already allowed
 *
 * Until W13.12 the dashboard accepted only 200 and 409. So a source the TV had *just created* (201)
 * was reported to the parent as a failure, and the source list was never refreshed - and the source
 * appeared only on the second click, because by then the same request answered 409, the one answer
 * the page knew how to read. The status check, the in-flight guard and the "we cannot tell" decision
 * live here so they can be tested without a browser.
 */
var SourceAdd = (function () {

    /** True when the TV has said the source is allowed, however it chose to say it. */
    function accepted(status) {
        return status === 200 || status === 201 || status === 409;
    }

    /** 'allowed' | 'already' | 'failed' - what the page should tell the parent. */
    function outcome(status) {
        if (status === 409) return 'already';
        if (accepted(status)) return 'allowed';
        return 'failed';
    }

    /**
     * An answer that says nothing about whether the source is stored: the request never reached the
     * TV, or the TV failed while working on it. The page must read the source list back and report
     * what is actually there, rather than reporting a failure for a write that succeeded - which is
     * the defect this module exists to end.
     */
    function uncertain(status) {
        return status === 0 || status === 503 || (status >= 500 && status <= 599);
    }

    var inFlight = false;

    /** One add at a time: a second click while the first is pending is not a second add. */
    function begin() {
        if (inFlight) return false;
        inFlight = true;
        return true;
    }

    function end() {
        inFlight = false;
    }

    function isInFlight() {
        return inFlight;
    }

    return {
        accepted: accepted,
        outcome: outcome,
        uncertain: uncertain,
        begin: begin,
        end: end,
        isInFlight: isInFlight
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = SourceAdd;
}
