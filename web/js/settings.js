const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';

function apiGet(url) {
    return fetch(url, { credentials: 'same-origin' }).then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error || 'Request failed');
        return data;
    });
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
    }).then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw new Error(data.error || 'Request failed');
        return data;
    });
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
const libraryList = document.getElementById('library-list');
const libraryCount = document.getElementById('library-count');
const libraryAddStatus = document.getElementById('library-add-status');
const searchResults = document.getElementById('library-search-results');

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
    }).catch(() => {});
}

function savePrefsPayload() {
    return {
        default_recommended_only: document.getElementById('setting-recommended-only')?.checked ? 1 : 0,
        hide_played: document.getElementById('setting-hide-played')?.checked ? 1 : 0,
        digest_enabled: document.getElementById('setting-digest-enabled')?.checked ? 1 : 0,
        digest_times: document.getElementById('setting-digest-times')?.value.trim() || '10,22',
    };
}

if (settingsForm) {
    settingsForm.addEventListener('submit', e => {
        e.preventDefault();
        apiSend('api/settings.php', 'POST', savePrefsPayload()).then(data => {
            if (data.ok) setStatus(statusEl, 'Settings saved');
        }).catch(err => setStatus(statusEl, err.message));
    });
}

if (digestForm) {
    digestForm.addEventListener('submit', e => {
        e.preventDefault();
        apiSend('api/settings.php', 'POST', savePrefsPayload()).then(data => {
            if (data.ok) setStatus(digestStatusEl, 'Digest saved');
        }).catch(err => setStatus(digestStatusEl, err.message));
    });
}

function renderLibrary(subscriptions) {
    if (!libraryList) return;
    libraryList.innerHTML = '';
    const rows = subscriptions || [];
    if (libraryCount) {
        libraryCount.textContent = rows.length
            ? `${rows.length} show${rows.length === 1 ? '' : 's'}`
            : 'No shows yet';
    }
    if (!rows.length) {
        const empty = document.createElement('div');
        empty.className = 'library-empty muted';
        empty.innerHTML = 'Your library is empty. Search above, paste an RSS feed, or browse featured shows on <a href="index.php">Home</a>.';
        libraryList.appendChild(empty);
        return;
    }
    rows.forEach((sub) => {
        const card = document.createElement('article');
        card.className = 'library-card';

        const art = document.createElement('img');
        art.className = 'library-card-art';
        art.src = sub.artwork_url || 'assets/logo.png';
        art.alt = '';

        const body = document.createElement('div');
        body.className = 'library-card-body';

        const title = document.createElement('div');
        title.className = 'library-card-title';
        title.textContent = sub.title || 'Untitled';

        const meta = document.createElement('div');
        meta.className = 'library-card-meta muted';
        const bits = [];
        bits.push(Number(sub.curated) === 1 ? 'Featured' : 'Added by users');
        const epCount = typeof sub.episode_count === 'number' ? sub.episode_count : 0;
        bits.push(`${epCount} episode${epCount === 1 ? '' : 's'}`);
        meta.textContent = bits.join(' · ');

        const feedToggle = document.createElement('label');
        feedToggle.className = 'toggle library-feed-toggle';
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = Number(sub.include_in_feed) !== 0;
        checkbox.addEventListener('change', () => {
            checkbox.disabled = true;
            apiSend('api/subscriptions.php', 'POST', {
                action: 'set_include_in_feed',
                podcast_id: sub.id,
                include_in_feed: checkbox.checked ? 1 : 0,
            }).then(() => {
                setStatus(libraryAddStatus, checkbox.checked ? 'Shown in feed' : 'Hidden from feed');
            }).catch((err) => {
                checkbox.checked = !checkbox.checked;
                setStatus(libraryAddStatus, err.message);
            }).finally(() => {
                checkbox.disabled = false;
            });
        });
        const toggleText = document.createElement('span');
        toggleText.textContent = 'Show in home feed';
        feedToggle.appendChild(checkbox);
        feedToggle.appendChild(toggleText);

        const actions = document.createElement('div');
        actions.className = 'library-card-actions';
        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'btn btn-small';
        removeBtn.textContent = 'Remove from library';
        removeBtn.addEventListener('click', () => {
            if (!confirm(`Remove “${sub.title || 'this podcast'}” from your library?`)) return;
            removeBtn.disabled = true;
            apiSend('api/subscriptions.php', 'POST', {
                action: 'unsubscribe',
                podcast_id: sub.id,
            }).then(() => loadLibrary()).catch((err) => {
                removeBtn.disabled = false;
                setStatus(libraryAddStatus, err.message);
            });
        });
        actions.appendChild(removeBtn);

        body.appendChild(title);
        body.appendChild(meta);
        body.appendChild(feedToggle);
        body.appendChild(actions);

        card.appendChild(art);
        card.appendChild(body);
        libraryList.appendChild(card);
    });
}

