<?php
require_once __DIR__ . '/api/config.php';
$user = current_user();
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="csrf-token" content="<?= htmlspecialchars($csrf) ?>">
    <title>MicahPod</title>
    <link rel="stylesheet" href="css/style.css?v=20260825a">
</head>
<body>
    <header class="topbar">
        <div class="logo">
            <img src="assets/logo.png" width="56" height="56" alt="MicahPod logo">
            <span>MicahPod</span>
        </div>
        <button id="nav-toggle" class="nav-toggle" type="button" aria-label="Toggle navigation">
            ☰
        </button>
        <nav class="nav-links">
            <a href="index.php" class="active">Home</a>
            <?php if ($user): ?>
            <a href="#" id="nav-my-podcasts">My Podcasts <span id="subscription-count" class="subscription-count-badge">0</span></a>
            <a href="#" id="nav-my-ratings">My Ratings</a>
            <a href="settings.php">Settings</a>
            <?php endif; ?>
            <?php if ($user && $user['role'] === 'admin'): ?>
            <a href="admin.php">Admin</a>
            <?php endif; ?>
        </nav>
        <div class="nav-actions">
            <input type="text" id="search-input" placeholder="Search episodes, shows, topics">
            <?php if ($user): ?>
            <div class="user-menu">
                <span><?= htmlspecialchars($user['username']) ?></span>
                <a class="btn btn-small" href="settings.php">Settings</a>
                <a href="logout.php">Logout</a>
            </div>
            <?php else: ?>
            <a class="btn" href="login.php">Login</a>
            <a class="btn btn-primary" href="register.php">Register</a>
            <?php endif; ?>
        </div>
    </header>

    <main class="container">
        <section class="filters">
            <select id="filter-podcast">
                <option value="">All Podcasts</option>
            </select>
            <select id="filter-sort">
                <option value="newest">Newest</option>
                <option value="rating">Top Rated</option>
                <option value="recommended">Recommended</option>
            </select>
            <select id="filter-rated">
                <option value="">All</option>
                <option value="1">Rated</option>
                <option value="0">Unrated</option>
            </select>
            <label class="toggle">
                <input type="checkbox" id="filter-recommended">
                <span>Recommended only</span>
            </label>
            <?php if ($user): ?>
            <label class="toggle">
                <input type="checkbox" id="filter-show-all">
                <span>Show All Episodes</span>
            </label>
            <?php endif; ?>
            <?php if ($user): ?>
            <button id="refresh-main" class="btn btn-small refresh-button" type="button">⟳ Refresh</button>
            <span id="refresh-status" class="muted"></span>
            <?php endif; ?>
        </section>
        <?php if ($user): ?>
        <section id="subscription-panel" class="subscription-panel hidden">
            <div class="subscription-header">
                <div class="subscription-title">
                    <h2>My Podcasts</h2>
                    <span id="subscription-count-panel" class="subscription-count-badge">0</span>
                </div>
                <button id="subscription-close" class="btn btn-small" type="button">Close</button>
            </div>
            <div id="subscription-grid" class="subscription-grid"></div>
        </section>
        <section id="continue-listening" class="continue-listening hidden">
            <div class="continue-listening-header">
                <h2>Continue Listening</h2>
                <span id="continue-listening-count" class="muted"></span>
            </div>
            <div id="continue-listening-list" class="continue-listening-list"></div>
        </section>
        <section id="podcast-focus" class="podcast-focus hidden" hidden>
            <div class="podcast-focus-main">
                <img id="podcast-focus-art" src="assets/logo.png" width="56" height="56" alt="">
                <div class="podcast-focus-info">
                    <div class="podcast-focus-label muted">Podcast</div>
                    <h2 id="podcast-focus-title">Podcast</h2>
                    <div id="podcast-focus-meta" class="podcast-focus-meta muted"></div>
                </div>
            </div>
            <button id="podcast-focus-clear" class="btn btn-small" type="button">Show All</button>
        </section>
        <?php endif; ?>

        <section id="episode-grid" class="episode-grid">
        </section>

        <section id="discussion-panel" class="discussion-panel hidden">
            <div class="discussion-header">
                <button id="discussion-back" class="btn btn-small" type="button">← Back</button>
                <div class="discussion-episode">
                    <img id="discussion-art" src="assets/logo.png" alt="Episode artwork">
                    <div class="discussion-episode-info">
                        <div id="discussion-title" class="discussion-title"></div>
                        <div id="discussion-podcast" class="discussion-podcast muted"></div>
                    </div>
                </div>
                <div id="discussion-count" class="discussion-count-badge">0</div>
            </div>
            <div class="discussion-compose">
                <label for="discussion-input">What did you think of this episode?</label>
                <textarea id="discussion-input" rows="4" placeholder="What did you think of this episode?"></textarea>
                <div class="discussion-compose-actions">
                    <span id="discussion-login-hint" class="muted hidden">Login to comment.</span>
                    <button id="discussion-submit" class="btn btn-primary" type="button">Post Comment</button>
                </div>
            </div>
            <div id="discussion-empty" class="discussion-empty muted hidden">No comments yet. Be the first to post.</div>
            <div id="discussion-list" class="discussion-list"></div>
        </section>

        <section class="pagination">
            <button id="prev-page">Prev</button>
            <span id="page-info">Page 1</span>
            <button id="next-page">Next</button>
        </section>
    </main>

    <div id="player-bar" class="player-bar">
        <div class="player-info">
            <img id="player-art" src="assets/logo.png" alt="Artwork">
            <div>
                <div id="player-title">Select an episode to play</div>
                <div id="player-podcast" class="muted"></div>
                <div id="player-casting" class="casting-label hidden"></div>
            </div>
            <button id="player-mini-play" class="btn btn-small mini-play-btn" aria-label="Play/Pause">▶</button>
        </div>
        <div class="player-controls">
            <div class="player-buttons">
                <button id="player-prev-track" type="button" aria-label="Previous track">⏮</button>
                <button id="player-back" type="button">-30s</button>
                <button id="player-play" type="button">▶</button>
                <button id="player-forward" type="button">+30s</button>
                <button id="player-next-track" type="button" aria-label="Next track">⏭</button>
                <button id="player-cast" class="cast-btn" type="button" aria-label="Cast" style="display:none" title="Cast to device">📺</button>
                <button id="player-fullscreen-toggle" class="player-fullscreen-toggle" type="button" aria-label="Full screen player">⛶</button>
                <button id="player-extras-toggle" class="player-toggle" type="button" aria-label="Player options">⋯</button>
            </div>
            <div class="player-progress">
                <input type="range" id="player-progress" min="0" max="100" value="0">
                <div id="player-time" class="player-time">0:00 / --:--</div>
            </div>
        </div>
        <div class="player-extras">
            <select id="player-speed">
                <option value="1">1x</option>
                <option value="1.25">1.25x</option>
                <option value="1.5">1.5x</option>
                <option value="2">2x</option>
            </select>
            <input type="range" id="player-volume" min="0" max="1" step="0.01" value="1">
            <?php if ($user): ?>
            <button id="player-save-progress" class="btn btn-small player-save-progress" type="button" title="Save progress for other devices">Save Progress</button>
            <span id="player-save-status" class="player-save-status muted"></span>
            <?php endif; ?>
        </div>
        <button id="player-close" class="player-close" type="button" aria-label="Minimize player">✕</button>
        <audio id="audio-element"></audio>
    </div>
    <div id="player-fullscreen" class="player-fullscreen" aria-hidden="true">
        <div class="player-fullscreen-content">
            <div class="player-fullscreen-main">
                <div class="player-fullscreen-art">
                    <img id="player-full-art" src="assets/logo.png" alt="Artwork">
                </div>
                <div class="player-fullscreen-info">
                    <div id="player-full-title" class="player-full-title">Select an episode to play</div>
                    <div id="player-full-podcast" class="player-full-podcast muted"></div>
                </div>
                <div class="player-fullscreen-controls">
                    <div class="player-fullscreen-buttons">
                        <button id="player-prev-track-full" type="button" aria-label="Previous track">⏮</button>
                        <button id="player-back-full" type="button">-30s</button>
                        <button id="player-play-full" type="button">▶</button>
                        <button id="player-forward-full" type="button">+30s</button>
                        <button id="player-next-track-full" type="button" aria-label="Next track">⏭</button>
                    </div>
                    <div class="player-fullscreen-progress">
                        <input type="range" id="player-progress-full" min="0" max="100" value="0">
                        <div id="player-time-full" class="player-time">0:00 / --:--</div>
                    </div>
                    <div class="player-fullscreen-extras">
                        <select id="player-speed-full">
                            <option value="1">1x</option>
                            <option value="1.25">1.25x</option>
                            <option value="1.5">1.5x</option>
                            <option value="2">2x</option>
                        </select>
                        <input type="range" id="player-volume-full" min="0" max="1" step="0.01" value="1">
                        <button id="player-cast-full" class="cast-btn" type="button" aria-label="Cast" style="display:none" title="Cast to device">📺</button>
                        <?php if ($user): ?>
                        <button id="player-save-progress-full" class="btn btn-small player-save-progress" type="button" title="Save progress for other devices">Save Progress</button>
                        <span id="player-save-status-full" class="player-save-status muted"></span>
                        <?php endif; ?>
                    </div>
                </div>
            </div>
            <aside class="player-queue-panel">
                <div class="player-queue-header">
                    <h3>Up Next</h3>
                    <div class="player-queue-header-actions">
                        <span id="player-queue-count" class="muted"></span>
                        <button id="player-queue-expand" class="player-queue-expand" type="button" aria-label="Expand queue" title="Expand queue">⤢</button>
                    </div>
                </div>
                <div id="player-queue-list" class="player-queue-list"></div>
                <div id="player-queue-empty" class="player-queue-empty muted">Queue is empty</div>
            </aside>
        </div>
        <button id="player-fullscreen-close" class="player-fullscreen-close" type="button" aria-label="Close full screen">✕</button>
    </div>
    <div id="player-queue-overlay" class="player-queue-overlay" aria-hidden="true">
        <div class="player-queue-overlay-content">
            <div class="player-queue-overlay-header">
                <div>
                    <h2>Queue</h2>
                    <span id="player-queue-overlay-count" class="muted"></span>
                </div>
                <div class="player-queue-overlay-actions">
                    <button id="player-queue-clear" class="btn btn-danger btn-small" type="button">Clear Queue</button>
                    <button id="player-queue-close" class="btn btn-small" type="button" aria-label="Close queue">✕</button>
                </div>
            </div>
            <div id="player-queue-overlay-list" class="player-queue-overlay-list"></div>
            <div id="player-queue-overlay-empty" class="player-queue-overlay-empty muted">Queue is empty</div>
        </div>
    </div>
    <button id="player-show" class="player-show hidden" type="button">Show Player</button>
    <div id="toast-container" class="toast-container"></div>

    <script>
        window.MICAHPOD_USER = <?= json_encode($user ?: null) ?>;
        // Register Cast callback BEFORE SDK loads
        window['__onGCastApiAvailable'] = function(isAvailable) {
            console.log('[MicahPod] Cast API available:', isAvailable);
            window.__castReady = isAvailable;
            // If player already exists, init cast now
            if (window.__micahPlayer && isAvailable) {
                window.__micahPlayer.initCast();
            }
        };
    </script>
    <script src="https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1"></script>
    <script src="js/player.js?v=20260813b"></script>
    <script src="js/app.js?v=20260813b"></script>
</body>
</html>
