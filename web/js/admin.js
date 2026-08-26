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

function showAdminSection(sectionKey) {
    const targetId = `section-${sectionKey}`;
    const target = document.getElementById(targetId);
    if (!target) {
        showAdminSection('dashboard');
        return;
    }
    const sections = document.querySelectorAll('.admin-panel-section');
    sections.forEach(section => {
        section.classList.toggle('active', section.id === targetId);
    });
}

function handleAdminHash() {
    const hash = window.location.hash.replace('#', '');
    if (!hash) {
        showAdminSection('dashboard');
        return;
    }
    showAdminSection(hash);
}

function renderStats(stats) {
    const container = document.getElementById('admin-stats');
    container.innerHTML = '';
    const items = [
        ['Total Episodes', stats.total_episodes],
        ['Total Ratings', stats.total_ratings],
        ['Active Users', stats.active_users],
        ['Last Refresh', stats.last_refresh || 'Never'],
    ];
    items.forEach(([label, value]) => {
        const card = document.createElement('div');
        card.className = 'stat-card';
        card.innerHTML = `<div class="stat-label">${label}</div><div class="stat-value">${value}</div>`;
        container.appendChild(card);
    });
}

function loadStats() {
    apiGet('api/admin.php?action=stats').then(data => {
        if (data.stats) renderStats(data.stats);
    });
}

function loadPodcasts() {
    apiGet('api/podcasts.php?all=1').then(data => {
        const list = document.getElementById('podcast-list');
        const select = document.getElementById('manual-podcast-select');
        list.innerHTML = '';
        select.innerHTML = '';
        (data.podcasts || []).forEach(p => {
            const row = document.createElement('div');
            row.className = 'admin-row';
            row.innerHTML = `
                <div>${p.title} <span class="muted">(${p.episode_count || 0} eps)</span></div>
                <div>${p.active ? 'Active' : 'Inactive'}</div>
                <div class="row-actions">
                    <button data-id="${p.id}" class="btn btn-small edit-podcast">Edit</button>
                    <button data-id="${p.id}" class="btn btn-small deactivate-podcast">${p.active ? 'Deactivate' : 'Activate'}</button>
                    <button data-id="${p.id}" class="btn btn-small refresh-podcast">Refresh</button>
                    <button data-id="${p.id}" class="btn btn-small btn-danger delete-podcast">Delete</button>
                </div>
            `;
            list.appendChild(row);

            const opt = document.createElement('option');
            opt.value = p.id;
            opt.textContent = p.title;
            select.appendChild(opt);
        });
    });
}

function loadUsers() {
    apiGet('api/users.php?action=list').then(data => {
        const list = document.getElementById('user-list');
        list.innerHTML = '';
        (data.users || []).forEach(u => {
            const row = document.createElement('div');
            row.className = 'admin-row';
            const verified = parseInt(u.email_verified);
            const verifyBtns = verified ? '' : `
                    <button data-id="${u.id}" class="btn btn-small verify-user">✓ Verify</button>
                    <button data-id="${u.id}" class="btn btn-small resend-verify">📧 Resend</button>`;
            row.innerHTML = `
                <div>${u.username} (${u.email}) ${verified ? '' : '<span class="badge-unverified">unverified</span>'}</div>
                <div>${u.role}</div>
                <div class="row-actions">
                    ${verifyBtns}
                    <button data-id="${u.id}" data-role="${u.role === 'admin' ? 'user' : 'admin'}" class="btn btn-small toggle-role">
                        Make ${u.role === 'admin' ? 'User' : 'Admin'}
                    </button>
                    <button data-id="${u.id}" class="btn btn-small delete-user">Delete</button>
                </div>
            `;
            list.appendChild(row);
        });
    });
}

let allTags = [];

function loadTags() {
    apiGet('api/tags.php?all=1').then(data => {
        allTags = data.tags || [];
        renderTags();
    });
}

