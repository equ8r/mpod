const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
const player = new MicahPlayer();

const state = {
    page: 1,
    perPage: 50,
    sort: 'newest',
    podcastId: '',
    rated: '',
    played: '',
    recommended: false,
    showAll: false,
    tag: '',
    search: '',
};
let userSettings = null;
let allPodcasts = [];
let communityPodcasts = [];
let subscriptionIds = new Set();
let subscriptionMeta = {};
let subscriptionsLoaded = false;

const episodeGrid = document.getElementById('episode-grid');
const pageInfo = document.getElementById('page-info');
const COLUMN_PREF_KEY = 'micahpod_episode_cols';

function defaultEpisodeColumns() {
    const width = window.innerWidth || 1200;
    if (width >= 1280) return 3;
    if (width >= 900) return 2;
    return 1;
}

function applyEpisodeColumns(cols) {
    if (!episodeGrid) return;
    const value = Math.min(4, Math.max(1, parseInt(cols, 10) || defaultEpisodeColumns()));
    episodeGrid.classList.remove('cols-1', 'cols-2', 'cols-3', 'cols-4');
    episodeGrid.classList.add(`cols-${value}`);
    const select = document.getElementById('filter-columns');
    if (select && select.value !== String(value)) {
        select.value = String(value);
    }
    try {
        localStorage.setItem(COLUMN_PREF_KEY, String(value));
    } catch (e) {
        // ignore quota / private mode
    }
    return value;
}

function initEpisodeColumns() {
    let saved = null;
    try {
        saved = localStorage.getItem(COLUMN_PREF_KEY);
    } catch (e) {
        saved = null;
    }
    const initial = saved && ['1', '2', '3', '4'].includes(saved)
        ? saved
        : String(defaultEpisodeColumns());
    applyEpisodeColumns(initial);
    const select = document.getElementById('filter-columns');
    if (select) {
        select.addEventListener('change', (e) => {
            applyEpisodeColumns(e.target.value);
        });
    }
}
const subscriptionPanel = document.getElementById('subscription-panel');
const subscriptionGrid = document.getElementById('subscription-grid');
const communitySection = document.getElementById('community-podcasts');
const communityGrid = document.getElementById('community-grid');
const emptyLibraryCta = document.getElementById('empty-library-cta');
const subscriptionCount = document.getElementById('subscription-count');
const subscriptionCountPanel = document.getElementById('subscription-count-panel');
const discussionPanel = document.getElementById('discussion-panel');
const discussionBack = document.getElementById('discussion-back');
const discussionArt = document.getElementById('discussion-art');
const discussionTitle = document.getElementById('discussion-title');
const discussionPodcast = document.getElementById('discussion-podcast');
const discussionInput = document.getElementById('discussion-input');
const discussionSubmit = document.getElementById('discussion-submit');
const discussionList = document.getElementById('discussion-list');
const discussionEmpty = document.getElementById('discussion-empty');
const discussionCount = document.getElementById('discussion-count');
const discussionLoginHint = document.getElementById('discussion-login-hint');
const filtersSection = document.querySelector('.filters');
const paginationSection = document.querySelector('.pagination');

let currentEpisodeMap = {};
let commentCounts = {};
let activeDiscussionId = null;

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

function formatDuration(seconds) {
    if (!seconds) return '';
    const mins = Math.floor(seconds / 60);
    const hours = Math.floor(mins / 60);
    const remMins = mins % 60;
    if (hours > 0) {
        return `${hours}h ${remMins}m`;
    }
    return `${mins} min`;
}

function relativeTime(dateString) {
    const date = new Date(dateString);
    const diff = Date.now() - date.getTime();
    const mins = Math.floor(diff / 60000);
    const hours = Math.floor(mins / 60);
    const days = Math.floor(hours / 24);
    if (mins < 60) return `${mins} minutes ago`;
    if (hours < 24) return `${hours} hours ago`;
    if (days === 1) return 'Yesterday';
    return `${days} days ago`;
}

function timeAgo(dateString) {
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) return '';
    const diffMs = Date.now() - date.getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) return 'Just now';
    if (mins < 60) return `${mins} minute${mins === 1 ? '' : 's'} ago`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
    const days = Math.floor(hours / 24);
    if (days === 1) return 'Yesterday';
    if (days < 7) return `${days} days ago`;
    const weeks = Math.floor(days / 7);
    if (weeks < 5) return `${weeks} week${weeks === 1 ? '' : 's'} ago`;
    const months = Math.floor(days / 30);
    if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
    const years = Math.floor(days / 365);
    return `${years} year${years === 1 ? '' : 's'} ago`;
}

function sanitizeHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = html || '';
    const allowedTags = new Set(['A', 'B', 'BR', 'EM', 'I', 'P', 'STRONG', 'UL', 'OL', 'LI', 'CODE', 'PRE']);
    const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_ELEMENT, null);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => {
        if (!allowedTags.has(node.tagName)) {
            const text = document.createTextNode(node.textContent || '');
            node.replaceWith(text);
            return;
        }
        [...node.attributes].forEach(attr => {
            if (node.tagName === 'A' && attr.name === 'href') {
                return;
            }
            node.removeAttribute(attr.name);
        });
        if (node.tagName === 'A') {
            node.setAttribute('rel', 'noopener noreferrer');
            node.setAttribute('target', '_blank');
        }
    });
    return template.innerHTML;
}

