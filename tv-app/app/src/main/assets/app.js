(function() {
    'use strict';

    // --- Environment detection ---
    // On relay: URL is /tv/{tvId}/... → tvId is set, API_BASE is /tv/{tvId}/api
    // On local: URL is / → tvId is null, API_BASE is ''
    function extractTvId() {
        var parts = window.location.pathname.split('/');
        if (parts.length >= 3 && parts[1] === 'tv') {
            return parts[2];
        }
        return null;
    }

    function extractApiBase() {
        var tvId = extractTvId();
        if (!tvId) return '';
        return '/tv/' + tvId + '/api';
    }

    function extractPin() {
        var params = new URLSearchParams(window.location.search);
        return params.get('pin');
    }

    var tvId = extractTvId();
    var isRelay = !!tvId;
    var API_BASE = extractApiBase();
    var STORAGE_KEY = tvId ? 'kw_token_' + tvId : 'kw_token';
    var sessionToken = localStorage.getItem(STORAGE_KEY);
    var statusInterval = null;
    var isCurrentlyPlaying = false;
    var offlineRetryInterval = null;
    var tvIsOffline = false;

    // Export functions for testing
    if (typeof window !== 'undefined') {
        window._kw = {
            extractTvId: extractTvId,
            extractApiBase: extractApiBase,
            extractPin: extractPin
        };
    }

    // DOM refs
    var authScreen = document.getElementById('auth-screen');
    var dashboard = document.getElementById('dashboard');
    var pinForm = document.getElementById('pin-form');
    var pinInput = document.getElementById('pin-input');
    var authError = document.getElementById('auth-error');
    var playlistForm = document.getElementById('playlist-form');
    var playlistUrl = document.getElementById('playlist-url');
    var playlistError = document.getElementById('playlist-error');
    var playlistList = document.getElementById('playlist-list');
    var recentList = document.getElementById('recent-list');
    var nowPlaying = document.getElementById('now-playing');
    var npTitle = document.getElementById('np-title');
    var npPlaylistTitle = document.getElementById('np-playlist-title');
    var npElapsed = document.getElementById('np-elapsed');
    var npDuration = document.getElementById('np-duration');
    var npProgressFill = document.getElementById('np-progress-fill');
    var npStopBtn = document.getElementById('np-stop-btn');
    var npPauseBtn = document.getElementById('np-pause-btn');
    var npNextBtn = document.getElementById('np-next-btn');
    var statVideos = document.getElementById('stat-videos');
    var statTime = document.getElementById('stat-time');
    var offlineBanner = document.getElementById('offline-banner');
    var versionBanner = document.getElementById('version-banner');
    var localNotice = document.getElementById('local-notice');

    // Hide local-notice on relay
    if (isRelay && localNotice) {
        localNotice.classList.add('hidden');
    }

    function authHeaders() {
        return { 'Authorization': 'Bearer ' + sessionToken, 'Content-Type': 'application/json' };
    }

    // --- Offline handling (relay only, but harmless on local) ---
    function showOffline() {
        if (offlineBanner) offlineBanner.classList.remove('hidden');
        tvIsOffline = true;
        if (!offlineRetryInterval) {
            offlineRetryInterval = setInterval(function() {
                checkOnline();
            }, 30000);
        }
    }

    function hideOffline() {
        if (offlineBanner) offlineBanner.classList.add('hidden');
        tvIsOffline = false;
        if (offlineRetryInterval) {
            clearInterval(offlineRetryInterval);
            offlineRetryInterval = null;
        }
    }

    function showVersionMismatch() {
        if (versionBanner) versionBanner.classList.remove('hidden');
    }

    async function checkOnline() {
        try {
            var resp = await fetch(API_BASE + '/status', { headers: authHeaders() });
            if (resp.status !== 503) {
                hideOffline();
                loadDashboard();
            }
        } catch (err) {
            // Still offline
        }
    }

    // --- API call with offline + auth handling ---
    async function apiCall(method, path, body) {
        var opts = { method: method, headers: authHeaders() };
        if (body) opts.body = JSON.stringify(body);
        try {
            var resp = await fetch(API_BASE + path, opts);
            if (resp.status === 503) {
                showOffline();
                return { status: 503, data: { error: 'TV is offline' } };
            }
            if (tvIsOffline) hideOffline();
            if (resp.status === 401) {
                logout();
                return { status: 401, data: { error: 'Session expired' } };
            }
            var data = await resp.json();
            return { status: resp.status, data: data };
        } catch (err) {
            if (isRelay) {
                showOffline();
                return { status: 503, data: { error: 'Connection failed' } };
            }
            return { status: 0, data: { error: 'Connection failed' } };
        }
    }

    function formatTime(totalSec) {
        var mins = Math.floor(totalSec / 60);
        var secs = totalSec % 60;
        return mins + ':' + (secs < 10 ? '0' : '') + secs;
    }

    // --- Token refresh ---
    async function refreshToken() {
        if (!sessionToken) return false;
        try {
            var resp = await fetch(API_BASE + '/auth/refresh', {
                method: 'POST',
                headers: authHeaders()
            });
            if (resp.status === 503) {
                showOffline();
                return true; // Token might still be valid, TV just offline
            }
            if (resp.ok) {
                var data = await resp.json();
                if (data.token) {
                    sessionToken = data.token;
                    localStorage.setItem(STORAGE_KEY, sessionToken);
                    return true;
                }
            }
            return false;
        } catch (err) {
            if (isRelay) {
                showOffline();
                return true; // Network error, token might still be valid
            }
            return false;
        }
    }

    // --- Version check ---
    async function checkVersion() {
        try {
            var result = await apiCall('GET', '/status');
            if (result.status === 200 && result.data.protocolVersion) {
                if (result.data.protocolVersion !== 1) {
                    showVersionMismatch();
                }
            }
        } catch (err) {
            // Version check is best-effort
        }
    }

    // --- Auth ---
    async function submitPin(pin) {
        if (!pin) return;
        authError.classList.add('hidden');

        try {
            var resp = await fetch(API_BASE + '/auth', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ pin: pin })
            });

            if (resp.status === 503) {
                showOffline();
                authError.textContent = 'TV is offline';
                authError.classList.remove('hidden');
                return;
            }

            var data = await resp.json();

            if (resp.ok && data.token) {
                sessionToken = data.token;
                localStorage.setItem(STORAGE_KEY, sessionToken);
                // Strip secret and pin from URL for security
                if (window.history && window.history.replaceState) {
                    var cleanUrl = window.location.pathname;
                    window.history.replaceState({}, document.title, cleanUrl);
                }
                authScreen.classList.add('hidden');
                dashboard.classList.remove('hidden');
                loadDashboard();
            } else {
                authError.textContent = data.error || 'Invalid PIN';
                authError.classList.remove('hidden');
            }
        } catch (err) {
            authError.textContent = 'Connection failed';
            authError.classList.remove('hidden');
        }
    }

    pinForm.addEventListener('submit', function(e) {
        e.preventDefault();
        submitPin(pinInput.value.trim());
    });

    // --- Playlists ---
    playlistForm.addEventListener('submit', async function(e) {
        e.preventDefault();
        playlistError.classList.add('hidden');
        var url = playlistUrl.value.trim();
        if (!url) return;

        try {
            var result = await apiCall('POST', '/playlists', { url: url });
            if (result.status === 201) {
                playlistUrl.value = '';
                loadPlaylists();
            } else {
                playlistError.textContent = result.data.error || 'Failed to add playlist';
                playlistError.classList.remove('hidden');
            }
        } catch (err) {
            playlistError.textContent = 'Connection failed';
            playlistError.classList.remove('hidden');
        }
    });

    async function deletePlaylist(id) {
        if (!confirm('Remove this playlist?')) return;
        await apiCall('DELETE', '/playlists/' + id);
        loadPlaylists();
    }

    async function loadPlaylists() {
        try {
            var result = await apiCall('GET', '/playlists');
            if (result.status === 200) {
                playlistList.innerHTML = '';
                result.data.forEach(function(pl) {
                    var li = document.createElement('li');
                    var label = escapeHtml(pl.displayName);
                    if (pl.videoCount > 0) label += ' \u2014 ' + pl.videoCount + ' videos';
                    li.innerHTML = '<span>' + label + '</span>';
                    var btn = document.createElement('button');
                    btn.className = 'delete-btn';
                    btn.textContent = 'Remove';
                    btn.onclick = function() { deletePlaylist(pl.id); };
                    li.appendChild(btn);
                    playlistList.appendChild(li);
                });
            }
        } catch (err) {
            console.error('Load playlists failed:', err);
        }
    }

    // --- Export / import the approved library ---
    // Export downloads what the TV currently has approved; import replays such a file onto this
    // TV. The file is posted verbatim (not re-encoded) because the server parses the payload it
    // is given and reports per-source outcomes.
    var exportButton = document.getElementById('export-sources');
    var importInput = document.getElementById('import-sources');
    var transferStatus = document.getElementById('transfer-status');

    function showTransfer(message, isError) {
        transferStatus.textContent = message;
        transferStatus.classList.remove('hidden');
        transferStatus.style.color = isError ? '#c0392b' : '';
    }

    exportButton.addEventListener('click', async function() {
        try {
            var result = await apiCall('GET', '/sources/export');
            if (result.status !== 200) {
                showTransfer(result.data.error || 'Export failed', true);
                return;
            }
            var blob = new Blob([JSON.stringify(result.data, null, 2)], { type: 'application/json' });
            var url = URL.createObjectURL(blob);
            var link = document.createElement('a');
            link.href = url;
            link.download = 'safetube-for-kids-sources.json';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);
            showTransfer('Exported ' + result.data.sources.length + ' source(s).', false);
        } catch (err) {
            showTransfer('Export failed', true);
        }
    });

    importInput.addEventListener('change', async function() {
        var file = importInput.files && importInput.files[0];
        if (!file) return;
        try {
            var text = await file.text();
            var resp = await fetch(API_BASE + '/sources/import', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
                body: text
            });
            var summary = await resp.json();
            if (resp.status !== 200) {
                showTransfer(summary.error || 'Import failed', true);
                return;
            }
            var failed = (summary.failed || []).length;
            showTransfer('Imported ' + summary.added.length + ', skipped ' + summary.skipped.length +
                (failed ? ', failed ' + failed : '') + '.', failed > 0);
            loadPlaylists();
        } catch (err) {
            showTransfer('Import failed', true);
        } finally {
            importInput.value = '';
        }
    });

    // --- Stats ---
    async function loadStats() {
        try {
            var result = await apiCall('GET', '/stats');
            if (result.status === 200) {
                statVideos.textContent = result.data.totalEventsToday;
                var mins = Math.round(result.data.totalWatchTimeToday / 60);
                statTime.textContent = mins + 'm';
            }
        } catch (err) {
            console.error('Load stats failed:', err);
        }
    }

    async function loadRecent() {
        try {
            var result = await apiCall('GET', '/stats/recent');
            if (result.status === 200) {
                recentList.innerHTML = '';
                result.data.slice(0, 10).forEach(function(evt) {
                    var li = document.createElement('li');
                    var mins = Math.round(evt.durationSec / 60);
                    var label = evt.title ? escapeHtml(evt.title) : escapeHtml(evt.videoId);
                    li.innerHTML = '<span>' + label + '</span><span>' + mins + 'm</span>';
                    recentList.appendChild(li);
                });
            }
        } catch (err) {
            console.error('Load recent failed:', err);
        }
    }

    // --- Now Playing ---
    async function loadStatus() {
        try {
            var result = await apiCall('GET', '/status');
            if (result.status === 200 && result.data.currentlyPlaying) {
                var np = result.data.currentlyPlaying;
                nowPlaying.classList.remove('hidden');
                npTitle.textContent = np.title || np.videoId;
                npPlaylistTitle.textContent = np.playlistTitle || '';
                var npThumbnail = document.getElementById('np-thumbnail');
                npThumbnail.src = 'https://img.youtube.com/vi/' + np.videoId + '/mqdefault.jpg';
                npThumbnail.alt = np.title || np.videoId;
                npElapsed.textContent = formatTime(np.elapsedSec || 0);
                npDuration.textContent = formatTime(np.durationSec || 0);
                npPauseBtn.textContent = np.playing ? 'Pause' : 'Play';

                var pct = 0;
                if (np.durationSec > 0) {
                    pct = Math.min(100, Math.round((np.elapsedSec / np.durationSec) * 100));
                }
                npProgressFill.style.width = pct + '%';

                if (!isCurrentlyPlaying) {
                    isCurrentlyPlaying = true;
                    setPollingRate(30000);
                }
            } else {
                nowPlaying.classList.add('hidden');
                var npThumbnailHide = document.getElementById('np-thumbnail');
                npThumbnailHide.src = '';
                npThumbnailHide.alt = '';
                if (isCurrentlyPlaying) {
                    isCurrentlyPlaying = false;
                    setPollingRate(120000);
                    loadStats();
                    loadRecent();
                }
            }
        } catch (err) {
            console.error('Load status failed:', err);
        }
    }

    // Playback controls
    npStopBtn.addEventListener('click', function() {
        apiCall('POST', '/playback/stop');
    });

    npPauseBtn.addEventListener('click', function() {
        apiCall('POST', '/playback/pause');
    });

    npNextBtn.addEventListener('click', function() {
        apiCall('POST', '/playback/skip');
    });

    function setPollingRate(ms) {
        if (statusInterval) clearInterval(statusInterval);
        statusInterval = setInterval(function() {
            loadStatus();
            loadTimeLimits();
        }, ms);
    }

    // --- Screen Time ---

    var currentlyLocked = false;

    async function loadTimeLimits() {
        try {
            var result = await apiCall('GET', '/time-limits');
            if (result.status !== 200) return;
            var d = result.data;

            // Status badge
            var badge = document.getElementById('st-status-badge');
            badge.className = 'badge st-badge-' + d.currentStatus;
            badge.textContent = d.currentStatus === 'allowed' ? 'Allowed'
                : d.currentStatus === 'warning' ? 'Warning'
                : 'Blocked';

            // Lock reason
            var reasonEl = document.getElementById('st-lock-reason');
            if (d.lockReason) {
                var reasonText = d.lockReason === 'daily_limit' ? 'Daily limit reached'
                    : d.lockReason === 'bedtime' ? 'Bedtime'
                    : 'Manually locked';
                reasonEl.textContent = reasonText;
                reasonEl.classList.remove('hidden');
            } else {
                reasonEl.classList.add('hidden');
            }

            // Usage bar
            var bar = document.getElementById('st-usage-bar');
            var label = document.getElementById('st-usage-label');
            var usedMin = d.todayUsedMin || 0;

            if (d.todayLimitMin != null) {
                var effectiveLimit = d.todayLimitMin + (d.todayBonusMin || 0);
                var pct = Math.min(100, Math.round((usedMin / effectiveLimit) * 100));
                bar.style.width = pct + '%';
                bar.className = 'st-usage-bar' + (d.currentStatus === 'blocked' ? ' st-bar-blocked' : d.currentStatus === 'warning' ? ' st-bar-warning' : '');
                label.textContent = usedMin + 'm / ' + effectiveLimit + 'm' + (d.todayBonusMin > 0 ? ' (+' + d.todayBonusMin + 'm bonus)' : '');
            } else {
                bar.style.width = '0%';
                label.textContent = usedMin + 'm / No limit';
            }

            // Lock button
            var lockBtn = document.getElementById('st-lock-btn');
            currentlyLocked = d.manuallyLocked;
            if (d.manuallyLocked) {
                lockBtn.textContent = 'Unlock TV';
                lockBtn.className = 'st-unlock';
                lockBtn.id = 'st-lock-btn';
            } else {
                lockBtn.textContent = 'Lock TV';
                lockBtn.className = '';
                lockBtn.id = 'st-lock-btn';
            }

            // Time request banner
            var requestEl = document.getElementById('st-time-request');
            if (d.hasTimeRequest) {
                requestEl.classList.remove('hidden');
            } else {
                requestEl.classList.add('hidden');
            }
        } catch (err) {
            console.error('Load time limits failed:', err);
        }
    }

    window.toggleLock = async function() {
        var locked = !currentlyLocked;
        await apiCall('POST', '/time-limits/lock', { locked: locked });
        loadTimeLimits();
    };

    window.grantBonusTime = async function(minutes) {
        await apiCall('POST', '/time-limits/bonus', { minutes: minutes });
        loadTimeLimits();
    };

    // Edit limits modal
    window.openEditLimits = async function() {
        var result = await apiCall('GET', '/time-limits');
        if (result.status !== 200) return;
        var d = result.data;

        var limitEnabled = document.getElementById('edit-limit-enabled');
        var limitMinutes = document.getElementById('edit-limit-minutes');
        var limitRow = document.getElementById('edit-limit-input-row');
        var bedtimeEnabled = document.getElementById('edit-bedtime-enabled');
        var bedtimeStart = document.getElementById('edit-bedtime-start');
        var bedtimeEnd = document.getElementById('edit-bedtime-end');
        var bedtimeRow = document.getElementById('edit-bedtime-input-row');

        var hasLimit = d.todayLimitMin != null;
        limitEnabled.checked = hasLimit;
        limitRow.classList.toggle('hidden', !hasLimit);
        if (hasLimit) limitMinutes.value = d.todayLimitMin;

        var hasBedtime = d.bedtime != null;
        bedtimeEnabled.checked = hasBedtime;
        bedtimeRow.classList.toggle('hidden', !hasBedtime);
        if (hasBedtime) {
            bedtimeStart.value = d.bedtime.start;
            bedtimeEnd.value = d.bedtime.end;
        }

        limitEnabled.onchange = function() { limitRow.classList.toggle('hidden', !limitEnabled.checked); };
        bedtimeEnabled.onchange = function() { bedtimeRow.classList.toggle('hidden', !bedtimeEnabled.checked); };

        document.getElementById('edit-limits-modal').classList.remove('hidden');
    };

    window.saveLimits = async function() {
        var limitEnabled = document.getElementById('edit-limit-enabled').checked;
        var bedtimeEnabled = document.getElementById('edit-bedtime-enabled').checked;
        var body = {};

        if (limitEnabled) {
            var mins = parseInt(document.getElementById('edit-limit-minutes').value) || 120;
            body.dailyLimits = {
                monday: mins, tuesday: mins, wednesday: mins,
                thursday: mins, friday: mins, saturday: mins, sunday: mins
            };
        } else {
            body.dailyLimits = {};
        }

        if (bedtimeEnabled) {
            var start = document.getElementById('edit-bedtime-start').value;
            var end = document.getElementById('edit-bedtime-end').value;
            var startParts = start.split(':');
            var endParts = end.split(':');
            body.bedtimeStartMin = parseInt(startParts[0]) * 60 + parseInt(startParts[1]);
            body.bedtimeEndMin = parseInt(endParts[0]) * 60 + parseInt(endParts[1]);
        } else {
            body.bedtimeStartMin = -1;
            body.bedtimeEndMin = -1;
        }

        await apiCall('PUT', '/time-limits', body);
        closeEditLimits();
        loadTimeLimits();
    };

    window.closeEditLimits = function() {
        document.getElementById('edit-limits-modal').classList.add('hidden');
    };

    // --- Version footer + feedback ---
    var footerVersion = document.getElementById('footer-version');
    var updateBadge = document.getElementById('update-badge');
    var feedbackLink = document.getElementById('feedback-link');
    var crashSection = document.getElementById('crash-section');
    var crashLog = document.getElementById('crash-log');
    var copyCrashBtn = document.getElementById('copy-crash');

    function updateVersionFooter(version) {
        if (footerVersion) footerVersion.textContent = 'v' + version;
        if (feedbackLink) {
            feedbackLink.href = 'mailto:hello@parentapproved.tv?subject='
                + encodeURIComponent('[SafeTube v' + version + '] Feedback')
                + '&body=' + encodeURIComponent('Device: \nIssue: \n\n');
        }
    }

    async function checkUpdateAvailable(version) {
        try {
            var resp = await fetch('https://parentapproved.tv/version.json');
            if (resp.ok) {
                var data = await resp.json();
                if (data.latestCode && data.latest) {
                    var current = parseInt(version.replace(/[^0-9]/g, ''));
                    if (data.latestCode > current && updateBadge) {
                        updateBadge.textContent = 'Update: v' + data.latest;
                        updateBadge.classList.remove('hidden');
                    }
                }
            }
        } catch (err) {
            // Best-effort
        }
    }

    async function loadCrashLog() {
        try {
            var result = await apiCall('GET', '/crash-log');
            if (result.status === 200 && result.data.hasCrash) {
                if (crashSection) crashSection.classList.remove('hidden');
                if (crashLog) crashLog.textContent = result.data.log;
            }
        } catch (err) {
            // Best-effort
        }
    }

    if (copyCrashBtn) {
        copyCrashBtn.addEventListener('click', function() {
            var text = crashLog ? crashLog.textContent : '';
            navigator.clipboard.writeText(text).then(function() {
                copyCrashBtn.textContent = 'Copied!';
                setTimeout(function() { copyCrashBtn.textContent = 'Copy to clipboard'; }, 2000);
            });
        });
    }

    // --- Kiosk / Apps management ---

    async function loadKioskConfig() {
        try {
            var result = await apiCall('GET', '/apps/kiosk');
            if (result.status !== 200) return;
            var data = result.data;

            var section = document.getElementById('kiosk-section');
            section.classList.remove('hidden');

            var badge = document.getElementById('kiosk-status-badge');
            var controls = document.getElementById('kiosk-controls');
            var setupHint = document.getElementById('kiosk-setup-hint');
            var toggleBtn = document.getElementById('kiosk-toggle-btn');
            var enforceCheck = document.getElementById('kiosk-enforce-time');
            var deviceOwnerStatus = document.getElementById('kiosk-device-owner-status');

            setupHint.classList.add('hidden');
            controls.classList.remove('hidden');
            deviceOwnerStatus.textContent = data.isDeviceOwner ? '' : 'Launcher mode (no device owner)';

            if (data.kioskEnabled) {
                badge.textContent = 'Active';
                badge.className = 'badge st-badge-warning';
                toggleBtn.textContent = 'Disable Kiosk Mode';
                toggleBtn.className = 'st-lock-btn-locked';
            } else {
                badge.textContent = 'Off';
                badge.className = 'badge st-badge-allowed';
                toggleBtn.textContent = 'Enable Kiosk Mode';
                toggleBtn.className = '';
            }
            enforceCheck.checked = data.enforceTimeLimitsOnAllApps;

            loadAppsList();
        } catch (err) {
            console.error('Failed to load kiosk config', err);
        }
    }

    async function loadAppsList() {
        try {
            var result = await apiCall('GET', '/apps');
            if (result.status !== 200) return;
            var apps = result.data;

            var card = document.getElementById('apps-list-card');
            card.classList.remove('hidden');
            var list = document.getElementById('apps-list');
            list.innerHTML = '';

            apps.forEach(function(app) {
                var li = document.createElement('li');
                li.className = 'app-item';
                var label = document.createElement('label');
                label.className = 'app-label';
                var checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.checked = app.whitelisted;
                checkbox.onchange = function() {
                    toggleAppWhitelist(app.packageName, checkbox.checked);
                };
                var nameSpan = document.createElement('span');
                nameSpan.className = 'app-name';
                nameSpan.textContent = app.displayName;
                var pkgSpan = document.createElement('span');
                pkgSpan.className = 'app-pkg';
                pkgSpan.textContent = app.packageName;
                label.appendChild(checkbox);
                label.appendChild(nameSpan);
                li.appendChild(label);
                li.appendChild(pkgSpan);
                list.appendChild(li);
            });
        } catch (err) {
            console.error('Failed to load apps list', err);
        }
    }

    window.toggleKiosk = async function() {
        try {
            var result = await apiCall('GET', '/apps/kiosk');
            if (result.status !== 200) return;
            var currentEnabled = result.data.kioskEnabled;
            var enforceTime = document.getElementById('kiosk-enforce-time').checked;

            await apiCall('POST', '/apps/kiosk', {
                enabled: !currentEnabled,
                enforceTimeLimitsOnAllApps: enforceTime,
            });
            loadKioskConfig();
        } catch (err) {
            console.error('Failed to toggle kiosk', err);
        }
    };

    window.toggleAppWhitelist = async function(packageName, whitelisted) {
        try {
            await apiCall('PUT', '/apps/whitelist', {
                packageName: packageName,
                whitelisted: whitelisted,
            });
        } catch (err) {
            console.error('Failed to toggle app whitelist', err);
        }
    };

    window.updateKioskEnforceTime = async function() {
        var enforce = document.getElementById('kiosk-enforce-time').checked;
        try {
            var result = await apiCall('GET', '/apps/kiosk');
            if (result.status !== 200) return;
            if (result.data.kioskEnabled) {
                await apiCall('POST', '/apps/kiosk', {
                    enabled: true,
                    enforceTimeLimitsOnAllApps: enforce,
                });
            }
        } catch (err) {
            console.error('Failed to update enforce time', err);
        }
    };

    // --- Catalog editor ---
    //
    // The tree comes from the TV's own server and the edits live here until Save publishes them as one
    // document. Nothing about the catalog is written to localStorage or any other browser storage: the
    // server's document is the authoritative catalog, and a browser holding its own copy could show a
    // parent a tree the TV is not showing. The consequence - a refresh discards unsaved edits - is
    // stated in the UI rather than hidden.
    //
    // The model (every mutation, and the save/reload conversation with the server) lives in
    // catalog-editor.js, which touches no DOM. This file only moves between that model and the page.

    var editorSession = null;
    var editorConflict = null;   // {serverVersion} while a save is blocked by a stale working copy
    var selectedNodeId = null;
    var editorNewId = null;      // kept so a reload keeps generating ids the same way

    function catalogError(message) {
        var box = document.getElementById('catalog-error');
        if (!box) return;
        if (!message) {
            box.classList.add('hidden');
            box.textContent = '';
            return;
        }
        box.textContent = message;
        box.classList.remove('hidden');
    }

    function setCatalogStatus(kind, message) {
        var el = document.getElementById('catalog-status');
        if (!el) return;
        el.className = 'catalog-status status-' + kind;
        el.textContent = message;
    }

    function selectedNode() {
        return editorSession && selectedNodeId ? CatalogEditor.nodeById(editorSession, selectedNodeId) : null;
    }

    /** Which of the four states the parent needs to be able to tell apart. */
    function refreshCatalogNotices() {
        var unsaved = document.getElementById('catalog-unsaved');
        var saveBtn = document.getElementById('catalog-save-btn');
        var dirty = !!(editorSession && CatalogEditor.isDirty(editorSession));

        if (saveBtn) saveBtn.disabled = !editorSession || !!editorConflict || !dirty;
        if (!unsaved) return;

        if (editorConflict) {
            unsaved.textContent = 'Your unsaved changes were not applied: the catalog changed on the server. ' +
                'Reload to take the server version (which discards what is here), then edit and save again.';
            unsaved.classList.remove('hidden');
        } else if (dirty) {
            unsaved.textContent = 'Unsaved changes — they exist only in this browser. Press Save to publish ' +
                'them; a page refresh would discard them.';
            unsaved.classList.remove('hidden');
        } else {
            unsaved.classList.add('hidden');
        }
    }

    function renderCatalog() {
        var container = document.getElementById('catalog-tree');
        var meta = document.getElementById('catalog-meta');
        if (!container) return;

        if (!editorSession) {
            container.innerHTML = '';
            if (meta) meta.textContent = 'Not available';
            refreshCatalogNotices();
            return;
        }

        container.innerHTML = CatalogTree.render(editorSession.nodes, { selectedId: selectedNodeId });
        var counts = CatalogTree.summary(editorSession.nodes);
        if (meta) {
            meta.textContent = 'Version ' + editorSession.catalogVersion + ' — ' + counts.total + ' nodes (' +
                counts.categories + ' categories, ' + counts.subcategories + ' subcategories, ' +
                counts.videos + ' videos' + (counts.disabled ? ', ' + counts.disabled + ' disabled' : '') + ')';
        }
        renderCatalogPanel();
        refreshAddForm();
        refreshCatalogNotices();
    }

    function renderCatalogPanel() {
        var panel = document.getElementById('catalog-panel');
        var empty = document.getElementById('catalog-panel-empty');
        if (!panel || !empty) return;

        var node = selectedNode();
        if (!node) {
            panel.classList.add('hidden');
            empty.classList.remove('hidden');
            return;
        }
        panel.classList.remove('hidden');
        empty.classList.add('hidden');

        document.getElementById('panel-type').textContent = node.nodeType;
        document.getElementById('panel-type').className = 'catalog-node-type catalog-type-' + node.nodeType.toLowerCase();
        document.getElementById('panel-id').textContent = node.id;
        document.getElementById('panel-title').value = node.title;
        document.getElementById('panel-enabled').checked = node.enabled !== false;

        var detail = [];
        if (node.youtubeVideoId) detail.push('video ' + node.youtubeVideoId);
        if (node.youtubePlaylistId) detail.push('playlist ' + node.youtubePlaylistId);
        detail.push('position #' + node.position);
        document.getElementById('panel-detail').textContent = detail.join(' · ');

        // A video keeps the name it was added with; the parent renames shelves and subcategories.
        var renameHint = document.getElementById('panel-rename-hint');
        var titleInput = document.getElementById('panel-title');
        var renameable = node.nodeType !== 'VIDEO';
        titleInput.disabled = !renameable;
        renameHint.textContent = renameable
            ? ''
            : 'A video keeps its name; rename the shelf or subcategory it sits in.';

        // Moving is only offered where it is possible: a shelf always sits at the top level.
        var select = document.getElementById('panel-parent');
        var options = CatalogEditor.validParentsFor(editorSession, node.nodeType).filter(function (parentId) {
            if (parentId === node.id) return false;
            if (parentId === null) return node.nodeType === 'CATEGORY';
            return CatalogEditor.subtreeIds(editorSession, node.id).indexOf(parentId) === -1;
        });
        select.innerHTML = options.map(function (parentId) {
            var label = parentId === null ? 'Top level' : parentLabel(parentId);
            return '<option value="' + (parentId === null ? '' : parentId) + '"' +
                (parentId === (node.parentId || null) ? ' selected' : '') + '>' + label + '</option>';
        }).join('');
        select.disabled = options.length <= 1;
    }

    function parentLabel(id) {
        var parent = CatalogEditor.nodeById(editorSession, id);
        return parent ? parent.title + ' (' + parent.nodeType.toLowerCase() + ')' : id;
    }

    function refreshAddForm() {
        var kindSelect = document.getElementById('add-kind');
        var parentSelect = document.getElementById('add-parent');
        var videoInput = document.getElementById('add-video');
        var hint = document.getElementById('add-hint');
        if (!kindSelect || !parentSelect || !editorSession) return;

        var kind = kindSelect.value;
        var parents = kind === 'CATEGORY' ? [] : editorSession.nodes
            .filter(function (node) { return CatalogEditor.childTypesOf(node.nodeType).indexOf(kind) !== -1; })
            .sort(function (a, b) { return a.title < b.title ? -1 : 1; });

        if (kind === 'CATEGORY') {
            parentSelect.innerHTML = '<option value="">Top level</option>';
            parentSelect.disabled = true;
        } else {
            parentSelect.innerHTML = parents.map(function (node) {
                return '<option value="' + node.id + '">' + node.title + '</option>';
            }).join('');
            parentSelect.disabled = parents.length === 0;
        }

        var needsVideo = kind === 'VIDEO';
        videoInput.classList.toggle('hidden', !needsVideo);
        if (hint) {
            hint.textContent = kind === 'CATEGORY'
                ? 'A category is a shelf at the top level of the home screen.'
                : (parents.length === 0
                    ? 'There is nothing to put this in yet — add a category first.'
                    : (kind === 'VIDEO'
                        ? 'The parent names the video; the YouTube id is only the source.'
                        : 'A subcategory holds videos you add to it.'));
        }
    }

    async function loadCatalog() {
        setCatalogStatus('saving', 'Loading…');
        var result = await CatalogEditor.reload(
            function () { return apiCall('GET', '/catalog'); },
            { newId: editorNewId || undefined },
        );

        if (result.outcome !== 'reloaded') {
            editorSession = null;
            catalogError('Catalog unavailable: ' + result.reason);
            setCatalogStatus('error', 'Error');
            renderCatalog();
            return;
        }

        editorSession = result.session;
        editorNewId = editorSession.newId;
        editorConflict = null;
        if (!CatalogEditor.nodeById(editorSession, selectedNodeId)) selectedNodeId = null;
        var conflictBox = document.getElementById('catalog-conflict');
        if (conflictBox) conflictBox.classList.add('hidden');
        catalogError('');
        setCatalogStatus('saved', 'Saved (version ' + editorSession.catalogVersion + ')');
        renderCatalog();
    }

    /** Every edit goes through here: a refused one changes nothing and says why. */
    function applyEditorResult(result) {
        if (!result.ok) {
            catalogError('Not changed: ' + result.reason);
            return false;
        }
        editorSession = result.session;
        catalogError('');
        setCatalogStatus('editing', 'Editing — not saved yet');
        renderCatalog();
        return true;
    }

    function selectNode(id) {
        selectedNodeId = id;
        renderCatalog();
    }

    function addNode() {
        if (!editorSession) return;
        var kind = document.getElementById('add-kind').value;
        var parentId = document.getElementById('add-parent').value || null;
        var title = document.getElementById('add-title').value;
        var videoId = document.getElementById('add-video').value;

        // Remembered before the edit so the node that was just added can be recognised afterwards.
        var beforeIds = editorSession.nodes.map(function (node) { return node.id; });

        var result;
        if (kind === 'CATEGORY') result = CatalogEditor.addCategory(editorSession, { title: title });
        else if (kind === 'SUBCATEGORY') result = CatalogEditor.addSubcategory(editorSession, { parentId: parentId, title: title });
        else result = CatalogEditor.addVideo(editorSession, { parentId: parentId, title: title, youtubeVideoId: videoId });

        if (!applyEditorResult(result)) return;

        // Select what was just added, so the panel is ready for the next edit.
        var added = result.session.nodes.filter(function (node) { return beforeIds.indexOf(node.id) === -1; });
        document.getElementById('add-title').value = '';
        document.getElementById('add-video').value = '';
        if (added.length === 1) {
            selectedNodeId = added[0].id;
            renderCatalog();
        }
    }

    function renameSelected() {
        if (!editorSession || !selectedNodeId) return;
        var title = document.getElementById('panel-title').value;
        applyEditorResult(CatalogEditor.rename(editorSession, { id: selectedNodeId, title: title }));
    }

    function toggleSelectedEnabled() {
        if (!editorSession || !selectedNodeId) return;
        var enabled = document.getElementById('panel-enabled').checked;
        applyEditorResult(CatalogEditor.setEnabled(editorSession, { id: selectedNodeId, enabled: enabled }));
    }

    function moveSelectedUp() {
        if (!editorSession || !selectedNodeId) return;
        applyEditorResult(CatalogEditor.moveUp(editorSession, { id: selectedNodeId }));
    }

    function moveSelectedDown() {
        if (!editorSession || !selectedNodeId) return;
        applyEditorResult(CatalogEditor.moveDown(editorSession, { id: selectedNodeId }));
    }

    function moveSelectedTo() {
        if (!editorSession || !selectedNodeId) return;
        var parentId = document.getElementById('panel-parent').value || null;
        applyEditorResult(CatalogEditor.moveTo(editorSession, { id: selectedNodeId, parentId: parentId }));
    }

    function deleteSelected() {
        if (!editorSession || !selectedNodeId) return;
        var node = selectedNode();
        if (!node) return;

        var removed = CatalogEditor.subtreeIds(editorSession, selectedNodeId).length - 1;
        var question = removed > 0
            ? 'Delete "' + node.title + '" and the ' + removed + ' node(s) inside it? This cannot be undone.'
            : 'Delete "' + node.title + '"? This cannot be undone.';
        if (!window.confirm(question)) return;

        var parentId = node.parentId || null;
        if (applyEditorResult(CatalogEditor.remove(editorSession, { id: selectedNodeId }))) {
            selectedNodeId = parentId;
            renderCatalog();
        }
    }

    async function saveCatalog() {
        if (!editorSession || editorConflict) return;

        if (!CatalogEditor.isDirty(editorSession)) {
            setCatalogStatus('saved', 'Saved (version ' + editorSession.catalogVersion + ')');
            return;
        }

        setCatalogStatus('saving', 'Saving…');
        catalogError('');

        var result = await CatalogEditor.save(editorSession, function (body) {
            return apiCall('PUT', '/catalog', body);
        });

        if (result.outcome === 'saved') {
            // Only here, after the server answered 200, is anything called saved.
            editorSession = result.session;
            editorConflict = null;
            var box = document.getElementById('catalog-conflict');
            if (box) box.classList.add('hidden');
            setCatalogStatus('saved', 'Saved (version ' + editorSession.catalogVersion + ')');
            renderCatalog();
            return;
        }

        if (result.outcome === 'conflict') {
            editorSession = result.session;   // the unsaved edits, untouched
            editorConflict = { serverVersion: result.serverVersion };
            setCatalogStatus('conflict', 'Conflict');
            showCatalogConflict(result.serverVersion);
            renderCatalog();
            return;
        }

        if (result.outcome === 'rejected') {
            setCatalogStatus('error', 'Error');
            catalogError('The server refused the catalog: ' + result.reasons.join('; '));
            return;
        }

        setCatalogStatus('error', 'Error');
        catalogError('Not saved — ' + result.reason + '. Your changes are still here; press Save to try again.');
    }

    function showCatalogConflict(serverVersion) {
        var box = document.getElementById('catalog-conflict');
        var text = document.getElementById('catalog-conflict-text');
        if (!box || !text) return;
        text.textContent = 'Catalog changed on the server' +
            (serverVersion === null || serverVersion === undefined ? '' : ' (now version ' + serverVersion + ')') +
            '. Your unsaved changes were not applied.';
        box.classList.remove('hidden');
    }

    async function reloadCatalogFromServer() {
        if (!editorSession) return;

        if (CatalogEditor.isDirty(editorSession) && !editorConflict) {
            if (!window.confirm('Discard your unsaved changes and reload the catalog from the server?')) return;
        }

        setCatalogStatus('saving', 'Loading…');
        var result = await CatalogEditor.reload(
            function () { return apiCall('GET', '/catalog'); },
            { newId: editorNewId || undefined },
        );

        if (result.outcome !== 'reloaded') {
            setCatalogStatus('error', 'Error');
            catalogError('Could not reload: ' + result.reason);
            return;
        }

        editorSession = result.session;
        editorNewId = editorSession.newId;
        editorConflict = null;
        if (!CatalogEditor.nodeById(editorSession, selectedNodeId)) selectedNodeId = null;
        var box = document.getElementById('catalog-conflict');
        if (box) box.classList.add('hidden');
        catalogError('');
        setCatalogStatus('saved', 'Saved (version ' + editorSession.catalogVersion + ')');
        renderCatalog();
    }

    /** Keep looking at your edits without pretending they can be published as they are. */
    function keepEditing() {
        var box = document.getElementById('catalog-conflict');
        if (box) box.classList.add('hidden');
        setCatalogStatus('conflict', 'Conflict — reload before saving again');
        refreshCatalogNotices();
    }

    window.loadCatalog = loadCatalog;
    window.reloadCatalogFromServer = reloadCatalogFromServer;
    window.saveCatalog = saveCatalog;
    window.keepEditing = keepEditing;
    window.refreshAddForm = refreshAddForm;
    window.addNode = addNode;
    window.renameSelected = renameSelected;
    window.toggleSelectedEnabled = toggleSelectedEnabled;
    window.moveSelectedUp = moveSelectedUp;
    window.moveSelectedDown = moveSelectedDown;
    window.moveSelectedTo = moveSelectedTo;
    window.deleteSelected = deleteSelected;

    // Selecting a row: delegated, so re-rendering the tree never leaves a stale handler behind.
    (function () {
        var container = document.getElementById('catalog-tree');
        if (!container) return;
        container.addEventListener('click', function (event) {
            var target = event.target;
            while (target && target !== container && !target.getAttribute('data-node-id')) {
                target = target.parentNode;
            }
            if (!target || target === container) return;
            selectNode(target.getAttribute('data-node-id'));
        });
    })();

    // A refresh with unsaved edits loses them. Say so before it happens rather than after.
    window.addEventListener('beforeunload', function (event) {
        if (editorSession && CatalogEditor.isDirty(editorSession)) {
            event.preventDefault();
            event.returnValue = '';
            return '';
        }
    });

    // --- Dashboard lifecycle ---
    async function loadDashboard() {
        await refreshToken();
        loadPlaylists();
        loadStats();
        loadRecent();
        loadStatus();
        loadTimeLimits();
        loadKioskConfig();
        checkVersion();
        loadCrashLog();
        loadCatalog();

        // Set version footer from status
        try {
            var statusResult = await apiCall('GET', '/status');
            if (statusResult.status === 200 && statusResult.data.version) {
                updateVersionFooter(statusResult.data.version);
                checkUpdateAvailable(statusResult.data.version);
            }
        } catch (err) {}

        setPollingRate(120000);
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function logout() {
        sessionToken = null;
        localStorage.removeItem(STORAGE_KEY);
        dashboard.classList.add('hidden');
        authScreen.classList.remove('hidden');
        if (statusInterval) clearInterval(statusInterval);
    }

    // --- Add to Home Screen banner ---
    (function() {
        var DISMISS_KEY = tvId ? 'kw_homescreen_dismissed_' + tvId : 'kw_homescreen_dismissed';
        var banner = document.getElementById('homescreen-banner');
        var addBtn = document.getElementById('homescreen-add-btn');
        var dismissBtn = document.getElementById('homescreen-dismiss-btn');
        var bannerText = document.getElementById('homescreen-text');
        var deferredPrompt = null;

        var isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone;
        var isMobile = 'ontouchstart' in window || window.innerWidth <= 768;
        if (isStandalone || !isMobile || localStorage.getItem(DISMISS_KEY)) return;

        var isIOS = /iPhone|iPad/.test(navigator.userAgent);

        if (isIOS) {
            bannerText.textContent = "Tap Share then 'Add to Home Screen' for quick access.";
            addBtn.style.display = 'none';
            banner.classList.remove('hidden');
        }

        window.addEventListener('beforeinstallprompt', function(e) {
            e.preventDefault();
            deferredPrompt = e;
            banner.classList.remove('hidden');
        });

        addBtn.addEventListener('click', function() {
            if (deferredPrompt) {
                deferredPrompt.prompt();
                deferredPrompt.userChoice.then(function() {
                    deferredPrompt = null;
                    banner.classList.add('hidden');
                });
            }
        });

        dismissBtn.addEventListener('click', function() {
            banner.classList.add('hidden');
            localStorage.setItem(DISMISS_KEY, '1');
        });
    })();

    // --- Startup ---
    var autoPin = extractPin();
    if (autoPin && !sessionToken) {
        pinInput.value = autoPin;
        submitPin(autoPin);
    } else if (sessionToken) {
        refreshToken().then(function(valid) {
            if (valid) {
                authScreen.classList.add('hidden');
                dashboard.classList.remove('hidden');
                loadDashboard();
            } else {
                logout();
            }
        });
    }
})();