function renderTags() {
    const list = document.getElementById('tag-list');
    if (!list) return;
    const filterEl = document.getElementById('tag-filter');
    const typeEl = document.getElementById('tag-type-filter');
    const filter = filterEl ? filterEl.value.toLowerCase() : '';
    const typeFilter = typeEl ? typeEl.value : '';
    list.innerHTML = '';
    const filtered = allTags.filter(t => {
        if (filter && !t.name.toLowerCase().includes(filter)) return false;
        if (typeFilter && t.type !== typeFilter) return false;
        return true;
    });
    const countEl = document.getElementById('tag-count');
    if (countEl) countEl.textContent = `${filtered.length} of ${allTags.length} tags`;
    filtered.forEach(t => {
        const row = document.createElement('div');
        row.className = 'admin-row';
        row.innerHTML = `
            <div class="tag-name-cell">
                <span class="tag-label tag-type-${t.type}">${t.type}</span>
                <span class="tag-name-text">${t.name}</span>
            </div>
            <div>Used ${t.usage_count}×</div>
            <div class="row-actions">
                <button data-id="${t.id}" data-name="${t.name.replace(/"/g, '&quot;')}" class="btn btn-small edit-tag">✏️ Edit</button>
                <button data-id="${t.id}" data-name="${t.name.replace(/"/g, '&quot;')}" class="btn btn-small merge-tag">🔗 Merge</button>
                ${parseInt(t.usage_count) > 10 ? `<button data-id="${t.id}" data-name="${t.name.replace(/"/g, '&quot;')}" data-count="${t.usage_count}" class="btn btn-small purge-noise-tag" title="Remove from episodes where name isn't in the title">🧹 Purge Noise</button>` : ''}
                <button data-id="${t.id}" class="btn btn-small delete-tag">🗑️ Delete</button>
            </div>
        `;
        list.appendChild(row);
    });
}

function loadDigestLog() {
    apiGet('api/admin.php?action=digest_log').then(data => {
        const list = document.getElementById('digest-log');
        list.innerHTML = '';
        (data.digest_log || []).forEach(row => {
            const item = document.createElement('div');
            item.className = 'admin-row';
            item.innerHTML = `
                <div>${row.email}</div>
                <div>${row.episode_count} episodes</div>
                <div>${row.sent_at}</div>
            `;
            list.appendChild(item);
        });
    });
}

function loadTagBlacklist() {
    apiGet('api/tag_blacklist.php').then(data => {
        const list = document.getElementById('tag-blacklist-list');
        if (!list) return;
        list.innerHTML = '';
        (data.tags || []).forEach(tag => {
            const row = document.createElement('div');
            row.className = 'admin-row';
            row.innerHTML = `
                <div>${tag.tag_name} (${tag.tag_type})</div>
                <div>Blacklisted ${tag.blacklisted_at || ''}</div>
                <div class="row-actions">
                    <button data-id="${tag.id}" class="btn btn-small restore-blacklist">Restore</button>
                </div>
            `;
            list.appendChild(row);
        });
    });
}

document.getElementById('podcast-form').addEventListener('submit', e => {
    e.preventDefault();
    const form = new FormData(e.target);
    const id = form.get('id');
    const payload = {
        title: form.get('title'),
        feed_url: form.get('feed_url'),
        website_url: form.get('website_url'),
        artwork_url: form.get('artwork_url'),
        author: form.get('author'),
        category: form.get('category'),
        description: form.get('description'),
        is_manual: form.get('is_manual') === 'on',
    };
    const method = id ? 'PUT' : 'POST';
    const url = id ? `api/podcasts.php?id=${id}` : 'api/podcasts.php';
    apiSend(url, method, payload).then(() => {
        e.target.reset();
        loadPodcasts();
    });
});

document.getElementById('podcast-list').addEventListener('click', e => {
    if (e.target.classList.contains('edit-podcast')) {
        const id = e.target.dataset.id;
        apiGet('api/podcasts.php?all=1').then(data => {
            const podcast = (data.podcasts || []).find(p => String(p.id) === String(id));
            if (!podcast) return;
            const form = document.getElementById('podcast-form');
            form.querySelector('[name="id"]').value = podcast.id;
            form.querySelector('[name="title"]').value = podcast.title;
            form.querySelector('[name="feed_url"]').value = podcast.feed_url || '';
            form.querySelector('[name="website_url"]').value = podcast.website_url || '';
            form.querySelector('[name="artwork_url"]').value = podcast.artwork_url || '';
            form.querySelector('[name="author"]').value = podcast.author || '';
            form.querySelector('[name="category"]').value = podcast.category || '';
            form.querySelector('[name="description"]').value = podcast.description || '';
            form.querySelector('[name="is_manual"]').checked = !!podcast.is_manual;
        });
    }
    if (e.target.classList.contains('deactivate-podcast')) {
        const id = e.target.dataset.id;
        apiSend(`api/podcasts.php?id=${id}`, 'DELETE', {}).then(loadPodcasts);
    }
    if (e.target.classList.contains('delete-podcast')) {
        const id = e.target.dataset.id;
        if (confirm('Permanently delete this podcast and ALL its episodes? This cannot be undone.')) {
            apiSend(`api/podcasts.php?action=purge&id=${id}`, 'DELETE', {}).then(() => {
                showOutput({output: 'Podcast and all episodes permanently deleted.'});
                loadPodcasts();
            });
        }
    }
    if (e.target.classList.contains('refresh-podcast')) {
        const btn = e.target;
        const id = btn.dataset.id;
        btn.disabled = true;
        btn.textContent = 'Refreshing...';
        apiSend(`api/podcasts.php?action=refresh&id=${id}`, 'POST', {}).then(data => {
            showOutput(data);
            btn.disabled = false;
            btn.textContent = 'Refresh';
        }).catch(() => { btn.disabled = false; btn.textContent = 'Refresh'; });
    }
});

document.getElementById('manual-episode-form').addEventListener('submit', e => {
    e.preventDefault();
    const form = new FormData(e.target);
    apiSend('api/episodes.php', 'POST', {
        podcast_id: form.get('podcast_id'),
        title: form.get('title'),
        description: form.get('description'),
        pub_date: form.get('pub_date'),
        audio_url: form.get('audio_url'),
        artwork_url: form.get('artwork_url'),
    }).then(() => {
        e.target.reset();
        loadStats();
    });
});

document.getElementById('user-list').addEventListener('click', e => {
    if (e.target.classList.contains('toggle-role')) {
        apiSend('api/users.php?action=update_role', 'PUT', {
            id: e.target.dataset.id,
            role: e.target.dataset.role,
        }).then(loadUsers);
    }
    if (e.target.classList.contains('delete-user')) {
        if (confirm('Delete this user?')) {
            apiSend(`api/users.php?action=delete&id=${e.target.dataset.id}`, 'DELETE', {}).then(loadUsers);
        }
    }
    if (e.target.classList.contains('verify-user')) {
        apiSend('api/users.php?action=verify', 'PUT', { id: e.target.dataset.id }).then(loadUsers);
    }
    if (e.target.classList.contains('resend-verify')) {
        e.target.textContent = 'Sending...';
        apiSend('api/users.php?action=resend_verification', 'PUT', { id: e.target.dataset.id }).then(r => {
            loadUsers();
            alert(r.sent ? 'Verification email sent!' : 'Failed to send email');
        });
    }
});

document.getElementById('tag-list').addEventListener('click', e => {
    if (e.target.classList.contains('delete-tag')) {
        if (confirm('Delete this tag and blacklist it from future extraction?')) {
            apiSend(`api/tags.php?id=${e.target.dataset.id}`, 'DELETE', {}).then(() => {
                loadTags();
                loadTagBlacklist();
            });
        }
    }
    if (e.target.classList.contains('purge-noise-tag')) {
        const tagId = e.target.dataset.id;
        const tagName = e.target.dataset.name;
        const count = e.target.dataset.count;
        if (confirm(`Purge noise for "${tagName}" (${count} uses)?\n\nThis will REMOVE the tag from episodes where "${tagName}" doesn't appear in the episode title. Episodes where the name IS in the title will keep the tag.\n\nThis is safe — it only removes AI-generated associations.`)) {
            e.target.disabled = true;
            e.target.textContent = '⏳ Purging...';
            apiSend('api/tags.php?action=purge_noise', 'POST', { tag_id: tagId }).then(data => {
                alert(`Done! Removed ${data.removed} noise associations, kept ${data.kept} legitimate ones.`);
                loadTags();
            }).catch(() => {
                e.target.disabled = false;
                e.target.textContent = '🧹 Purge Noise';
            });
        }
    }
    if (e.target.classList.contains('edit-tag')) {
        const id = e.target.dataset.id;
        const currentName = e.target.dataset.name;
        const newName = prompt('Rename tag:', currentName);
        if (newName && newName.trim() !== '' && newName !== currentName) {
            apiSend('api/tags.php?action=update', 'PUT', { id, name: newName.trim() }).then(loadTags);
        }
    }
    if (e.target.classList.contains('merge-tag')) {
        const sourceId = e.target.dataset.id;
        const sourceName = e.target.dataset.name;
        // Build a list of other tags of same type for selection
        const sourceTag = allTags.find(t => t.id == sourceId);
        if (!sourceTag) return;
        const candidates = allTags.filter(t => t.type === sourceTag.type && t.id != sourceId);
        const targetName = prompt(
            `Merge "${sourceName}" into which tag?\n\nType the name of the target tag (episodes will be moved to it, "${sourceName}" will be deleted).\n\nExisting ${sourceTag.type} tags:\n` +
            candidates.slice(0, 30).map(t => `  • ${t.name} (${t.usage_count}×)`).join('\n') +
            (candidates.length > 30 ? `\n  ... and ${candidates.length - 30} more` : '')
        );
        if (!targetName) return;
        const target = candidates.find(t => t.name.toLowerCase() === targetName.trim().toLowerCase());
        if (!target) {
            alert(`Tag "${targetName}" not found. Make sure you type the exact name.`);
            return;
        }
        if (confirm(`Merge "${sourceName}" → "${target.name}"?\n\nAll episode associations will be moved to "${target.name}" and "${sourceName}" will be deleted.`)) {
            apiSend('api/tags.php?action=merge', 'POST', { source_id: sourceId, target_id: target.id }).then(loadTags);
        }
    }
});

