const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';

function apiGet(url) {
    return fetch(url, { credentials: 'same-origin' }).then(r => r.json());
}

function apiSend(url, method, body) {
    return fetch(url, {
        method,
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-Token': csrfToken,
        },
        credentials: 'same-origin',
        body: JSON.stringify(body),
    }).then(r => r.json());
}

function setStatus(el, message) {
    if (!el) return;
    el.textContent = message;
    if (!message) return;
    setTimeout(() => {
        if (el.textContent === message) el.textContent = '';
    }, 2500);
}

const settingsForm = document.getElementById('settings-form');
const digestForm = document.getElementById('digest-settings-form');
const statusEl = document.getElementById('settings-status');
const digestStatusEl = document.getElementById('digest-status');

function loadSettings() {
    apiGet('api/settings.php').then(data => {
        const settings = data.settings || {};
        const recOnly = document.getElementById('setting-recommended-only');
        const hidePlayed = document.getElementById('setting-hide-played');
        const digestEnabled = document.getElementById('setting-digest-enabled');
        const digestTimes = document.getElementById('setting-digest-times');
        if (recOnly) recOnly.checked = !!settings.default_recommended_only;
        if (hidePlayed) hidePlayed.checked = !!settings.hide_played;
        if (digestEnabled) digestEnabled.checked = !!settings.digest_enabled;
        if (digestTimes) digestTimes.value = settings.digest_times || '10,22';
    });
}

if (settingsForm) {
    settingsForm.addEventListener('submit', e => {
        e.preventDefault();
        apiSend('api/settings.php', 'POST', {
            default_recommended_only: document.getElementById('setting-recommended-only')?.checked ? 1 : 0,
            hide_played: document.getElementById('setting-hide-played')?.checked ? 1 : 0,
            digest_enabled: document.getElementById('setting-digest-enabled')?.checked ? 1 : 0,
            digest_times: document.getElementById('setting-digest-times')?.value.trim() || '10,22',
        }).then(data => {
            if (data.ok) {
                setStatus(statusEl, 'Settings saved');
            }
        });
    });
}

if (digestForm) {
    digestForm.addEventListener('submit', e => {
        e.preventDefault();
        apiSend('api/settings.php', 'POST', {
            default_recommended_only: document.getElementById('setting-recommended-only')?.checked ? 1 : 0,
            hide_played: document.getElementById('setting-hide-played')?.checked ? 1 : 0,
            digest_enabled: document.getElementById('setting-digest-enabled')?.checked ? 1 : 0,
            digest_times: document.getElementById('setting-digest-times')?.value.trim() || '10,22',
        }).then(data => {
            if (data.ok) {
                setStatus(digestStatusEl, 'Digest saved');
            }
        });
    });
}

const navToggle = document.getElementById('nav-toggle');
if (navToggle) {
    navToggle.addEventListener('click', () => {
        document.querySelector('.topbar')?.classList.toggle('nav-open');
    });
}

loadSettings();
