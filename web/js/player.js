class MicahPlayer {
    constructor() {
        this.audio = document.getElementById('audio-element');
        this.playerBar = document.getElementById('player-bar');
        this.playBtn = document.getElementById('player-play');
        this.backBtn = document.getElementById('player-back');
        this.forwardBtn = document.getElementById('player-forward');
        this.progress = document.getElementById('player-progress');
        this.volume = document.getElementById('player-volume');
        this.speed = document.getElementById('player-speed');
        this.title = document.getElementById('player-title');
        this.podcast = document.getElementById('player-podcast');
        this.art = document.getElementById('player-art');
        this.time = document.getElementById('player-time');
        this.fullscreen = document.getElementById('player-fullscreen');
        this.fullscreenToggle = document.getElementById('player-fullscreen-toggle');
        this.fullscreenClose = document.getElementById('player-fullscreen-close');
        this.fullTitle = document.getElementById('player-full-title');
        this.fullPodcast = document.getElementById('player-full-podcast');
        this.fullArt = document.getElementById('player-full-art');
        this.fullPlayBtn = document.getElementById('player-play-full');
        this.fullBackBtn = document.getElementById('player-back-full');
        this.fullForwardBtn = document.getElementById('player-forward-full');
        this.fullProgress = document.getElementById('player-progress-full');
        this.fullTime = document.getElementById('player-time-full');
        this.fullSpeed = document.getElementById('player-speed-full');
        this.fullVolume = document.getElementById('player-volume-full');
        this.fullCastBtn = document.getElementById('player-cast-full');
        this.prevTrackBtn = document.getElementById('player-prev-track');
        this.nextTrackBtn = document.getElementById('player-next-track');
        this.fullPrevTrackBtn = document.getElementById('player-prev-track-full');
        this.fullNextTrackBtn = document.getElementById('player-next-track-full');
        this.queueList = document.getElementById('player-queue-list');
        this.queueEmpty = document.getElementById('player-queue-empty');
        this.queueCount = document.getElementById('player-queue-count');
        this.queueExpandBtn = document.getElementById('player-queue-expand');
        this.queueOverlay = document.getElementById('player-queue-overlay');
        this.queueOverlayList = document.getElementById('player-queue-overlay-list');
        this.queueOverlayEmpty = document.getElementById('player-queue-overlay-empty');
        this.queueOverlayCount = document.getElementById('player-queue-overlay-count');
        this.queueOverlayClose = document.getElementById('player-queue-close');
        this.queueOverlayClear = document.getElementById('player-queue-clear');
        this.toastContainer = document.getElementById('toast-container');
        this.extrasToggle = document.getElementById('player-extras-toggle');
        this.closeBtn = document.getElementById('player-close');
        this.showBtn = document.getElementById('player-show');
        this.miniPlayBtn = document.getElementById('player-mini-play');
        this.castBtn = document.getElementById('player-cast');
        this.castingLabel = document.getElementById('player-casting');
        this.stateKey = 'micahpod_player_state';
        this.queueKey = 'micahpod_queue';
        this.csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
        this.user = window.MICAHPOD_USER || null;
        this.saveDebounceMs = 12000;
        this.serverSaveTimer = null;
        this.lastServerSave = 0;
        this.restoreInProgress = false;
        this.castContext = null;
        this.castSession = null;
        this.castPlayer = null;
        this.castController = null;
        this.castAvailable = false;
        this.castSaveAt = 0;
        this.currentEpisode = null;
        this.playHistory = [];
        this.queue = this.getQueue();
        this.dragState = null;
        this.boundQueueTouchMove = this.handleQueueTouchMove.bind(this);
        this.boundQueueTouchEnd = this.handleQueueTouchEnd.bind(this);
        this.bind();
        this.restore();
        this.renderQueue();
        this.setupCastAvailability();
    }

    bind() {
        this.playBtn.addEventListener('click', () => {
            this.togglePlay();
        });
        if (this.fullPlayBtn) {
            this.fullPlayBtn.addEventListener('click', () => {
                this.togglePlay();
            });
        }
        this.backBtn.addEventListener('click', () => {
            this.seekBy(-30);
        });
        if (this.fullBackBtn) {
            this.fullBackBtn.addEventListener('click', () => {
                this.seekBy(-30);
            });
        }
        this.forwardBtn.addEventListener('click', () => {
            this.seekBy(30);
        });
        if (this.fullForwardBtn) {
            this.fullForwardBtn.addEventListener('click', () => {
                this.seekBy(30);
            });
        }
        if (this.prevTrackBtn) {
            this.prevTrackBtn.addEventListener('click', () => this.playPreviousTrack());
        }
        if (this.nextTrackBtn) {
            this.nextTrackBtn.addEventListener('click', () => this.playNextTrack());
        }
        if (this.fullPrevTrackBtn) {
            this.fullPrevTrackBtn.addEventListener('click', () => this.playPreviousTrack());
        }
        if (this.fullNextTrackBtn) {
            this.fullNextTrackBtn.addEventListener('click', () => this.playNextTrack());
        }
        this.audio.addEventListener('timeupdate', () => {
            if (this.isCasting()) return;
            if (!isNaN(this.audio.duration) && this.audio.duration > 0) {
                this.progress.value = (this.audio.currentTime / this.audio.duration) * 100;
                if (this.fullProgress) {
                    this.fullProgress.value = this.progress.value;
                }
                this.save();
            }
            this.updateTime();
            this.updatePositionState();
        });
        this.audio.addEventListener('loadedmetadata', () => {
            if (this.isCasting()) return;
            this.updateTime();
            this.updatePositionState();
        });
        this.audio.addEventListener('durationchange', () => this.updateTime());
        this.audio.addEventListener('ended', () => this.handleTrackEnded());
        if (this.miniPlayBtn) {
            this.miniPlayBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.togglePlay();
            });
        }
        this.audio.addEventListener('play', () => {
            if (this.isCasting()) return;
            this.playBtn.textContent = '⏸';
            this.playBtn.setAttribute('aria-label', 'Pause');
            if (this.miniPlayBtn) this.miniPlayBtn.textContent = '⏸';
            if (this.fullPlayBtn) {
                this.fullPlayBtn.textContent = '⏸';
                this.fullPlayBtn.setAttribute('aria-label', 'Pause');
            }
        });
        this.audio.addEventListener('pause', () => {
            if (this.isCasting()) return;
            this.playBtn.textContent = '▶';
            this.playBtn.setAttribute('aria-label', 'Play');
            if (this.miniPlayBtn) this.miniPlayBtn.textContent = '▶';
            if (this.fullPlayBtn) {
                this.fullPlayBtn.textContent = '▶';
                this.fullPlayBtn.setAttribute('aria-label', 'Play');
            }
            this.saveToServer();
        });
        this.progress.addEventListener('input', () => {
            const duration = this.getDuration();
            if (!isNaN(duration) && duration > 0) {
                this.seekTo((this.progress.value / 100) * duration);
            }
            if (this.fullProgress) {
                this.fullProgress.value = this.progress.value;
            }
            this.updateTime();
        });
        if (this.fullProgress) {
            this.fullProgress.addEventListener('input', () => {
                const duration = this.getDuration();
                if (!isNaN(duration) && duration > 0) {
                    this.seekTo((this.fullProgress.value / 100) * duration);
                }
                this.progress.value = this.fullProgress.value;
                this.updateTime();
            });
        }
        this.volume.addEventListener('input', () => {
            const level = parseFloat(this.volume.value);
            if (this.isCasting() && this.castController && this.castPlayer) {
                this.castPlayer.volumeLevel = level;
                this.castController.setVolumeLevel();
            } else {
                this.audio.volume = level;
            }
            if (this.fullVolume) {
                this.fullVolume.value = String(level);
            }
            this.save();
        });
        if (this.fullVolume) {
            this.fullVolume.addEventListener('input', () => {
                const level = parseFloat(this.fullVolume.value);
                if (this.isCasting() && this.castController && this.castPlayer) {
                    this.castPlayer.volumeLevel = level;
                    this.castController.setVolumeLevel();
                } else {
                    this.audio.volume = level;
                }
                this.volume.value = String(level);
                this.save();
            });
        }
        this.speed.addEventListener('change', () => {
            this.audio.playbackRate = parseFloat(this.speed.value);
            if (this.fullSpeed) {
                this.fullSpeed.value = this.speed.value;
            }
            this.save();
        });
        if (this.fullSpeed) {
            this.fullSpeed.addEventListener('change', () => {
                const rate = parseFloat(this.fullSpeed.value);
                this.audio.playbackRate = rate;
                this.speed.value = String(rate);
                this.save();
            });
        }
        if (this.extrasToggle) {
            this.extrasToggle.addEventListener('click', () => {
                this.playerBar?.classList.toggle('show-extras');
            });
        }
        if (this.closeBtn) {
            this.closeBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.playerBar?.classList.add('minimized');
                localStorage.setItem('micahpod_player_minimized', '1');
                // Audio keeps playing!
            });
        }
        if (this.fullscreenToggle) {
            this.fullscreenToggle.addEventListener('click', (e) => {
                e.stopPropagation();
                this.openFullscreen();
            });
        }
        if (this.fullscreenClose) {
            this.fullscreenClose.addEventListener('click', () => {
                this.closeFullscreen();
            });
        }
        if (this.queueExpandBtn) {
            this.queueExpandBtn.addEventListener('click', () => {
                this.openQueueOverlay();
            });
        }
        if (this.queueOverlayClose) {
            this.queueOverlayClose.addEventListener('click', () => {
                this.closeQueueOverlay();
            });
        }
        if (this.queueOverlayClear) {
            this.queueOverlayClear.addEventListener('click', () => {
                this.clearQueue();
            });
        }
        // Click the minimized bar to expand
        if (this.playerBar) {
            this.playerBar.addEventListener('click', (e) => {
                if (this.playerBar.classList.contains('minimized')) {
                    this.playerBar.classList.remove('minimized');
                    localStorage.removeItem('micahpod_player_minimized');
                }
            });
        }
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                if (this.queueOverlay?.classList.contains('open')) {
                    this.closeQueueOverlay();
                } else if (this.fullscreen?.classList.contains('open')) {
                    this.closeFullscreen();
                }
            }
        });
        // Save state to server when leaving or switching tabs
        window.addEventListener('beforeunload', () => {
            this.save();
            // Use sendBeacon for reliable delivery on page close
            if (this.user && navigator.sendBeacon) {
                const state = this.getState();
                if (state && state.audio_url) {
                    const payload = JSON.stringify({
                        episode_id: state.id || null,
                        position_seconds: this.getCurrentTime() || state.current_time || 0,
                        queue: this.getQueue(),
                        speed: this.audio.playbackRate,
                        volume: this.audio.volume,
                    });
                    navigator.sendBeacon('api/player_state.php?beacon=1', new Blob([payload], { type: 'application/json' }));
                }
            }
        });
        document.addEventListener('visibilitychange', () => {
            if (document.hidden && this.user) {
                this.saveToServer();
            }
        });
    }

    loadEpisode(episode, options = {}) {
        if (!episode || !episode.audio_url) return;
        if (this.currentEpisode && this.currentEpisode.audio_url && this.currentEpisode.audio_url !== episode.audio_url) {
            this.saveToServer();
        }
        if (!options.skipHistory && this.currentEpisode && this.currentEpisode.audio_url && this.currentEpisode.audio_url !== episode.audio_url) {
            this.playHistory.push(this.currentEpisode);
            if (this.playHistory.length > 50) {
                this.playHistory.shift();
            }
        }
        this.currentEpisode = episode;
        this.audio.src = episode.audio_url || '';
        this.title.textContent = episode.title;
        this.podcast.textContent = episode.podcast_title || '';
        this.art.src = episode.artwork_url || episode.podcast_artwork || 'assets/logo.svg';
        if (this.fullTitle) this.fullTitle.textContent = episode.title;
        if (this.fullPodcast) this.fullPodcast.textContent = episode.podcast_title || '';
        if (this.fullArt) this.fullArt.src = episode.artwork_url || episode.podcast_artwork || 'assets/logo.svg';
        this.progress.value = 0;
        if (this.fullProgress) this.fullProgress.value = 0;
        this.updateTime();
        if (this.isCasting()) {
            this.audio.pause();
            this.loadCastMedia(episode, 0);
        } else {
            this.audio.play().catch(() => {});
        }
        this.save(episode);
        this.saveToServer();
        this.setPlayerArt(episode.artwork_url || episode.podcast_artwork || '');
        this.updateMediaSession(episode);
        // Always expand when loading a new episode
        this.playerBar?.classList.remove('minimized');
        localStorage.removeItem('micahpod_player_minimized');
    }

    updateMediaSession(episode) {
        if (!('mediaSession' in navigator)) return;

        const artwork = episode?.artwork_url || episode?.podcast_artwork || '';
        const artworkList = artwork ? [
            { src: artwork, sizes: '96x96', type: 'image/jpeg' },
            { src: artwork, sizes: '256x256', type: 'image/jpeg' },
            { src: artwork, sizes: '512x512', type: 'image/jpeg' },
        ] : [];

        navigator.mediaSession.metadata = new MediaMetadata({
            title: episode?.title || 'MicahPod',
            artist: episode?.podcast_title || '',
            album: 'MicahPod',
            artwork: artworkList,
        });

        navigator.mediaSession.setActionHandler('play', () => {
            this.play();
        });
        navigator.mediaSession.setActionHandler('pause', () => {
            this.pause();
        });
        navigator.mediaSession.setActionHandler('seekbackward', () => {
            this.seekBy(-30);
        });
        navigator.mediaSession.setActionHandler('seekforward', () => {
            this.seekBy(30);
        });
        navigator.mediaSession.setActionHandler('previoustrack', () => {
            this.playPreviousTrack();
        });
        navigator.mediaSession.setActionHandler('nexttrack', () => {
            this.playNextTrack();
        });
        navigator.mediaSession.setActionHandler('seekto', (details) => {
            if (details.seekTime != null) {
                this.seekTo(details.seekTime);
            }
        });
        navigator.mediaSession.setActionHandler('stop', () => {
            this.pause();
            this.seekTo(0);
        });
    }

    save(episode) {
        if (this.restoreInProgress) return;
        const existing = this.getState();
        const state = {
            ...existing,
            id: episode?.id ?? existing?.id ?? null,
            title: episode?.title ?? existing?.title ?? '',
            podcast_title: episode?.podcast_title ?? existing?.podcast_title ?? '',
            artwork_url: episode?.artwork_url ?? existing?.artwork_url ?? '',
            podcast_artwork: episode?.podcast_artwork ?? existing?.podcast_artwork ?? '',
            audio_url: episode?.audio_url ?? existing?.audio_url ?? '',
            current_time: this.getCurrentTime() || existing?.current_time || 0,
            volume: this.isCasting() && this.castPlayer ? this.castPlayer.volumeLevel : this.audio.volume,
            speed: this.audio.playbackRate,
            updated_at: new Date().toISOString(),
        };
        localStorage.setItem(this.stateKey, JSON.stringify(state));
        this.queueServerSave();
    }

    getState() {
        try {
            return JSON.parse(localStorage.getItem(this.stateKey));
        } catch (err) {
            return null;
        }
    }

    parseUpdatedAt(value) {
        if (!value) return 0;
        const ts = Date.parse(value);
        return Number.isFinite(ts) ? ts : 0;
    }

    queueServerSave(force = false) {
        if (!this.user) return;
        if (force) {
            this.saveToServer();
            return;
        }
        if (this.serverSaveTimer) return;
        this.serverSaveTimer = setTimeout(() => {
            this.serverSaveTimer = null;
            this.saveToServer();
        }, this.saveDebounceMs);
    }

    saveToServer() {
        if (!this.user) return;
        const state = this.getState();
        if (!state || !state.audio_url) return;
        const payload = {
            episode_id: state.id || null,
            position_seconds: this.getCurrentTime() || state.current_time || 0,
            queue: this.getQueue(),
            speed: this.audio.playbackRate,
            volume: this.isCasting() && this.castPlayer ? this.castPlayer.volumeLevel : this.audio.volume,
        };
        fetch('api/player_state.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-Token': this.csrfToken,
            },
            credentials: 'same-origin',
            body: JSON.stringify(payload),
        }).then(r => r.json()).then(data => {
            if (data?.state?.updated_at) {
                const existing = this.getState() || {};
                localStorage.setItem(this.stateKey, JSON.stringify({
                    ...existing,
                    updated_at: data.state.updated_at,
                }));
            }
        }).catch(() => {});
    }

    fetchServerState() {
        if (!this.user) return Promise.resolve(null);
        return fetch('api/player_state.php', { credentials: 'same-origin' })
            .then(r => r.json())
            .then(data => data.state || null)
            .catch(() => null);
    }

    applyState(state, options = {}) {
        if (!state || !state.audio_url) return;
        this.restoreInProgress = true;
        this.currentEpisode = {
            id: state.episode_id ?? state.id ?? null,
            title: state.title || 'Now playing',
            podcast_title: state.podcast_title || '',
            artwork_url: state.artwork_url || '',
            podcast_artwork: state.podcast_artwork || '',
            audio_url: state.audio_url || '',
        };
        this.audio.src = state.audio_url;
        this.title.textContent = state.title || 'Now playing';
        this.podcast.textContent = state.podcast_title || '';
        this.art.src = state.artwork_url || state.podcast_artwork || 'assets/logo.svg';
        if (this.fullTitle) this.fullTitle.textContent = state.title || 'Now playing';
        if (this.fullPodcast) this.fullPodcast.textContent = state.podcast_title || '';
        if (this.fullArt) this.fullArt.src = state.artwork_url || state.podcast_artwork || 'assets/logo.svg';
        this.audio.currentTime = state.position_seconds ?? state.current_time ?? 0;
        this.audio.volume = state.volume ?? 1;
        this.audio.playbackRate = state.speed ?? 1;
        this.volume.value = this.audio.volume;
        this.speed.value = String(this.audio.playbackRate);
        if (this.fullVolume) this.fullVolume.value = String(this.audio.volume);
        if (this.fullSpeed) this.fullSpeed.value = String(this.audio.playbackRate);
        if (Array.isArray(state.queue)) {
            this.saveQueue(state.queue, { skipServer: true });
        }
        this.setPlayerArt(state.artwork_url || state.podcast_artwork || '');
        this.updateTime();
        this.updateMediaSession({
            title: state.title,
            podcast_title: state.podcast_title,
            artwork_url: state.artwork_url,
            podcast_artwork: state.podcast_artwork,
        });
        if (localStorage.getItem('micahpod_player_minimized') === '1') {
            this.playerBar?.classList.add('minimized');
        }
        this.restoreInProgress = false;
    }

    restore() {
        const state = this.getState();
        if (state && state.audio_url) {
            this.applyState({
                ...state,
                position_seconds: state.current_time ?? 0,
                episode_id: state.id ?? null,
            });
        }
        if (!this.user) {
            return;
        }
        this.fetchServerState().then(serverState => {
            if (!serverState || !serverState.audio_url) return;
            const localUpdated = this.parseUpdatedAt(state?.updated_at);
            const serverUpdated = this.parseUpdatedAt(serverState.updated_at);
            const localEpisodeId = state?.id ?? null;
            const serverEpisodeId = serverState.episode_id ?? serverState.id ?? null;
            // Always prefer server if episode changed (cross-device sync)
            const episodeChanged = serverEpisodeId && localEpisodeId && String(serverEpisodeId) !== String(localEpisodeId);
            if (!localUpdated || serverUpdated > localUpdated || episodeChanged) {
                this.applyState(serverState);
                const merged = {
                    ...state,
                    id: serverState.episode_id ?? serverState.id ?? null,
                    title: serverState.title ?? state?.title ?? '',
                    podcast_title: serverState.podcast_title ?? state?.podcast_title ?? '',
                    artwork_url: serverState.artwork_url ?? state?.artwork_url ?? '',
                    podcast_artwork: serverState.podcast_artwork ?? state?.podcast_artwork ?? '',
                    audio_url: serverState.audio_url ?? state?.audio_url ?? '',
                    current_time: serverState.position_seconds ?? 0,
                    volume: serverState.volume ?? 1,
                    speed: serverState.speed ?? 1,
                    updated_at: serverState.updated_at,
                };
                localStorage.setItem(this.stateKey, JSON.stringify(merged));
            } else if (localUpdated > serverUpdated) {
                this.saveToServer();
            }
        });
    }

    setPlayerArt(url) {
        if (!this.playerBar) return;
        if (url) {
            this.playerBar.style.setProperty('--player-bg', `url(${url})`);
            this.playerBar.classList.add('has-art');
            if (this.fullscreen) {
                this.fullscreen.style.setProperty('--player-bg', `url(${url})`);
            }
        } else {
            this.playerBar.classList.remove('has-art');
            if (this.fullscreen) {
                this.fullscreen.style.removeProperty('--player-bg');
            }
        }
    }

    updatePositionState() {
        if (!('mediaSession' in navigator) || !navigator.mediaSession.setPositionState) return;
        const duration = this.getDuration();
        if (!isFinite(duration) || duration <= 0) return;
        try {
            navigator.mediaSession.setPositionState({
                duration: duration,
                playbackRate: this.isCasting() ? 1 : this.audio.playbackRate,
                position: Math.min(this.getCurrentTime(), duration),
            });
        } catch (e) {}
    }

    formatTime(seconds) {
        if (!isFinite(seconds) || seconds < 0) return '0:00';
        const total = Math.floor(seconds);
        const hrs = Math.floor(total / 3600);
        const mins = Math.floor((total % 3600) / 60);
        const secs = total % 60;
        const mm = hrs > 0 ? String(mins).padStart(2, '0') : String(mins);
        return hrs > 0 ? `${hrs}:${mm}:${String(secs).padStart(2, '0')}` : `${mins}:${String(secs).padStart(2, '0')}`;
    }

    updateTime() {
        if (!this.time) return;
        const current = this.getCurrentTime();
        const duration = isFinite(this.getDuration()) ? this.getDuration() : 0;
        const durationText = duration ? this.formatTime(duration) : '--:--';
        this.time.textContent = `${this.formatTime(current)} / ${durationText}`;
        if (this.fullTime) {
            this.fullTime.textContent = `${this.formatTime(current)} / ${durationText}`;
        }
    }

    setupCastAvailability() {
        // Register globally so index.php callback can find us
        window.__micahPlayer = this;
        // Override the callback in case SDK hasn't fired yet
        window['__onGCastApiAvailable'] = (isAvailable) => {
            console.log('[MicahPod Player] Cast callback fired:', isAvailable);
            if (isAvailable) {
                this.initCast();
            }
        };
        // If Cast SDK already loaded and fired before us
        if (window.__castReady) {
            console.log('[MicahPod Player] Cast was already ready, initializing...');
            this.initCast();
        }
        // Also check directly
        if (window.cast && window.cast.framework) {
            console.log('[MicahPod Player] Cast framework found directly');
            this.initCast();
        }
    }

    initCast() {
        console.log('[MicahPod] initCast called. castAvailable:', this.castAvailable, 'cast:', !!window.cast, 'framework:', !!(window.cast && window.cast.framework));
        if (this.castAvailable) return;
        if (!window.cast || !window.cast.framework) {
            console.warn('[MicahPod] Cast framework not loaded yet');
            return;
        }
        this.castAvailable = true;
        console.log('[MicahPod] Cast initialized successfully');
        this.castContext = cast.framework.CastContext.getInstance();
        const appId = (window.chrome && window.chrome.cast && window.chrome.cast.media && window.chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID)
            ? window.chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID
            : 'CC1AD845';
        const autoJoin = (window.chrome && window.chrome.cast && window.chrome.cast.AutoJoinPolicy)
            ? window.chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED
            : cast.framework.AutoJoinPolicy.ORIGIN_SCOPED;
        this.castContext.setOptions({
            receiverApplicationId: appId,
            autoJoinPolicy: autoJoin,
        });
        if (this.castBtn) {
            this.castBtn.style.display = 'inline-flex';
            this.castBtn.addEventListener('click', () => this.onCastButtonClick());
        }
        if (this.fullCastBtn) {
            this.fullCastBtn.style.display = 'inline-flex';
            this.fullCastBtn.addEventListener('click', () => this.onCastButtonClick());
        }
        this.castContext.addEventListener(
            cast.framework.CastContextEventType.SESSION_STATE_CHANGED,
            (event) => this.handleCastSessionStateChange(event)
        );
        if (this.castContext.getCurrentSession()) {
            this.startCastingSession();
        }
    }

    onCastButtonClick() {
        if (!this.castContext) return;
        const session = this.castContext.getCurrentSession();
        if (session) {
            this.castContext.endCurrentSession(true);
        } else {
            this.castContext.requestSession();
        }
    }

    handleCastSessionStateChange(event) {
        const state = event.sessionState;
        if (state === cast.framework.SessionState.SESSION_STARTED || state === cast.framework.SessionState.SESSION_RESUMED) {
            this.startCastingSession();
        } else if (state === cast.framework.SessionState.SESSION_ENDED) {
            this.endCastingSession();
        }
    }

    startCastingSession() {
        if (!this.castContext) return;
        this.castSession = this.castContext.getCurrentSession();
        if (!this.castSession) return;
        this.castPlayer = new cast.framework.RemotePlayer();
        this.castController = new cast.framework.RemotePlayerController(this.castPlayer);
        this.castController.addEventListener(
            cast.framework.RemotePlayerEventType.IS_PAUSED_CHANGED,
            () => this.updatePlaybackUI(!this.castPlayer.isPaused)
        );
        this.castController.addEventListener(
            cast.framework.RemotePlayerEventType.CURRENT_TIME_CHANGED,
            () => this.updateFromCastState()
        );
        this.castController.addEventListener(
            cast.framework.RemotePlayerEventType.DURATION_CHANGED,
            () => this.updateFromCastState()
        );
        this.castController.addEventListener(
            cast.framework.RemotePlayerEventType.VOLUME_LEVEL_CHANGED,
            () => {
                if (this.volume) {
                    this.volume.value = String(this.castPlayer.volumeLevel ?? this.volume.value);
                }
                if (this.fullVolume) {
                    this.fullVolume.value = String(this.castPlayer.volumeLevel ?? this.fullVolume.value);
                }
            }
        );

        const deviceName = this.castSession.getCastDevice()?.friendlyName || 'Cast device';
        this.updateCastingUI(true, deviceName);
        this.audio.pause();
        const episode = this.getActiveEpisode();
        if (episode && episode.audio_url) {
            const startTime = this.audio.currentTime || 0;
            this.loadCastMedia(episode, startTime);
        }
    }

    endCastingSession() {
        const resumeTime = this.castPlayer ? this.castPlayer.currentTime : this.audio.currentTime;
        const wasPlaying = this.castPlayer ? !this.castPlayer.isPaused : false;
        this.castSession = null;
        this.castPlayer = null;
        this.castController = null;
        this.updateCastingUI(false, '');
        if (this.audio.src) {
            this.audio.currentTime = resumeTime || 0;
            if (wasPlaying) {
                this.audio.play().catch(() => {});
            } else {
                this.updatePlaybackUI(false);
            }
        }
    }

    loadCastMedia(episode, startTime) {
        if (!this.castSession || !window.chrome || !window.chrome.cast) return;
        const mediaInfo = new chrome.cast.media.MediaInfo(episode.audio_url, 'audio/mpeg');
        mediaInfo.metadata = new chrome.cast.media.MusicTrackMediaMetadata();
        mediaInfo.metadata.title = episode.title || 'MicahPod';
        mediaInfo.metadata.artist = episode.podcast_title || '';
        const art = episode.artwork_url || episode.podcast_artwork || '';
        if (art) {
            mediaInfo.metadata.images = [new chrome.cast.Image(art)];
        }
        const request = new chrome.cast.media.LoadRequest(mediaInfo);
        request.currentTime = startTime || 0;
        request.autoplay = true;
        request.playbackRate = this.audio.playbackRate || 1;
        this.castSession.loadMedia(request).then(() => {
            this.updateFromCastState();
        }).catch(() => {});
    }

    updateCastingUI(isCasting, deviceName) {
        if (this.playerBar) {
            this.playerBar.classList.toggle('casting', isCasting);
        }
        if (this.castingLabel) {
            if (isCasting) {
                this.castingLabel.textContent = `Casting to ${deviceName}`;
                this.castingLabel.classList.remove('hidden');
            } else {
                this.castingLabel.textContent = '';
                this.castingLabel.classList.add('hidden');
            }
        }
        if (this.fullscreen) {
            this.fullscreen.classList.toggle('casting', isCasting);
        }
    }

    updateFromCastState() {
        if (!this.isCasting()) return;
        const duration = this.getDuration();
        if (duration > 0) {
            this.progress.value = (this.getCurrentTime() / duration) * 100;
            if (this.fullProgress) {
                this.fullProgress.value = this.progress.value;
            }
        }
        this.updateTime();
        this.updatePositionState();
        this.save();
    }

    getActiveEpisode() {
        return this.currentEpisode || this.getState();
    }

    isCasting() {
        return !!this.castSession;
    }

    getCurrentTime() {
        if (this.isCasting() && this.castPlayer) {
            return this.castPlayer.currentTime || 0;
        }
        return this.audio.currentTime || 0;
    }

    getDuration() {
        if (this.isCasting() && this.castPlayer) {
            return this.castPlayer.duration || 0;
        }
        return isFinite(this.audio.duration) ? this.audio.duration : 0;
    }

    updatePlaybackUI(isPlaying) {
        this.playBtn.textContent = isPlaying ? '⏸' : '▶';
        this.playBtn.setAttribute('aria-label', isPlaying ? 'Pause' : 'Play');
        if (this.miniPlayBtn) {
            this.miniPlayBtn.textContent = isPlaying ? '⏸' : '▶';
        }
        if (this.fullPlayBtn) {
            this.fullPlayBtn.textContent = isPlaying ? '⏸' : '▶';
            this.fullPlayBtn.setAttribute('aria-label', isPlaying ? 'Pause' : 'Play');
        }
    }

    togglePlay() {
        if (this.isCasting() && this.castController) {
            this.castController.playOrPause();
            return;
        }
        if (this.audio.paused) {
            this.audio.play();
        } else {
            this.audio.pause();
        }
    }

    play() {
        if (this.isCasting() && this.castController && this.castPlayer?.isPaused) {
            this.castController.playOrPause();
            return;
        }
        this.audio.play();
    }

    pause() {
        if (this.isCasting() && this.castController && !this.castPlayer?.isPaused) {
            this.castController.playOrPause();
            this.save();
            this.saveToServer();
            return;
        }
        this.audio.pause();
        this.save();
        this.saveToServer();
    }

    seekTo(seconds) {
        const duration = this.getDuration();
        const target = Math.max(0, Math.min(duration || seconds, seconds));
        if (this.isCasting() && this.castController && this.castPlayer) {
            this.castPlayer.currentTime = target;
            this.castController.seek();
        } else {
            this.audio.currentTime = target;
        }
        this.updateTime();
    }

    seekBy(delta) {
        const duration = this.getDuration();
        const target = this.getCurrentTime() + delta;
        const clamped = Math.max(0, Math.min(duration || target, target));
        this.seekTo(clamped);
    }

    episodeKey(episode) {
        return `${episode?.id ?? ''}::${episode?.audio_url ?? ''}`;
    }

    normalizeEpisode(episode) {
        return {
            id: episode.id ?? null,
            title: episode.title || 'Untitled episode',
            podcast_title: episode.podcast_title || '',
            artwork_url: episode.artwork_url || '',
            podcast_artwork: episode.podcast_artwork || '',
            audio_url: episode.audio_url || '',
            duration_seconds: episode.duration_seconds ?? null,
        };
    }

    getQueue() {
        try {
            const stored = JSON.parse(localStorage.getItem(this.queueKey));
            return Array.isArray(stored) ? stored : [];
        } catch (err) {
            return [];
        }
    }

    saveQueue(queue, options = {}) {
        const skipServer = options.skipServer === true;
        this.queue = queue;
        localStorage.setItem(this.queueKey, JSON.stringify(queue));
        this.renderQueue();
        if (!skipServer) {
            this.save();
            this.queueServerSave(true);
        }
    }

    addToQueue(episode, options = {}) {
        if (!episode || !episode.audio_url) return;
        const normalized = this.normalizeEpisode(episode);
        const queue = this.getQueue();
        const key = this.episodeKey(normalized);
        const existingIndex = queue.findIndex(item => this.episodeKey(item) === key);
        const playNext = options.playNext === true;

        if (existingIndex >= 0) {
            if (playNext && existingIndex > 0) {
                queue.splice(existingIndex, 1);
                queue.unshift(normalized);
                this.saveQueue(queue);
                this.showToast('Queued to play next');
            } else {
                this.showToast('Already in queue');
            }
            return;
        }

        if (playNext) {
            queue.unshift(normalized);
            this.showToast('Queued to play next');
        } else {
            queue.push(normalized);
            this.showToast('Added to queue');
        }
        this.saveQueue(queue);
    }

    removeQueueItem(index) {
        const queue = this.getQueue();
        queue.splice(index, 1);
        this.saveQueue(queue);
    }

    moveQueueItem(index, direction) {
        const queue = this.getQueue();
        const target = index + direction;
        if (target < 0 || target >= queue.length) return;
        const [item] = queue.splice(index, 1);
        queue.splice(target, 0, item);
        this.saveQueue(queue);
    }

    reorderQueueItem(fromIndex, toIndex) {
        if (fromIndex === toIndex) return;
        const queue = this.getQueue();
        if (fromIndex < 0 || fromIndex >= queue.length) return;
        if (toIndex < 0 || toIndex >= queue.length) return;
        const [item] = queue.splice(fromIndex, 1);
        queue.splice(toIndex, 0, item);
        this.saveQueue(queue);
    }

    clearQueue() {
        this.saveQueue([]);
        this.showToast('Queue cleared');
    }

    openQueueOverlay() {
        if (!this.queueOverlay) return;
        this.queueOverlay.classList.add('open');
        this.queueOverlay.setAttribute('aria-hidden', 'false');
        document.body.classList.add('player-queue-overlay-open');
        this.renderQueue();
    }

    closeQueueOverlay() {
        if (!this.queueOverlay) return;
        this.queueOverlay.classList.remove('open');
        this.queueOverlay.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('player-queue-overlay-open');
    }

    formatQueueDuration(seconds) {
        if (!seconds || !isFinite(seconds)) return '';
        return this.formatTime(seconds);
    }

    setupQueueListEvents(listEl) {
        if (!listEl || listEl.dataset.dragBound) return;
        listEl.dataset.dragBound = '1';
        listEl.addEventListener('dragover', (event) => this.handleQueueDragOver(event, listEl));
        listEl.addEventListener('drop', (event) => this.handleQueueDrop(event, listEl));
    }

    handleQueueDragStart(event, row, index, listEl) {
        if (!event.target.closest('.queue-drag-handle')) {
            event.preventDefault();
            return;
        }
        this.dragState = {
            sourceIndex: index,
            sourceRow: row,
            placeholder: this.createQueuePlaceholder(row),
            listEl,
            isTouch: false,
        };
        row.classList.add('is-dragging');
        row.style.display = 'none';
        try {
            event.dataTransfer.effectAllowed = 'move';
            event.dataTransfer.setData('text/plain', String(index));
        } catch (err) {}
    }

    handleQueueDragEnd() {
        if (!this.dragState) return;
        const { sourceRow, placeholder } = this.dragState;
        if (sourceRow) {
            sourceRow.classList.remove('is-dragging');
            sourceRow.style.display = '';
        }
        if (placeholder) placeholder.remove();
        this.dragState = null;
    }

    handleQueueDragOver(event, listEl) {
        if (!this.dragState || this.dragState.isTouch) return;
        event.preventDefault();
        const target = event.target.closest('.queue-item');
        this.placeQueuePlaceholder(listEl, target, event.clientY);
    }

    handleQueueDrop(event, listEl) {
        if (!this.dragState || this.dragState.isTouch) return;
        event.preventDefault();
        const { sourceIndex, placeholder } = this.dragState;
        if (!placeholder || !placeholder.parentElement) {
            this.handleQueueDragEnd();
            return;
        }
        const newIndex = this.getPlaceholderIndex(listEl, placeholder);
        this.handleQueueDragEnd();
        if (newIndex != null && newIndex !== sourceIndex) {
            this.reorderQueueItem(sourceIndex, newIndex);
        }
    }

    handleQueueTouchStart(event, row, index, listEl) {
        const handle = event.target.closest('.queue-drag-handle');
        if (!handle) return;
        const touch = event.touches[0];
        if (!touch) return;
        event.preventDefault();
        this.dragState = {
            sourceIndex: index,
            sourceRow: row,
            placeholder: this.createQueuePlaceholder(row),
            listEl,
            isTouch: true,
            touchId: touch.identifier,
            ghost: this.createQueueGhost(row),
        };
        row.classList.add('is-dragging');
        row.style.display = 'none';
        document.body.classList.add('queue-dragging');
        this.updateQueueGhostPosition(touch);
        document.addEventListener('touchmove', this.boundQueueTouchMove, { passive: false });
        document.addEventListener('touchend', this.boundQueueTouchEnd);
        document.addEventListener('touchcancel', this.boundQueueTouchEnd);
    }

    handleQueueTouchMove(event) {
        if (!this.dragState || !this.dragState.isTouch) return;
        const touch = [...event.changedTouches].find(t => t.identifier === this.dragState.touchId);
        if (!touch) return;
        event.preventDefault();
        this.updateQueueGhostPosition(touch);
        const target = document.elementFromPoint(touch.clientX, touch.clientY)?.closest('.queue-item');
        this.placeQueuePlaceholder(this.dragState.listEl, target, touch.clientY);
    }

    handleQueueTouchEnd(event) {
        if (!this.dragState || !this.dragState.isTouch) return;
        const touch = [...event.changedTouches].find(t => t.identifier === this.dragState.touchId);
        if (!touch) return;
        const { listEl, placeholder, sourceIndex } = this.dragState;
        const newIndex = placeholder ? this.getPlaceholderIndex(listEl, placeholder) : null;
        this.cleanupQueueTouchDrag();
        if (newIndex != null && newIndex !== sourceIndex) {
            this.reorderQueueItem(sourceIndex, newIndex);
        }
    }

    cleanupQueueTouchDrag() {
        if (!this.dragState) return;
        const { sourceRow, placeholder, ghost } = this.dragState;
        if (sourceRow) {
            sourceRow.classList.remove('is-dragging');
            sourceRow.style.display = '';
        }
        if (placeholder) placeholder.remove();
        if (ghost) ghost.remove();
        document.body.classList.remove('queue-dragging');
        document.removeEventListener('touchmove', this.boundQueueTouchMove);
        document.removeEventListener('touchend', this.boundQueueTouchEnd);
        document.removeEventListener('touchcancel', this.boundQueueTouchEnd);
        this.dragState = null;
    }

    createQueuePlaceholder(row) {
        const placeholder = document.createElement('div');
        placeholder.className = 'queue-placeholder';
        placeholder.style.height = `${row.getBoundingClientRect().height}px`;
        return placeholder;
    }

    placeQueuePlaceholder(listEl, target, clientY) {
        if (!listEl || !this.dragState || !this.dragState.placeholder) return;
        const placeholder = this.dragState.placeholder;
        if (!target || !listEl.contains(target)) {
            if (placeholder.parentElement !== listEl) {
                listEl.appendChild(placeholder);
            }
            return;
        }
        if (target.classList.contains('is-dragging')) return;
        const rect = target.getBoundingClientRect();
        const insertAfter = clientY > rect.top + rect.height / 2;
        if (insertAfter) {
            if (target.nextSibling !== placeholder) {
                listEl.insertBefore(placeholder, target.nextSibling);
            }
        } else {
            if (target.previousSibling !== placeholder) {
                listEl.insertBefore(placeholder, target);
            }
        }
    }

    getPlaceholderIndex(listEl, placeholder) {
        const items = Array.from(listEl.querySelectorAll('.queue-item')).filter(el => !el.classList.contains('is-dragging'));
        const next = placeholder.nextElementSibling;
        if (!next) {
            return items.length;
        }
        const idx = items.indexOf(next);
        return idx === -1 ? items.length : idx;
    }

    createQueueGhost(row) {
        const ghost = row.cloneNode(true);
        ghost.classList.add('queue-drag-ghost');
        ghost.style.width = `${row.getBoundingClientRect().width}px`;
        document.body.appendChild(ghost);
        return ghost;
    }

    updateQueueGhostPosition(touch) {
        if (!this.dragState || !this.dragState.ghost) return;
        this.dragState.ghost.style.left = `${touch.clientX}px`;
        this.dragState.ghost.style.top = `${touch.clientY}px`;
    }

    renderQueue() {
        if (!this.queueList && !this.queueOverlayList) return;
        const queue = this.getQueue();
        if (this.queueList) this.queueList.innerHTML = '';
        if (this.queueOverlayList) this.queueOverlayList.innerHTML = '';
        if (this.queueCount) {
            this.queueCount.textContent = queue.length ? `${queue.length} queued` : '';
        }
        if (this.queueOverlayCount) {
            this.queueOverlayCount.textContent = queue.length ? `${queue.length} queued` : '';
        }
        if (this.queueEmpty) {
            this.queueEmpty.style.display = queue.length ? 'none' : 'block';
        }
        if (this.queueOverlayEmpty) {
            this.queueOverlayEmpty.style.display = queue.length ? 'none' : 'block';
        }
        const renderList = (listEl) => {
            if (!listEl) return;
            this.setupQueueListEvents(listEl);
            queue.forEach((item, index) => {
                const row = document.createElement('div');
                row.className = 'queue-item';
                row.dataset.index = String(index);
                row.setAttribute('draggable', 'true');
                row.addEventListener('dragstart', (event) => this.handleQueueDragStart(event, row, index, listEl));
                row.addEventListener('dragend', () => this.handleQueueDragEnd());

                const handle = document.createElement('div');
                handle.className = 'queue-drag-handle';
                handle.setAttribute('aria-label', 'Drag to reorder');
                handle.setAttribute('title', 'Drag to reorder');
                handle.addEventListener('touchstart', (event) => this.handleQueueTouchStart(event, row, index, listEl), { passive: false });

                const art = document.createElement('img');
                art.src = item.artwork_url || item.podcast_artwork || 'assets/logo.svg';
                art.alt = item.title || 'Artwork';

                const info = document.createElement('div');
                info.className = 'queue-item-info';
                const title = document.createElement('div');
                title.className = 'queue-item-title';
                title.textContent = item.title || 'Untitled episode';
                const meta = document.createElement('div');
                meta.className = 'queue-item-meta';
                const show = document.createElement('div');
                show.className = 'queue-item-show';
                show.textContent = item.podcast_title || '';
                const duration = document.createElement('div');
                duration.className = 'queue-item-duration';
                duration.textContent = this.formatQueueDuration(item.duration_seconds);
                meta.appendChild(show);
                meta.appendChild(duration);
                info.appendChild(title);
                info.appendChild(meta);

                const actions = document.createElement('div');
                actions.className = 'queue-item-actions';

                const removeBtn = document.createElement('button');
                removeBtn.textContent = '✕';
                removeBtn.setAttribute('aria-label', 'Remove from queue');
                removeBtn.addEventListener('click', () => this.removeQueueItem(index));

                actions.appendChild(removeBtn);

                row.appendChild(handle);
                row.appendChild(art);
                row.appendChild(info);
                row.appendChild(actions);
                listEl.appendChild(row);
            });
        };
        renderList(this.queueList);
        renderList(this.queueOverlayList);
    }

    playNextTrack(silent = false) {
        const queue = this.getQueue();
        if (!queue.length) {
            if (!silent) this.showToast('Queue is empty');
            return;
        }
        const next = queue.shift();
        this.saveQueue(queue);
        this.loadEpisode(next);
    }

    playPreviousTrack() {
        if (!this.playHistory.length) {
            this.showToast('No previous track');
            return;
        }
        const previous = this.playHistory.pop();
        if (previous) {
            this.loadEpisode(previous, { skipHistory: true });
        }
    }

    handleTrackEnded() {
        this.playNextTrack(true);
    }

    openFullscreen() {
        if (!this.fullscreen) return;
        this.fullscreen.classList.add('open');
        this.fullscreen.setAttribute('aria-hidden', 'false');
        document.body.classList.add('player-fullscreen-open');
        this.syncFullscreenState();
    }

    closeFullscreen() {
        if (!this.fullscreen) return;
        this.fullscreen.classList.remove('open');
        this.fullscreen.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('player-fullscreen-open');
        if (this.queueOverlay?.classList.contains('open')) {
            this.closeQueueOverlay();
        }
    }

    syncFullscreenState() {
        const episode = this.getActiveEpisode();
        if (episode) {
            if (this.fullTitle) this.fullTitle.textContent = episode.title || 'Now playing';
            if (this.fullPodcast) this.fullPodcast.textContent = episode.podcast_title || '';
            if (this.fullArt) this.fullArt.src = episode.artwork_url || episode.podcast_artwork || 'assets/logo.svg';
        }
        if (this.fullSpeed) this.fullSpeed.value = this.speed.value;
        if (this.fullVolume) this.fullVolume.value = this.volume.value;
        if (this.fullProgress) this.fullProgress.value = this.progress.value;
        this.updateTime();
    }

    showToast(message) {
        if (!this.toastContainer) return;
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = message;
        this.toastContainer.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 2500);
    }
}

window.MicahPlayer = MicahPlayer;