// Tag filter controls
document.addEventListener('DOMContentLoaded', () => {
    const filterEl = document.getElementById('tag-filter');
    const typeEl = document.getElementById('tag-type-filter');
    if (filterEl) filterEl.addEventListener('input', renderTags);
    if (typeEl) typeEl.addEventListener('change', renderTags);
});

const blacklistList = document.getElementById('tag-blacklist-list');
if (blacklistList) {
    blacklistList.addEventListener('click', e => {
        if (e.target.classList.contains('restore-blacklist')) {
            apiSend('api/tag_blacklist.php?action=restore', 'POST', {
                id: e.target.dataset.id,
            }).then(loadTagBlacklist);
        }
    });
}

// Tag form removed — edit/merge now handled inline via buttons

document.getElementById('itunes-search-btn').addEventListener('click', () => {
    const query = document.getElementById('itunes-search').value.trim();
    if (!query) return;
    apiGet(`api/search.php?q=${encodeURIComponent(query)}`).then(data => {
        const results = document.getElementById('itunes-results');
        results.innerHTML = '';
        (data.results || []).forEach(r => {
            const card = document.createElement('div');
            card.className = 'itunes-card';
            card.innerHTML = `
                <img src="${r.artworkUrl100}" alt="">
                <div>
                    <strong>${r.collectionName}</strong>
                    <div>${r.artistName}</div>
                    <button class="btn btn-small add-itunes"
                        data-title="${r.collectionName}"
                        data-feed="${r.feedUrl || ''}"
                        data-art="${r.artworkUrl600 || r.artworkUrl100 || ''}"
                        data-site="${r.collectionViewUrl || ''}"
                        data-author="${r.artistName || ''}"
                        data-id="${r.collectionId || ''}">
                        Add Podcast
                    </button>
                </div>
            `;
            results.appendChild(card);
        });
    });
});

