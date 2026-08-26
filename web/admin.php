<?php
require_once __DIR__ . '/api/config.php';
$user = current_user();
if (!$user || $user['role'] !== 'admin') {
    http_response_code(403);
    echo 'Forbidden';
    exit;
}
$csrf = csrf_token();

// Auto-detect latest sideload APK so the admin label always matches downloads/.
$androidApkFile = null;
$androidApkVersion = null;
$downloadsDir = __DIR__ . '/downloads';
if (is_dir($downloadsDir)) {
    $candidates = [];
    foreach (glob($downloadsDir . '/MicahPod-*.apk') ?: [] as $path) {
        $name = basename($path);
        if (!preg_match('/^MicahPod-(\d+(?:\.\d+)*)\.apk$/', $name, $m)) {
            continue;
        }
        $parts = array_map('intval', explode('.', $m[1]));
        $candidates[] = [
            'file' => $name,
            'version' => $m[1],
            'sort' => $parts,
            'mtime' => @filemtime($path) ?: 0,
        ];
    }
    usort($candidates, static function (array $a, array $b): int {
        $len = max(count($a['sort']), count($b['sort']));
        for ($i = 0; $i < $len; $i++) {
            $av = $a['sort'][$i] ?? 0;
            $bv = $b['sort'][$i] ?? 0;
            if ($av !== $bv) {
                return $bv <=> $av;
            }
        }
        return $b['mtime'] <=> $a['mtime'];
    });
    if ($candidates) {
        $androidApkFile = $candidates[0]['file'];
        $androidApkVersion = $candidates[0]['version'];
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="csrf-token" content="<?= htmlspecialchars($csrf) ?>">
    <title>Admin - MicahPod</title>
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
            <a href="index.php">Home</a>
            <a href="admin.php" class="active">Admin</a>
        </nav>
        <div class="nav-actions">
            <div class="user-menu">
                <span><?= htmlspecialchars($user['username']) ?></span>
                <a href="logout.php">Logout</a>
            </div>
        </div>
    </header>

    <main class="container admin-container">
        <section id="section-dashboard" class="admin-panel-section active">
            <h2>Admin Dashboard</h2>
            <div class="admin-card-grid">
                <div class="admin-card" data-section="podcasts">
                    <div class="admin-card-icon">🎙️</div>
                    <div class="admin-card-title">Podcasts</div>
                    <div class="admin-card-desc">Manage feeds, add and edit shows.</div>
                </div>
                <div class="admin-card" data-section="users">
                    <div class="admin-card-icon">👥</div>
                    <div class="admin-card-title">Users</div>
                    <div class="admin-card-desc">Manage accounts and roles.</div>
                </div>
                <div class="admin-card" data-section="tags">
                    <div class="admin-card-icon">🏷️</div>
                    <div class="admin-card-title">Tags</div>
                    <div class="admin-card-desc">View, rename, merge, or delete tags.</div>
                </div>
                <div class="admin-card" data-section="episode-tags">
                    <div class="admin-card-icon">🎯</div>
                    <div class="admin-card-title">Episode Tags</div>
                    <div class="admin-card-desc">Search episodes, view/add/remove tags, re-extract.</div>
                </div>
                <div class="admin-card" data-section="tag-blacklist">
                    <div class="admin-card-icon">🚫</div>
                    <div class="admin-card-title">Tag Blacklist</div>
                    <div class="admin-card-desc">Restore blacklisted tags.</div>
                </div>
                <div class="admin-card" data-section="feed-refresh">
                    <div class="admin-card-icon">🔄</div>
                    <div class="admin-card-title">Feed Refresh</div>
                    <div class="admin-card-desc">Trigger a full feed refresh.</div>
                </div>
                <div class="admin-card" data-section="recommendations">
                    <div class="admin-card-icon">✨</div>
                    <div class="admin-card-title">Recommendations</div>
                    <div class="admin-card-desc">Recompute recommendation scores.</div>
                </div>
                <div class="admin-card" data-section="stats">
                    <div class="admin-card-icon">📊</div>
                    <div class="admin-card-title">System Stats</div>
                    <div class="admin-card-desc">View site stats and digest log.</div>
                </div>
<?php if ($androidApkFile && $androidApkVersion): ?>
                <a class="admin-card admin-card-download" href="downloads/<?= htmlspecialchars($androidApkFile) ?>" download="<?= htmlspecialchars($androidApkFile) ?>">
                    <div class="admin-card-icon">📱</div>
                    <div class="admin-card-title">Android App <?= htmlspecialchars($androidApkVersion) ?></div>
                    <div class="admin-card-desc">Download MicahPod <?= htmlspecialchars($androidApkVersion) ?> APK for sideload install.</div>
                </a>
<?php else: ?>
                <div class="admin-card admin-card-download" aria-disabled="true">
                    <div class="admin-card-icon">📱</div>
                    <div class="admin-card-title">Android App</div>
                    <div class="admin-card-desc">No APK found in downloads/.</div>
                </div>
<?php endif; ?>
            </div>
        </section>

        <section id="section-stats" class="admin-panel-section">
            <h2>System Stats</h2>
            <div id="admin-stats" class="stats-grid"></div>
            <h3>Digest Log</h3>
            <div id="digest-log" class="admin-table"></div>
        </section>

        <section id="section-podcasts" class="admin-panel-section">
            <h2>Manage Podcasts</h2>
            <div class="admin-actions">
                <input type="text" id="itunes-search" placeholder="Search iTunes for podcasts">
                <button id="itunes-search-btn" class="btn">Search</button>
            </div>
            <div id="itunes-results" class="search-results"></div>
            <div class="admin-form">
                <h3>Add / Edit Podcast</h3>
                <form id="podcast-form">
                    <input type="hidden" name="id">
                    <label>Title</label>
                    <input type="text" name="title" required>
                    <label>Feed URL (RSS)</label>
                    <input type="url" name="feed_url">
                    <label>Website URL</label>
                    <input type="url" name="website_url">
                    <label>Artwork URL</label>
                    <input type="url" name="artwork_url">
                    <label>Author</label>
                    <input type="text" name="author">
                    <label>Category</label>
                    <input type="text" name="category">
                    <label>Description</label>
                    <textarea name="description"></textarea>
                    <label class="toggle">
                        <input type="checkbox" name="is_manual">
                        <span>Manual (no RSS)</span>
                    </label>
                    <button class="btn btn-primary" type="submit">Save Podcast</button>
                </form>
            </div>
            <div id="podcast-list" class="admin-table"></div>

            <h3>Manual Episode Entry</h3>
            <form id="manual-episode-form" class="admin-form">
                <label>Podcast</label>
                <select name="podcast_id" id="manual-podcast-select"></select>
                <label>Title</label>
                <input type="text" name="title" required>
                <label>Description</label>
                <textarea name="description"></textarea>
                <label>Publish Date</label>
                <input type="datetime-local" name="pub_date" required>
                <label>Audio URL</label>
                <input type="url" name="audio_url">
                <label>Artwork URL</label>
                <input type="url" name="artwork_url">
                <button class="btn btn-primary" type="submit">Add Episode</button>
            </form>
        </section>

        <section id="section-users" class="admin-panel-section">
            <h2>Manage Users</h2>
            <div id="user-list" class="admin-table"></div>
        </section>

        <section id="section-tags" class="admin-panel-section">
            <h2>Tag Management</h2>
            <div class="admin-actions" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
                <button id="extract-tags" class="btn">Extract Tags</button>
                <input type="text" id="tag-filter" placeholder="Search tags..." class="input-field" style="flex:1;min-width:150px;">
                <select id="tag-type-filter" class="input-field" style="width:auto;">
                    <option value="">All types</option>
                    <option value="guest">Guests</option>
                    <option value="topic">Topics</option>
                </select>
                <span id="tag-count" class="muted" style="font-size:0.85rem;"></span>
            </div>
            <p class="muted" style="font-size:0.8rem;margin:8px 0;">Use ✏️ Edit to rename, 🔗 Merge to combine duplicates, 🗑️ Delete to remove and blacklist.</p>
            <div id="tag-list" class="admin-table"></div>
        </section>

        <section id="section-episode-tags" class="admin-panel-section">
            <h2>Episode Tags</h2>
            <div class="admin-actions" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
                <input type="text" id="episode-tag-search" placeholder="Search episodes by title..." class="input-field" style="flex:1;min-width:200px;">
                <button id="episode-tag-search-btn" class="btn">Search</button>
            </div>
            <div id="episode-tag-results" class="admin-table" style="margin-top:12px;"></div>
            <div id="episode-tag-detail" style="display:none;margin-top:16px;">
                <h3 id="episode-tag-detail-title" style="margin-bottom:8px;"></h3>
                <div style="display:flex;gap:10px;align-items:center;margin-bottom:12px;flex-wrap:wrap;">
                    <button id="episode-reextract" class="btn btn-small">🔄 Re-extract AI Tags</button>
                    <span class="muted" style="font-size:0.8rem;">Removes AI tags and re-runs extraction</span>
                </div>
                <div id="episode-tag-current" style="margin-bottom:12px;"></div>
                <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
                    <input type="text" id="episode-tag-add-name" placeholder="Tag name" class="input-field" style="flex:1;min-width:120px;">
                    <select id="episode-tag-add-type" class="input-field" style="width:auto;">
                        <option value="topic">Topic</option>
                        <option value="guest">Guest</option>
                    </select>
                    <button id="episode-tag-add-btn" class="btn btn-small">+ Add Tag</button>
                </div>
            </div>
        </section>

        <section id="section-tag-blacklist" class="admin-panel-section">
            <h2>Tag Blacklist</h2>
            <div id="tag-blacklist-list" class="admin-table"></div>
        </section>

        <section id="section-feed-refresh" class="admin-panel-section">
            <h2>Feed Refresh</h2>
            <div class="admin-actions">
                <button id="refresh-feeds" class="btn">Refresh Feeds</button>
            </div>
            <pre id="system-output" class="system-output"></pre>
        </section>

        <section id="section-recommendations" class="admin-panel-section">
            <h2>Recommendations</h2>
            <div class="admin-actions">
                <button id="recompute-recs" class="btn">Recompute Recommendations</button>
            </div>
        </section>
    </main>

    <script src="js/admin.js"></script>
</body>
</html>
