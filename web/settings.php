<?php
require_once __DIR__ . '/api/config.php';
$user = require_login();
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="csrf-token" content="<?= htmlspecialchars($csrf) ?>">
    <title>Settings - MicahPod</title>
    <link rel="stylesheet" href="css/style.css?v=20261008c">
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
            <a href="index.php">Home</a>
            <a href="settings.php" class="active">Settings</a>
            <?php if ($user && $user['role'] === 'admin'): ?>
            <a href="admin.php">Admin</a>
            <?php endif; ?>
        </nav>
        <div class="nav-actions">
            <div class="user-menu">
                <span><?= htmlspecialchars($user['username']) ?></span>
                <a href="logout.php">Logout</a>
            </div>
        </div>
    </header>

    <main class="container settings-container">
        <section class="settings-section">
            <h2>My Podcast Library</h2>
            <p class="muted">Add shows you want, hide high-volume ones from your home feed, or remove them from your library.</p>

            <div class="library-add">
                <h3>Find a podcast</h3>
                <div class="library-search-row">
                    <input type="search" id="library-search-input" placeholder="Search podcasts…" autocomplete="off">
                    <button class="btn btn-primary" type="button" id="library-search-btn">Search</button>
                </div>
                <div id="library-search-results" class="library-search-results"></div>

                <h3>Or paste an RSS feed URL</h3>
                <div class="library-search-row">
                    <input type="url" id="library-feed-url" placeholder="https://example.com/feed.xml">
                    <button class="btn btn-secondary" type="button" id="library-add-feed-btn">Add feed</button>
                </div>
                <span id="library-add-status" class="muted"></span>
            </div>

            <div class="library-list-header">
                <h3>Your subscriptions</h3>
                <span id="library-count" class="muted"></span>
            </div>
            <div id="library-list" class="library-list"></div>
        </section>

        <section class="settings-section">
            <h2>Playback & Feed</h2>
            <form id="settings-form" class="settings-form">
                <label class="toggle">
                    <input type="checkbox" name="default_recommended_only" id="setting-recommended-only">
                    <span>Default to recommended only</span>
                </label>
                <label class="toggle">
                    <input type="checkbox" name="hide_played" id="setting-hide-played">
                    <span>Hide played episodes</span>
                </label>
                <div class="settings-actions">
                    <button class="btn btn-primary" type="submit">Save settings</button>
                    <span id="settings-status" class="muted"></span>
                </div>
            </form>
        </section>

        <section class="settings-section">
            <h2>Digest Settings</h2>
            <form id="digest-settings-form" class="settings-form">
                <label class="toggle">
                    <input type="checkbox" name="digest_enabled" id="setting-digest-enabled">
                    <span>Enable daily digest</span>
                </label>
                <label>Digest hours (comma-separated, 24h)</label>
                <input type="text" name="digest_times" id="setting-digest-times" placeholder="10,22">
                <div class="settings-actions">
                    <button class="btn btn-primary" type="submit">Save digest</button>
                    <span id="digest-status" class="muted"></span>
                </div>
            </form>
        </section>
    </main>

    <script src="js/settings.js?v=20261008a"></script>
</body>
</html>