document.getElementById('itunes-results').addEventListener('click', e => {
    if (e.target.classList.contains('add-itunes')) {
        const payload = {
            title: e.target.dataset.title,
            feed_url: e.target.dataset.feed,
            artwork_url: e.target.dataset.art,
            website_url: e.target.dataset.site,
            author: e.target.dataset.author,
            itunes_id: e.target.dataset.id,
            is_manual: false,
        };
        apiSend('api/podcasts.php', 'POST', payload).then(() => {
            loadPodcasts();
        });
    }
});

function showOutput(data) {
    const output = document.getElementById('system-output');
    if (!output) return;
    const ts = new Date().toLocaleTimeString();
    const message = data.output || data.error || 'Done.';
    const lines = message.split('\n').map(line => `[${ts}] ${line}`).join('\n');
    output.textContent = output.textContent ? `${output.textContent}\n${lines}` : lines;
    output.scrollTop = output.scrollHeight;
}

document.getElementById('refresh-feeds').addEventListener('click', () => {
    const btn = document.getElementById('refresh-feeds');
    const output = document.getElementById('system-output');
    btn.disabled = true;
    btn.textContent = 'Refreshing all feeds...';
    if (output) output.textContent = '⏳ Refreshing feeds — this may take a minute for large libraries...';
    apiSend('api/podcasts.php?action=refresh_all', 'POST', {}).then(data => {
        showOutput(data);
        btn.disabled = false;
        btn.textContent = 'Refresh Feeds';
    }).catch(() => {
        if (output) output.textContent = '❌ Refresh failed.';
        btn.disabled = false;
        btn.textContent = 'Refresh Feeds';
    });
});
document.getElementById('recompute-recs').addEventListener('click', () => {
    apiSend('api/admin.php?action=recompute', 'POST', {}).then(showOutput);
});
document.getElementById('extract-tags').addEventListener('click', () => {
    apiSend('api/admin.php?action=extract_tags', 'POST', {}).then(showOutput);
});