function buildStars(current, episodeId) {
    const wrapper = document.createElement('div');
    wrapper.className = 'stars';
    wrapper.dataset.current = String(current);
    const setStars = value => {
        const stars = wrapper.querySelectorAll('.star');
        stars.forEach((star, idx) => {
            star.classList.toggle('active', idx < value);
        });
    };
    for (let i = 1; i <= 5; i++) {
        const star = document.createElement('span');
        star.className = i <= current ? 'star active' : 'star';
        star.dataset.value = String(i);
        star.textContent = '★';
        star.addEventListener('mouseenter', () => setStars(i));
        star.addEventListener('click', () => {
            apiSend('api/ratings.php', 'POST', { episode_id: episodeId, rating: i })
                .then(() => {
                    wrapper.dataset.current = String(i);
                    setStars(i);
                })
                .then(() => loadEpisodes());
        });
        wrapper.appendChild(star);
    }
    wrapper.addEventListener('mouseleave', () => {
        setStars(parseInt(wrapper.dataset.current || '0', 10));
    });
    return wrapper;
}

function buildTag(tag) {
    const tagEl = document.createElement('span');
    tagEl.className = `tag-pill ${tag.type}`;
    tagEl.textContent = tag.name;
    tagEl.title = tag.type;
    tagEl.addEventListener('click', () => {
        state.tag = tag.name;
        state.page = 1;
        loadEpisodes();
    });
    return tagEl;
}

function buildRecommendationBadge(episode) {
    if (!episode.rec_score || episode.rec_score < 0.7) return null;
    const badge = document.createElement('div');
    badge.className = 'rec-badge';
    badge.textContent = '🔥 Recommended for you';
    let tooltip = 'Recommended for you';
    if (episode.factors_json) {
        try {
            const factors = JSON.parse(episode.factors_json);
            const reasons = [];
            if (factors.topic_match) reasons.push(`topic match ${Math.round(factors.topic_match * 100)}%`);
            if (factors.guest_match) reasons.push(`guest match ${Math.round(factors.guest_match * 100)}%`);
            if (factors.podcast_affinity) reasons.push(`podcast affinity ${Math.round(factors.podcast_affinity * 100)}%`);
            if (reasons.length) tooltip = `Based on ${reasons.join(', ')}`;
        } catch (err) {}
    }
    badge.title = tooltip;
    return badge;
}

function buildPlayedBadge() {
    const badge = document.createElement('div');
    badge.className = 'played-banner';
    badge.textContent = '✓ Played';
    badge.title = 'Played';
    return badge;
}

function buildInProgressBadge(episode) {
    const position = Number(episode.position_seconds);
    if (!Number.isFinite(position) || position < 30 || episode.completed) return null;
    const badge = document.createElement('div');
    badge.className = 'in-progress-badge';
    const label = player.formatTime ? player.formatTime(position) : formatDuration(position);
    badge.textContent = `In progress · ${label}`;
    badge.title = `Resume from ${label}`;
    return badge;
}

function buildCommentBadge(count) {
    if (!count || count <= 0) return null;
    const badge = document.createElement('div');
    badge.className = 'comment-count-badge';
    const icon = document.createElement('span');
    icon.className = 'comment-count-icon';
    icon.textContent = '💬';
    const value = document.createElement('span');
    value.className = 'comment-count-value';
    value.textContent = String(count);
    badge.appendChild(icon);
    badge.appendChild(value);
    return badge;
}

