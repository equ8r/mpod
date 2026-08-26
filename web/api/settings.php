<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_login();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $stmt = $pdo->prepare(
        "SELECT digest_enabled, digest_times, default_recommended_only, hide_played
         FROM users WHERE id = ?"
    );
    $stmt->execute([(int)$user['id']]);
    $settings = $stmt->fetch() ?: [];
    json_response(['settings' => $settings]);
}

if ($method === 'POST' || $method === 'PUT') {
    verify_csrf();
    $data = get_json_body();
    $digestEnabled = isset($data['digest_enabled']) ? (int)$data['digest_enabled'] : 1;
    $digestTimes = trim($data['digest_times'] ?? '10,22');
    $recommendedOnly = !empty($data['default_recommended_only']) ? 1 : 0;
    $hidePlayed = !empty($data['hide_played']) ? 1 : 0;

    $stmt = $pdo->prepare(
        "UPDATE users
         SET digest_enabled = ?, digest_times = ?, default_recommended_only = ?, hide_played = ?
         WHERE id = ?"
    );
    $stmt->execute([$digestEnabled ? 1 : 0, $digestTimes, $recommendedOnly, $hidePlayed, (int)$user['id']]);

    json_response(['ok' => true]);
}

json_response(['error' => 'Method not allowed'], 405);