const navToggle = document.getElementById('nav-toggle');
if (navToggle) {
    navToggle.addEventListener('click', () => {
        document.querySelector('.topbar')?.classList.toggle('nav-open');
    });
}

// === Episode Tags Section ===
let currentEpisodeId = null;

function searchEpisodes() {
    const q = document.getElementById('episode-tag-search').value.trim();
    if (!q) return;
    apiGet(`api/episodes.php?q=${encodeURIComponent(q)}&limit=20`).then(data => {
        const results = document.getElementById('episode-tag-results');
        const detail = document.getElementById('episode-tag-detail');
        detail.style.display = 'none';
        results.innerHTML = '';
        const episodes = data.episodes || [];
        if (!episodes.length) {
            results.innerHTML = '<div class="muted" style="padding:10px;">No episodes found.</div>';
            return;
        }
        episodes.forEach(ep => {
            const row = document.createElement('div');
            row.className = 'admin-row episode-tag-row';
            row.style.cursor = 'pointer';
            row.innerHTML = `
                <div style="flex:1;">
                    <strong>${ep.title}</strong>
                    <span class="muted" style="font-size:0.8rem;margin-left:8px;">${ep.podcast_title || ''}</span>
                </div>
                <div class="muted" style="font-size:0.8rem;">${ep.pub_date ? ep.pub_date.slice(0, 10) : ''}</div>
            `;
            row.addEventListener('click', () => loadEpisodeTags(ep.id, ep.title));
            results.appendChild(row);
        });
    });
}

function loadEpisodeTags(episodeId, title) {
    currentEpisodeId = episodeId;
    const detail = document.getElementById('episode-tag-detail');
    document.getElementById('episode-tag-detail-title').textContent = title || `Episode #${episodeId}`;
    detail.style.display = 'block';
    apiGet(`api/tags.php?action=episode_tags&episode_id=${episodeId}`).then(data => {
        const container = document.getElementById('episode-tag-current');
        const tags = data.tags || [];
        if (!tags.length) {
            container.innerHTML = '<div class="muted">No tags on this episode.</div>';
            return;
        }
        container.innerHTML = tags.map(t => `
            <span class="episode-tag-chip tag-type-${t.type}">
                ${t.name}
                <span class="tag-chip-type" data-id="${t.id}" data-type="${t.type}" title="Click to toggle type">${t.type}</span>
                <span class="tag-chip-remove" data-tag-id="${t.id}" title="Remove from episode">×</span>
            </span>
        `).join('');
    });
}