function renderEpisode(episode) {
    const card = document.createElement('article');
    card.className = 'episode-card';
    card.dataset.episodeId = String(episode.id);

    const art = document.createElement('img');
    art.className = 'episode-art';
    art.loading = 'lazy';
    art.src = episode.artwork_url || episode.podcast_artwork || 'assets/logo.png';

    const body = document.createElement('div');
    body.className = 'episode-body';

    const show = document.createElement('div');
    show.className = 'episode-show';
    show.textContent = episode.podcast_title || '';

    const title = document.createElement('div');
    title.className = 'episode-title';
    title.textContent = episode.title;

    const meta = document.createElement('div');
    meta.className = 'episode-meta';
    const pieces = [];
    if (episode.episode_number) {
        pieces.push(`Ep. ${episode.episode_number}`);
    }
    pieces.push(relativeTime(episode.pub_date));
    const duration = formatDuration(episode.duration_seconds);
    if (duration) pieces.push(duration);
    meta.textContent = pieces.join(' • ');

    const desc = document.createElement('div');
    desc.className = 'episode-desc';
    const descText = document.createElement('div');
    descText.className = 'episode-desc-text';
    descText.textContent = episode.description || '';
    const descHtml = document.createElement('div');
    descHtml.className = 'episode-desc-html';
    descHtml.innerHTML = sanitizeHtml(episode.description_html || '');
    desc.appendChild(descText);
    desc.appendChild(descHtml);

    const readMore = document.createElement('button');
    readMore.className = 'read-more';
    readMore.textContent = 'Read more';
    readMore.addEventListener('click', () => {
        const isExpanded = card.classList.toggle('expanded');
        readMore.textContent = isExpanded ? 'Show less' : 'Read more';
    });

    const tags = document.createElement('div');
    tags.className = 'tag-row';
    (episode.tags || []).forEach(tag => tags.appendChild(buildTag(tag)));

    const actions = document.createElement('div');
    actions.className = 'episode-actions';
    const playBtn = document.createElement('button');
    playBtn.className = 'btn btn-primary play-button';
    playBtn.textContent = '▶';
    playBtn.setAttribute('aria-label', 'Play');
    playBtn.title = 'Play';
    playBtn.addEventListener('click', () => player.loadEpisode(episode));
    actions.appendChild(playBtn);

    const queueBtn = document.createElement('button');
    queueBtn.className = 'queue-button';
    queueBtn.textContent = '＋ Queue';
    queueBtn.setAttribute('aria-label', 'Add to queue');
    queueBtn.title = 'Add to queue';
    queueBtn.addEventListener('click', () => player.addToQueue(episode));
    actions.appendChild(queueBtn);

    const playNextBtn = document.createElement('button');
    playNextBtn.className = 'play-next-button';
    playNextBtn.textContent = 'Play Next';
    playNextBtn.setAttribute('aria-label', 'Play next');
    playNextBtn.title = 'Play next';
    playNextBtn.addEventListener('click', () => player.addToQueue(episode, { playNext: true }));
    actions.appendChild(playNextBtn);

    const discussBtn = document.createElement('button');
    discussBtn.className = 'discuss-button';
    discussBtn.textContent = 'Discuss';
    discussBtn.setAttribute('aria-label', 'Discuss this episode');
    discussBtn.title = 'Discuss';
    discussBtn.addEventListener('click', () => openDiscussion(episode.id));
    actions.appendChild(discussBtn);

    const rating = buildStars(episode.user_rating || 0, episode.id);

    const badge = buildRecommendationBadge(episode);
    if (badge) card.appendChild(badge);
    if (episode.completed) {
        card.classList.add('is-played');
        card.appendChild(buildPlayedBadge());
    } else {
        const progressBadge = buildInProgressBadge(episode);
        if (progressBadge) card.appendChild(progressBadge);
    }
    const commentBadge = buildCommentBadge(episode.comment_count || 0);
    if (commentBadge) {
        commentBadge.dataset.episodeId = String(episode.id);
        card.appendChild(commentBadge);
    }

    body.appendChild(show);
    body.appendChild(title);
    body.appendChild(meta);
    body.appendChild(desc);
    if (descHtml.innerHTML.trim()) {
        body.appendChild(readMore);
    }
    body.appendChild(tags);
    body.appendChild(rating);
    body.appendChild(actions);

    card.appendChild(art);
    card.appendChild(body);
    return card;
}

function setDiscussionVisibility(isOpen) {
    if (discussionPanel) {
        discussionPanel.classList.toggle('hidden', !isOpen);
    }
    if (episodeGrid) {
        episodeGrid.classList.toggle('hidden', isOpen);
    }
    if (paginationSection) {
        paginationSection.classList.toggle('hidden', isOpen);
    }
    if (filtersSection) {
        filtersSection.classList.toggle('hidden', isOpen);
    }
    if (subscriptionPanel) {
        subscriptionPanel.classList.add('hidden');
    }
    const subscriptionsLink = document.getElementById('nav-my-podcasts');
    if (subscriptionsLink) {
        subscriptionsLink.classList.remove('active');
    }
}

function updateDiscussionHeader(episode) {
    if (!episode) return;
    if (discussionArt) {
        discussionArt.src = episode.artwork_url || episode.podcast_artwork || 'assets/logo.png';
    }
    if (discussionTitle) {
        discussionTitle.textContent = episode.title || '';
    }
    if (discussionPodcast) {
        discussionPodcast.textContent = episode.podcast_title || '';
    }
}

function updateDiscussionCount(episodeId, count) {
    if (discussionCount && activeDiscussionId === episodeId) {
        discussionCount.textContent = String(count);
    }
    commentCounts[episodeId] = count;
    const episode = currentEpisodeMap[episodeId];
    if (episode) {
        episode.comment_count = count;
    }
    updateCommentBadge(episodeId, count);
}

function updateCommentBadge(episodeId, count) {
    if (!episodeGrid) return;
    const card = episodeGrid.querySelector(`.episode-card[data-episode-id="${episodeId}"]`);
    if (!card) return;
    let badge = card.querySelector('.comment-count-badge');
    if (!count || count <= 0) {
        if (badge) badge.remove();
        return;
    }
    if (!badge) {
        badge = buildCommentBadge(count);
        if (!badge) return;
        badge.dataset.episodeId = String(episodeId);
        card.appendChild(badge);
        return;
    }
    const value = badge.querySelector('.comment-count-value');
    if (value) value.textContent = String(count);
}

function openDiscussion(episodeId) {
    const episode = currentEpisodeMap[episodeId];
    activeDiscussionId = episodeId;
    updateDiscussionHeader(episode);
    if (discussionCount) {
        const count = commentCounts[episodeId] ?? 0;
        discussionCount.textContent = String(count);
    }
    if (discussionInput) {
        discussionInput.value = '';
        discussionInput.disabled = !window.MICAHPOD_USER;
    }
    if (discussionSubmit) {
        discussionSubmit.disabled = !window.MICAHPOD_USER;
    }
    if (discussionLoginHint) {
        discussionLoginHint.classList.toggle('hidden', !!window.MICAHPOD_USER);
    }
    if (discussionList) {
        discussionList.innerHTML = '';
    }
    if (discussionEmpty) {
        discussionEmpty.classList.add('hidden');
    }
    setDiscussionVisibility(true);
    loadComments(episodeId);
}

function closeDiscussion() {
    activeDiscussionId = null;
    setDiscussionVisibility(false);
}

