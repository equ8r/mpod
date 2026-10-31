<?php
/**
 * Refresh podcast feeds — fetches RSS and inserts new episodes.
 * Works entirely in PHP (no Python dependency).
 */
require_once __DIR__ . '/config.php';

// Allow cron access via secret key (GET)
$key = $_GET['key'] ?? '';
$digestKey = env('DIGEST_KEY');
$viaCronKey = false;
if ($key && $digestKey && hash_equals($digestKey, $key)) {
    // Authenticated via key — skip session/CSRF checks
    $viaCronKey = true;
} else {
    require_login();
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        json_response(['error' => 'Method not allowed'], 405);
    }
    verify_csrf();
}

$podcastId = isset($_GET['podcast_id']) ? (int)$_GET['podcast_id'] : null;
// Cron keyed runs use follower + tier filters; manual/admin refreshes do not.
$scheduled = $viaCronKey && !$podcastId;
$result = refresh_podcast_feeds($podcastId, $scheduled);
json_response($result);
