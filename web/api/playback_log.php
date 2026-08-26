<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_login();
$method = $_SERVER['REQUEST_METHOD'];
$userId = (int)$user['id'];
$username = (string)($user['username'] ?? 'user');

$logDir = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'logs' . DIRECTORY_SEPARATOR . 'playback';
if (!is_dir($logDir)) {
    @mkdir($logDir, 0755, true);
}

if ($method === 'POST') {
    // App may submit via beacon-style cookie auth without CSRF.
    $isBeacon = isset($_GET['beacon']);
    if (!$isBeacon) {
        verify_csrf();
    }
    $data = get_json_body();
    $logText = (string)($data['log'] ?? '');
    if (trim($logText) === '') {
        json_response(['error' => 'Missing log'], 400);
    }
    // Cap payload ~512KB
    if (strlen($logText) > 524288) {
        $logText = substr($logText, -524288);
    }
    $device = preg_replace('/[^a-zA-Z0-9._\- ]+/', '', (string)($data['device'] ?? 'android'));
    $appVersion = preg_replace('/[^a-zA-Z0-9._\-]+/', '', (string)($data['app_version'] ?? ''));
    $stamp = gmdate('Ymd_His');
    $safeUser = preg_replace('/[^a-zA-Z0-9_\-]+/', '_', $username);
    $base = "{$safeUser}_{$userId}_{$stamp}";
    $path = $logDir . DIRECTORY_SEPARATOR . $base . '.log';
    $header = "user_id={$userId}\nusername={$username}\ndevice={$device}\napp_version={$appVersion}\nsubmitted_at_utc=" . gmdate('Y-m-d H:i:s') . "\n\n";
    $ok = @file_put_contents($path, $header . $logText);
    if ($ok === false) {
        json_response(['error' => 'Failed to store log'], 500);
    }
    $latestPath = $logDir . DIRECTORY_SEPARATOR . "latest_user_{$userId}.log";
    @file_put_contents($latestPath, $header . $logText);
    json_response([
        'ok' => true,
        'bytes' => strlen($logText),
        'file' => basename($path),
    ]);
}

if ($method === 'GET') {
    require_admin();
    $action = trim((string)($_GET['action'] ?? 'list'));
    if ($action === 'latest') {
        $targetUser = isset($_GET['user_id']) ? (int)$_GET['user_id'] : $userId;
        $latestPath = $logDir . DIRECTORY_SEPARATOR . "latest_user_{$targetUser}.log";
        if (!is_file($latestPath)) {
            json_response(['error' => 'No log found for user', 'user_id' => $targetUser], 404);
        }
        json_response([
            'ok' => true,
            'user_id' => $targetUser,
            'file' => basename($latestPath),
            'log' => file_get_contents($latestPath),
        ]);
    }

    $files = glob($logDir . DIRECTORY_SEPARATOR . '*.log') ?: [];
    rsort($files);
    $items = [];
    foreach (array_slice($files, 0, 50) as $file) {
        $items[] = [
            'file' => basename($file),
            'bytes' => filesize($file),
            'mtime' => gmdate('Y-m-d H:i:s', filemtime($file)),
        ];
    }
    json_response(['ok' => true, 'logs' => $items]);
}

json_response(['error' => 'Method not allowed'], 405);