function loadLibrary() {
    apiGet('api/subscriptions.php').then((data) => {
        renderLibrary(data.subscriptions || []);
    }).catch((err) => setStatus(libraryAddStatus, err.message));
}

function addPodcast(payload) {
    setStatus(libraryAddStatus, 'Adding…');
    return apiSend('api/podcasts.php?action=add_user', 'POST', payload)
        .then((data) => {
            setStatus(libraryAddStatus, data.created ? 'Podcast added and subscribed' : 'Already in catalog — subscribed');
            loadLibrary();
            return data;
        })
        .catch((err) => {
            setStatus(libraryAddStatus, err.message);
            throw err;
        });
}

function renderSearchResults(results) {
    if (!searchResults) return;
    searchResults.innerHTML = '';
    if (!results.length) {
        searchResults.innerHTML = '<div class="muted">No matches.</div>';
        return;
    }
    results.forEach((item) => {
        const row = document.createElement('div');
        row.className = 'library-search-item';

        const art = document.createElement('img');
        art.src = item.artworkUrl600 || item.artworkUrl100 || 'assets/logo.png';
        art.alt = '';

        const info = document.createElement('div');
        info.className = 'library-search-info';
        const title = document.createElement('div');
        title.className = 'library-search-title';
        title.textContent = item.collectionName || item.trackName || 'Podcast';
        const author = document.createElement('div');
        author.className = 'muted';
        author.textContent = item.artistName || '';
        info.appendChild(title);
        info.appendChild(author);

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-small btn-primary';
        btn.textContent = 'Add';
        btn.addEventListener('click', () => {
            btn.disabled = true;
            addPodcast({
                source: 'user_search',
                title: item.collectionName || item.trackName || 'Podcast',
                feed_url: item.feedUrl || '',
                artwork_url: item.artworkUrl600 || item.artworkUrl100 || null,
                author: item.artistName || null,
                itunes_id: item.collectionId || item.trackId || null,
            }).finally(() => {
                btn.disabled = false;
            });
        });

        row.appendChild(art);
        row.appendChild(info);
        row.appendChild(btn);
        searchResults.appendChild(row);
    });
}

function runLibrarySearch() {
    const q = document.getElementById('library-search-input')?.value.trim() || '';
    if (!q) {
        setStatus(libraryAddStatus, 'Enter a search term');
        return;
    }
    setStatus(libraryAddStatus, 'Searching…');
    apiGet(`api/search.php?q=${encodeURIComponent(q)}`)
        .then((data) => {
            setStatus(libraryAddStatus, '');
            renderSearchResults(data.results || []);
        })
        .catch((err) => setStatus(libraryAddStatus, err.message));
}

document.getElementById('library-search-btn')?.addEventListener('click', runLibrarySearch);
document.getElementById('library-search-input')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        runLibrarySearch();
    }
});

document.getElementById('library-add-feed-btn')?.addEventListener('click', () => {
    const feedUrl = document.getElementById('library-feed-url')?.value.trim() || '';
    if (!feedUrl) {
        setStatus(libraryAddStatus, 'Paste a feed URL first');
        return;
    }
    addPodcast({ source: 'user_rss', feed_url: feedUrl }).then(() => {
        const input = document.getElementById('library-feed-url');
        if (input) input.value = '';
    }).catch(() => {});
});

const navToggle = document.getElementById('nav-toggle');
if (navToggle) {
    navToggle.addEventListener('click', () => {
        document.querySelector('.topbar')?.classList.toggle('nav-open');
    });
}

loadSettings();
loadLibrary();