function loadComments(episodeId) {
    if (!episodeId) return;
    apiGet(`api/comments.php?episode_id=${episodeId}`).then(data => {
        const comments = data.comments || [];
        renderComments(comments);
        const count = typeof data.total_count === 'number' ? data.total_count : comments.length;
        updateDiscussionCount(episodeId, count);
    }).catch(() => {
        if (discussionList) {
            discussionList.innerHTML = '';
        }
        if (discussionEmpty) {
            discussionEmpty.classList.remove('hidden');
        }
    });
}

function postComment(episodeId, parentId, body) {
    return apiSend('api/comments.php', 'POST', {
        action: 'create',
        episode_id: episodeId,
        parent_id: parentId || null,
        body,
    });
}

function editComment(commentId, body) {
    return apiSend('api/comments.php?action=edit', 'PUT', {
        action: 'edit',
        comment_id: commentId,
        body,
    });
}

function deleteComment(commentId) {
    return apiSend('api/comments.php', 'DELETE', {
        comment_id: commentId,
    });
}

function renderComments(comments) {
    if (!discussionList) return;
    discussionList.innerHTML = '';
    if (!comments.length) {
        if (discussionEmpty) discussionEmpty.classList.remove('hidden');
        return;
    }
    if (discussionEmpty) discussionEmpty.classList.add('hidden');
    comments.forEach(comment => {
        discussionList.appendChild(renderCommentTree(comment, 1));
    });
}

function buildInlineForm({ initialValue = '', onSubmit, onCancel, submitLabel, quoteText }) {
    const form = document.createElement('div');
    form.className = 'comment-form-inline';

    if (quoteText) {
        const quote = document.createElement('div');
        quote.className = 'comment-quote';
        quote.textContent = quoteText;
        form.appendChild(quote);
    }

    const textarea = document.createElement('textarea');
    textarea.rows = 3;
    textarea.value = initialValue;
    form.appendChild(textarea);

    const actions = document.createElement('div');
    actions.className = 'comment-form-actions';

    const submit = document.createElement('button');
    submit.className = 'btn btn-small btn-primary';
    submit.type = 'button';
    submit.textContent = submitLabel;
    submit.addEventListener('click', () => {
        const value = textarea.value.trim();
        onSubmit(value, textarea, submit);
    });

    const cancel = document.createElement('button');
    cancel.className = 'btn btn-small';
    cancel.type = 'button';
    cancel.textContent = 'Cancel';
    cancel.addEventListener('click', () => {
        if (onCancel) onCancel();
        form.remove();
    });

    actions.appendChild(submit);
    actions.appendChild(cancel);
    form.appendChild(actions);

    return form;
}

function renderCommentTree(comment, depth) {
    const wrapper = document.createElement('div');
    wrapper.className = `comment-thread depth-${depth}`;

    const card = document.createElement('div');
    card.className = 'comment-card';

    const header = document.createElement('div');
    header.className = 'comment-header';

    const user = document.createElement('span');
    user.className = 'comment-user';
    user.textContent = comment.username || 'User';

    const time = document.createElement('span');
    time.className = 'comment-time muted';
    time.textContent = timeAgo(comment.created_at);
    if (comment.edited_at) {
        time.textContent += ' (edited)';
    }

    header.appendChild(user);
    header.appendChild(time);

    const body = document.createElement('div');
    body.className = 'comment-body';
    body.textContent = comment.body || '';

    const actions = document.createElement('div');
    actions.className = 'comment-actions';

    const canReply = depth < 3;
    if (window.MICAHPOD_USER && canReply) {
        const replyBtn = document.createElement('button');
        replyBtn.className = 'comment-action';
        replyBtn.type = 'button';
        replyBtn.textContent = 'Reply';
        replyBtn.addEventListener('click', () => {
            const existing = wrapper.querySelector('.comment-form-inline.reply-form');
            if (existing) {
                existing.remove();
                return;
            }
            const snippet = comment.body ? comment.body.slice(0, 140) : '';
            const quoteText = snippet ? `Replying to ${comment.username}: "${snippet}${comment.body.length > 140 ? '...' : ''}"` : '';
            const form = buildInlineForm({
                initialValue: '',
                submitLabel: 'Post Reply',
                quoteText,
                onSubmit: (value, textarea, submit) => {
                    if (!value) return;
                    submit.disabled = true;
                    postComment(activeDiscussionId, comment.id, value).then(res => {
                        if (res.error) {
                            submit.disabled = false;
                            return;
                        }
                        loadComments(activeDiscussionId);
                    }).catch(() => {
                        submit.disabled = false;
                    });
                },
            });
            form.classList.add('reply-form');
            wrapper.appendChild(form);
        });
        actions.appendChild(replyBtn);
    }

    const currentUser = window.MICAHPOD_USER;
    const isOwner = currentUser && Number(comment.user_id) === Number(currentUser.id);
    const isAdmin = currentUser && currentUser.role === 'admin';
    if (isOwner) {
        const editBtn = document.createElement('button');
        editBtn.className = 'comment-action';
        editBtn.type = 'button';
        editBtn.textContent = 'Edit';
        editBtn.addEventListener('click', () => {
            const existing = wrapper.querySelector('.comment-form-inline.edit-form');
            if (existing) {
                existing.remove();
                return;
            }
            const form = buildInlineForm({
                initialValue: comment.body || '',
                submitLabel: 'Save',
                onSubmit: (value, textarea, submit) => {
                    if (!value) return;
                    submit.disabled = true;
                    editComment(comment.id, value).then(res => {
                        if (res.error) {
                            submit.disabled = false;
                            return;
                        }
                        loadComments(activeDiscussionId);
                    }).catch(() => {
                        submit.disabled = false;
                    });
                },
            });
            form.classList.add('edit-form');
            wrapper.appendChild(form);
        });
        actions.appendChild(editBtn);
    }
    if (isOwner || isAdmin) {
        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'comment-action danger';
        deleteBtn.type = 'button';
        deleteBtn.textContent = 'Delete';
        deleteBtn.addEventListener('click', () => {
            if (!confirm('Delete this comment?')) return;
            deleteBtn.disabled = true;
            deleteComment(comment.id).then(res => {
                if (res.error) {
                    deleteBtn.disabled = false;
                    return;
                }
                loadComments(activeDiscussionId);
            }).catch(() => {
                deleteBtn.disabled = false;
            });
        });
        actions.appendChild(deleteBtn);
    }

    card.appendChild(header);
    card.appendChild(body);
    card.appendChild(actions);
    wrapper.appendChild(card);

    if (comment.replies && comment.replies.length) {
        const replies = document.createElement('div');
        replies.className = 'comment-replies';
        comment.replies.forEach(reply => {
            replies.appendChild(renderCommentTree(reply, Math.min(depth + 1, 3)));
        });
        wrapper.appendChild(replies);
    }

    return wrapper;
}