document.getElementById('episode-tag-search-btn').addEventListener('click', searchEpisodes);
document.getElementById('episode-tag-search').addEventListener('keydown', e => {
    if (e.key === 'Enter') searchEpisodes();
});

document.getElementById('episode-tag-current').addEventListener('click', e => {
    if (e.target.classList.contains('tag-chip-remove')) {
        const tagId = e.target.dataset.tagId;
        apiSend(`api/tags.php?action=remove_episode_tag&episode_id=${currentEpisodeId}&tag_id=${tagId}`, 'DELETE', {}).then(() => {
            loadEpisodeTags(currentEpisodeId, document.getElementById('episode-tag-detail-title').textContent);
        });
    }
    if (e.target.classList.contains('tag-chip-type')) {
        const tagId = e.target.dataset.id;
        const currentType = e.target.dataset.type;
        const newType = currentType === 'guest' ? 'topic' : 'guest';
        if (confirm(`Change this tag from "${currentType}" to "${newType}" globally?`)) {
            apiSend('api/tags.php?action=update', 'PUT', { id: tagId, type: newType }).then(() => {
                loadEpisodeTags(currentEpisodeId, document.getElementById('episode-tag-detail-title').textContent);
                loadTags();
            });
        }
    }
});

document.getElementById('episode-tag-add-btn').addEventListener('click', () => {
    const name = document.getElementById('episode-tag-add-name').value.trim();
    const type = document.getElementById('episode-tag-add-type').value;
    if (!name || !currentEpisodeId) return;
    apiSend('api/tags.php', 'POST', { episode_id: currentEpisodeId, name, type }).then(() => {
        document.getElementById('episode-tag-add-name').value = '';
        loadEpisodeTags(currentEpisodeId, document.getElementById('episode-tag-detail-title').textContent);
        loadTags();
    });
});

document.getElementById('episode-reextract').addEventListener('click', () => {
    if (!currentEpisodeId) return;
    if (!confirm('This will remove all AI-generated tags and re-run extraction. Manual tags are kept. Continue?')) return;
    const btn = document.getElementById('episode-reextract');
    btn.disabled = true;
    btn.textContent = '⏳ Re-extracting...';
    apiSend('api/tags.php?action=reextract', 'POST', { episode_id: currentEpisodeId }).then(data => {
        btn.disabled = false;
        btn.textContent = '🔄 Re-extract AI Tags';
        loadEpisodeTags(currentEpisodeId, document.getElementById('episode-tag-detail-title').textContent);
        loadTags();
        alert(data.reextracted ? 'Tags re-extracted!' : `AI tags cleared. ${data.manual_tags_kept} manual tags kept.`);
    }).catch(() => {
        btn.disabled = false;
        btn.textContent = '🔄 Re-extract AI Tags';
    });
});

// Also add type toggle to the main tag list
document.getElementById('tag-list').addEventListener('click', e => {
    if (e.target.classList.contains('tag-label')) {
        const row = e.target.closest('.admin-row');
        const editBtn = row?.querySelector('.edit-tag');
        if (!editBtn) return;
        const tagId = editBtn.dataset.id;
        const currentType = e.target.textContent.trim();
        const newType = currentType === 'guest' ? 'topic' : 'guest';
        if (confirm(`Change this tag from "${currentType}" to "${newType}"?`)) {
            apiSend('api/tags.php?action=update', 'PUT', { id: tagId, type: newType }).then(loadTags);
        }
    }
});

document.querySelectorAll('.admin-card').forEach(card => {
    card.addEventListener('click', () => {
        const target = card.dataset.section;
        if (target) {
            window.location.hash = `#${target}`;
        }
    });
});

window.addEventListener('hashchange', handleAdminHash);

loadStats();
loadPodcasts();
loadUsers();
loadTags();
loadDigestLog();
loadTagBlacklist();
handleAdminHash();
