<?php
/**
 * Refresh podcast feeds — fetches RSS and inserts new episodes.
 * Works entirely in PHP (no Python dependency).
 */
require_once __DIR__ . '/config.php';

// Allow cron access via secret key (GET)
$key = $_GET['key'] ?? '';
$digestKey = env('DIGEST_KEY');
if ($key && $digestKey && hash_equals($digestKey, $key)) {
    // Authenticated via key — skip session/CSRF checks
} else {
    require_login();
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        json_response(['error' => 'Method not allowed'], 405);
    }
    verify_csrf();
}

$podcastId = isset($_GET['podcast_id']) ? (int)$_GET['podcast_id'] : null;
$result = refresh_podcast_feeds($podcastId);
json_response($result);