function loadPodcasts() {
    apiGet('api/podcasts.php').then(data => {
        const select = document.getElementById('filter-podcast');
        allPodcasts = data.podcasts || [];
        if (!select) return;
        select.innerHTML = '<option value="">All Podcasts</option>';
        allPodcasts.forEach(p => {
            const opt = document.createElement('option');
            opt.value = p.id;
            opt.textContent = p.title;
            select.appendChild(opt);
        });
        if (subscriptionPanel) {
            renderSubscriptionPanel();
        }
    });
}

function updateSubscriptionCount() {
    const count = subscriptionIds.size;
    if (subscriptionCount) subscriptionCount.textContent = String(count);
    if (subscriptionCountPanel) subscriptionCountPanel.textContent = String(count);
}

function buildPodcastCard(podcast, options = {}) {
    const card = document.createElement('article');
    card.className = 'podcast-card';
    card.tabIndex = 0;
    card.setAttribute('role', 'button');
    card.setAttribute('aria-label', `View episodes for ${podcast.title || 'podcast'}`);

    const art = document.createElement('img');
    art.className = 'podcast-card-art';
    art.loading = 'lazy';
    art.src = podcast.artwork_url || 'assets/logo.png';

    const body = document.createElement('div');
    body.className = 'podcast-card-body';

    const title = document.createElement('div');
    title.className = 'podcast-card-title';
    title.textContent = podcast.title || '';

    const meta = document.createElement('div');
    meta.className = 'podcast-card-meta';
    const episodeCount = typeof podcast.episode_count === 'number' ? podcast.episode_count : 0;
    let metaText = `${episodeCount} episode${episodeCount === 1 ? '' : 's'}`;
    if (options.subscriberCount) {
        metaText += ` · ${options.subscriberCount} listeners`;
    }
    meta.textContent = metaText;

    const openHint = document.createElement('div');
    openHint.className = 'podcast-card-open muted';
    openHint.textContent = 'View episodes';

    const button = document.createElement('button');
    const isSubscribed = subscriptionIds.has(String(podcast.id));
    button.className = isSubscribed ? 'subscribe-toggle subscribed' : 'subscribe-toggle';
    button.type = 'button';
    button.textContent = isSubscribed ? 'Subscribed' : 'Subscribe';
    button.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleSubscription(podcast.id);
    });

    const openPodcast = () => openPodcastView(podcast);
    card.addEventListener('click', openPodcast);
    card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            openPodcast();
        }
    });

    body.appendChild(title);
    body.appendChild(meta);
    body.appendChild(openHint);
    card.appendChild(art);
    card.appendChild(body);
    card.appendChild(button);
    return card;
}

function renderSubscriptionPanel() {
    if (!subscriptionGrid) return;
    subscriptionGrid.innerHTML = '';
    if (!allPodcasts.length) {
        const empty = document.createElement('div');
        empty.className = 'muted';
        empty.textContent = 'No featured podcasts available yet.';
        subscriptionGrid.appendChild(empty);
    } else {
        allPodcasts.forEach(podcast => {
            subscriptionGrid.appendChild(buildPodcastCard(podcast));
        });
    }
    if (communityGrid && communitySection) {
        communityGrid.innerHTML = '';
        if (communityPodcasts.length) {
            communitySection.classList.remove('hidden');
            communityPodcasts.forEach(podcast => {
                communityGrid.appendChild(buildPodcastCard(podcast, {
                    subscriberCount: podcast.subscriber_count,
                }));
            });
        } else {
            communitySection.classList.add('hidden');
        }
    }
    updateSubscriptionCount();
}

function loadCommunityPodcasts() {
    if (!window.MICAHPOD_USER) return;
    apiGet('api/podcasts.php?action=community').then(data => {
        communityPodcasts = data.podcasts || [];
        renderSubscriptionPanel();
    }).catch(() => {
        communityPodcasts = [];
    });
}

function updateEmptyLibraryCta(data) {
    if (!emptyLibraryCta) return;
    const showEmpty = !!(data && data.library_empty) && !state.podcastId && !state.showAll && !state.search;
    const showHidden = !!(data && data.feed_hidden_only) && !state.podcastId && !state.showAll;
    if (showEmpty) {
        emptyLibraryCta.classList.remove('hidden');
        emptyLibraryCta.querySelector('h2').textContent = 'Your feed is ready for something new';
        emptyLibraryCta.querySelector('p').textContent =
            'Looks like you’ve cleared your library. Add a featured show, see what other listeners enjoy, or bring your own feed.';
    } else if (showHidden) {
        emptyLibraryCta.classList.remove('hidden');
        emptyLibraryCta.querySelector('h2').textContent = 'Everything in your library is hidden from the feed';
        emptyLibraryCta.querySelector('p').textContent =
            'You’re still subscribed — open Settings to turn “Show in home feed” back on, or browse a specific podcast.';
    } else {
        emptyLibraryCta.classList.add('hidden');
    }
}

function updatePodcastFocusUI() {
    const focus = document.getElementById('podcast-focus');
    if (!focus) return;
    const podcast = allPodcasts.find(p => String(p.id) === String(state.podcastId));
    if (!state.podcastId || !podcast) {
        focus.classList.add('hidden');
        focus.hidden = true;
        return;
    }
    const art = document.getElementById('podcast-focus-art');
    const title = document.getElementById('podcast-focus-title');
    const meta = document.getElementById('podcast-focus-meta');
    if (art) art.src = podcast.artwork_url || 'assets/logo.png';
    if (title) title.textContent = podcast.title || 'Podcast';
    if (meta) {
        const episodeCount = typeof podcast.episode_count === 'number' ? podcast.episode_count : 0;
        meta.textContent = `${episodeCount} episode${episodeCount === 1 ? '' : 's'} · sorted by ${state.sort || 'newest'}`;
    }
    focus.classList.remove('hidden');
    focus.hidden = false;
}

function openPodcastView(podcast) {
    if (!podcast) return;
    state.podcastId = String(podcast.id);
    state.page = 1;
    state.sort = state.sort || 'newest';
    state.showAll = true;
    const podcastSelect = document.getElementById('filter-podcast');
    if (podcastSelect) podcastSelect.value = String(podcast.id);
    const sortSelect = document.getElementById('filter-sort');
    if (sortSelect) sortSelect.value = state.sort;
    const showAllToggle = document.getElementById('filter-show-all');
    if (showAllToggle) showAllToggle.checked = true;
    if (subscriptionPanel) subscriptionPanel.classList.add('hidden');
    const subscriptionsLink = document.getElementById('nav-my-podcasts');
    if (subscriptionsLink) subscriptionsLink.classList.remove('active');
    closeDiscussion();
    updatePodcastFocusUI();
    loadEpisodes();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearPodcastView() {
    // Leaves the homepage feed (all subscribed shows), not "every episode of this podcast".
    state.podcastId = '';
    state.page = 1;
    const podcastSelect = document.getElementById('filter-podcast');
    if (podcastSelect) podcastSelect.value = '';
    updatePodcastFocusUI();
    loadEpisodes();
}

function loadSubscriptions() {
    if (!window.MICAHPOD_USER) return;
    apiGet('api/subscriptions.php').then(data => {
        const rows = data.subscriptions || [];
        subscriptionIds = new Set(rows.map(row => String(row.id)));
        subscriptionMeta = {};
        rows.forEach(row => {
            subscriptionMeta[String(row.id)] = row;
        });
        subscriptionsLoaded = true;
        renderSubscriptionPanel();
        loadCommunityPodcasts();
    });
}

function toggleSubscription(podcastId) {
    if (!window.MICAHPOD_USER) return;
    const key = String(podcastId);
    const wasSubscribed = subscriptionIds.has(key);
    if (wasSubscribed) {
        subscriptionIds.delete(key);
    } else {
        subscriptionIds.add(key);
    }
    renderSubscriptionPanel();
    const action = wasSubscribed ? 'unsubscribe' : 'subscribe';
    apiSend('api/subscriptions.php', 'POST', { action, podcast_id: podcastId })
        .then(() => loadEpisodes())
        .catch(() => {
            if (wasSubscribed) {
                subscriptionIds.add(key);
            } else {
                subscriptionIds.delete(key);
            }
            renderSubscriptionPanel();
        });
}

function loadCommentCountsForEpisodes(episodes) {
    if (!episodes || episodes.length === 0) {
        return Promise.resolve({});
    }
    const ids = episodes.map(ep => ep.id).join(',');
    return apiGet(`api/comments.php?action=counts&episode_ids=${ids}`).then(data => data.counts || {});
}

function loadEpisodes() {
    const params = new URLSearchParams({
        page: state.page,
        per_page: state.perPage,
        sort: state.sort,
    });
    if (state.podcastId) params.set('podcast_id', state.podcastId);
    if (state.rated !== '') params.set('rated', state.rated);
    if (state.played !== '') params.set('played', state.played);
    if (state.recommended) params.set('recommended', '1');
    if (state.showAll) params.set('show_all', '1');
    if (state.tag) params.set('tag', state.tag);
    if (state.search) params.set('q', state.search);

    apiGet(`api/episodes.php?${params.toString()}`).then(data => {
        const episodes = data.episodes || [];
        const pageCount = Math.max(1, Math.ceil((data.total || 0) / (data.per_page || state.perPage)));
        currentEpisodeMap = {};
        episodes.forEach(ep => {
            currentEpisodeMap[ep.id] = ep;
        });
        updateEmptyLibraryCta(data);
        const renderEpisodes = () => {
            episodeGrid.innerHTML = '';
            let visible = episodes;
            if (userSettings?.hide_played) {
                visible = episodes.filter(ep => !ep.completed);
            }
            if (!visible.length && !data.library_empty && !data.feed_hidden_only) {
                const empty = document.createElement('div');
                empty.className = 'muted';
                empty.textContent = 'No episodes match these filters.';
                episodeGrid.appendChild(empty);
            }
            visible.forEach(ep => episodeGrid.appendChild(renderEpisode(ep)));
            pageInfo.textContent = `Page ${data.page} of ${pageCount}`;
            document.getElementById('prev-page').disabled = state.page <= 1;
            document.getElementById('next-page').disabled = state.page >= pageCount;
        };

        const countsPromise = loadCommentCountsForEpisodes(episodes).then(counts => {
            Object.keys(counts).forEach(key => {
                commentCounts[key] = counts[key];
            });
            episodes.forEach(ep => {
                const count = counts[ep.id] ?? 0;
                ep.comment_count = count;
            });
        }).catch(() => {
            episodes.forEach(ep => {
                ep.comment_count = ep.comment_count || 0;
            });
        });

        if (!window.MICAHPOD_USER || episodes.length === 0) {
            countsPromise.then(renderEpisodes).catch(() => renderEpisodes());
            return;
        }
        const ids = episodes.map(ep => ep.id).join(',');
        const progressPromise = apiGet(`api/progress.php?episode_ids=${ids}`).then(progressData => {
            const progressMap = progressData.progress || {};
            episodes.forEach(ep => {
                const progress = progressMap[ep.id];
                if (progress) {
                    ep.completed = !!progress.completed;
                    ep.position_seconds = progress.position_seconds;
                } else {
                    ep.completed = false;
                }
            });
        });
        Promise.all([countsPromise, progressPromise]).then(renderEpisodes).catch(() => renderEpisodes());
    });
}

document.getElementById('prev-page').addEventListener('click', () => {
    state.page = Math.max(1, state.page - 1);
    loadEpisodes();
});
document.getElementById('next-page').addEventListener('click', () => {
    state.page += 1;
    loadEpisodes();
});

document.getElementById('filter-podcast').addEventListener('change', e => {
    state.podcastId = e.target.value;
    state.page = 1;
    if (state.podcastId) {
        state.showAll = true;
        const showAllToggle = document.getElementById('filter-show-all');
        if (showAllToggle) showAllToggle.checked = true;
    }
    updatePodcastFocusUI();
    loadEpisodes();
});
document.getElementById('filter-sort').addEventListener('change', e => {
    state.sort = e.target.value;
    state.page = 1;
    updatePodcastFocusUI();
    loadEpisodes();
});
document.getElementById('filter-rated').addEventListener('change', e => {
    state.rated = e.target.value;
    state.page = 1;
    loadEpisodes();
});
document.getElementById('filter-played').addEventListener('change', e => {
    state.played = e.target.value;
    state.page = 1;
    loadEpisodes();
});
document.getElementById('filter-recommended').addEventListener('change', e => {
    state.recommended = e.target.checked;
    state.page = 1;
    loadEpisodes();
});
const showAllToggle = document.getElementById('filter-show-all');
if (showAllToggle) {
    showAllToggle.addEventListener('change', e => {
        state.showAll = e.target.checked;
        state.page = 1;
        loadEpisodes();
    });
}
document.getElementById('search-input').addEventListener('input', e => {
    state.search = e.target.value;
    state.page = 1;
    loadEpisodes();
});

const navToggle = document.getElementById('nav-toggle');
if (navToggle) {
    navToggle.addEventListener('click', () => {
        document.querySelector('.topbar')?.classList.toggle('nav-open');
    });
}

const refreshBtn = document.getElementById('refresh-main');
const refreshStatus = document.getElementById('refresh-status');
if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
        refreshBtn.disabled = true;
        refreshBtn.textContent = 'Refreshing...';
        if (refreshStatus) refreshStatus.textContent = 'Refreshing feeds...';
        apiSend('api/refresh.php', 'POST', {}).then(data => {
            const added = typeof data.new_episodes === 'number' ? data.new_episodes : 0;
            if (refreshStatus) {
                refreshStatus.textContent = `Refresh complete • ${added} new`;
            }
            refreshBtn.disabled = false;
            refreshBtn.textContent = '⟳ Refresh';
            loadEpisodes();
        }).catch(() => {
            if (refreshStatus) refreshStatus.textContent = 'Refresh failed';
            refreshBtn.disabled = false;
            refreshBtn.textContent = '⟳ Refresh';
        });
    });
}

const ratingsLink = document.getElementById('nav-my-ratings');
if (ratingsLink) {
    ratingsLink.addEventListener('click', e => {
        e.preventDefault();
        state.rated = '1';
        document.getElementById('filter-rated').value = '1';
        state.page = 1;
        loadEpisodes();
    });
}
const subscriptionsLink = document.getElementById('nav-my-podcasts');
if (subscriptionsLink && subscriptionPanel) {
    const closeBtn = document.getElementById('subscription-close');
    const togglePanel = () => {
        const isHidden = subscriptionPanel.classList.toggle('hidden');
        subscriptionsLink.classList.toggle('active', !isHidden);
        if (!isHidden && !subscriptionsLoaded) {
            loadSubscriptions();
        }
    };
    subscriptionsLink.addEventListener('click', e => {
        e.preventDefault();
        togglePanel();
    });
    if (closeBtn) {
        closeBtn.addEventListener('click', () => {
            subscriptionPanel.classList.add('hidden');
            subscriptionsLink.classList.remove('active');
        });
    }
}

if (discussionBack) {
    discussionBack.addEventListener('click', () => closeDiscussion());
}
if (discussionSubmit) {
    discussionSubmit.addEventListener('click', () => {
        if (!window.MICAHPOD_USER || !activeDiscussionId) return;
        const body = discussionInput ? discussionInput.value.trim() : '';
        if (!body) return;
        discussionSubmit.disabled = true;
        postComment(activeDiscussionId, null, body).then(res => {
            discussionSubmit.disabled = false;
            if (res.error) return;
            if (discussionInput) discussionInput.value = '';
            loadComments(activeDiscussionId);
        }).catch(() => {
            discussionSubmit.disabled = false;
        });
    });
}

function markEpisodePlayed(episodeId) {
    if (!window.MICAHPOD_USER || !episodeId) return Promise.resolve(null);
    return apiSend('api/progress.php', 'POST', {
        action: 'mark_played',
        episode_id: episodeId,
    }).then(data => {
        if (data?.error) {
            if (player?.showToast) player.showToast('Could not mark as played');
            return data;
        }
        if (player?.showToast) player.showToast('Marked as played');
        const current = player?.getState?.();
        if (current && String(current.id) === String(episodeId) && typeof player.clearLocalPlayerSession === 'function') {
            player.clearLocalPlayerSession();
        }
        loadContinueListening();
        loadEpisodes();
        return data;
    }).catch(() => {
        if (player?.showToast) player.showToast('Could not mark as played');
        return null;
    });
}

function loadContinueListening() {
    const section = document.getElementById('continue-listening');
    const list = document.getElementById('continue-listening-list');
    const countEl = document.getElementById('continue-listening-count');
    if (!window.MICAHPOD_USER || !section || !list) return Promise.resolve();
    return apiGet('api/progress.php?action=in_progress&limit=12').then(data => {
        const episodes = data.episodes || [];
        list.innerHTML = '';
        if (!episodes.length) {
            section.classList.add('hidden');
            if (countEl) countEl.textContent = '';
            return;
        }
        section.classList.remove('hidden');
        if (countEl) countEl.textContent = `${episodes.length} episode${episodes.length === 1 ? '' : 's'}`;
        episodes.forEach(ep => {
            const card = document.createElement('article');
            card.className = 'continue-card';

            const art = document.createElement('img');
            art.className = 'continue-card-art';
            art.src = ep.artwork_url || ep.podcast_artwork || 'assets/logo.png';
            art.alt = '';
            art.width = 64;
            art.height = 64;
            art.loading = 'lazy';

            const body = document.createElement('div');
            body.className = 'continue-card-body';

            const show = document.createElement('div');
            show.className = 'continue-card-show muted';
            show.textContent = ep.podcast_title || '';

            const title = document.createElement('div');
            title.className = 'continue-card-title';
            title.textContent = ep.title || 'Episode';

            const meta = document.createElement('div');
            meta.className = 'continue-card-meta muted';
            const at = player.formatTime(ep.position_seconds || 0);
            const duration = ep.duration_seconds ? ` / ${player.formatTime(ep.duration_seconds)}` : '';
            meta.textContent = `At ${at}${duration}`;

            const actions = document.createElement('div');
            actions.className = 'continue-card-actions';

            const resumeBtn = document.createElement('button');
            resumeBtn.className = 'btn btn-primary btn-small';
            resumeBtn.type = 'button';
            resumeBtn.textContent = 'Resume';
            resumeBtn.addEventListener('click', () => {
                player.loadEpisode(ep, { startAt: Number(ep.position_seconds) || 0 });
            });

            const playedBtn = document.createElement('button');
            playedBtn.className = 'btn btn-small continue-mark-played';
            playedBtn.type = 'button';
            playedBtn.textContent = 'Mark Played';
            playedBtn.title = 'Remove from Continue Listening';
            playedBtn.addEventListener('click', () => {
                playedBtn.disabled = true;
                markEpisodePlayed(ep.id || ep.episode_id).finally(() => {
                    playedBtn.disabled = false;
                });
            });

            actions.appendChild(resumeBtn);
            actions.appendChild(playedBtn);
            body.appendChild(show);
            body.appendChild(title);
            body.appendChild(meta);
            body.appendChild(actions);
            card.appendChild(art);
            card.appendChild(body);
            list.appendChild(card);
        });
    }).catch(() => {
        section.classList.add('hidden');
    });
}

window.refreshContinueListening = loadContinueListening;

const podcastFocusClear = document.getElementById('podcast-focus-clear');
if (podcastFocusClear) {
    podcastFocusClear.addEventListener('click', () => clearPodcastView());
}

document.getElementById('cta-open-podcasts')?.addEventListener('click', () => {
    if (subscriptionPanel) subscriptionPanel.classList.remove('hidden');
    document.getElementById('nav-my-podcasts')?.classList.add('active');
    subscriptionPanel?.scrollIntoView({ behavior: 'smooth', block: 'start' });
});

initEpisodeColumns();
loadPodcasts();

if (window.MICAHPOD_USER) {
    loadSubscriptions();
    loadContinueListening();
    apiGet('api/settings.php').then(data => {
        userSettings = data.settings || {};
        if (userSettings.default_recommended_only) {
            state.recommended = true;
            const recommendedToggle = document.getElementById('filter-recommended');
            if (recommendedToggle) recommendedToggle.checked = true;
        }
        loadEpisodes();
    }).catch(() => loadEpisodes());
} else {
    loadEpisodes();
}
