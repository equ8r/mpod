<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_admin();
$method = $_SERVER['REQUEST_METHOD'];
$action = $_GET['action'] ?? '';

if ($method === 'GET') {
    $stmt = $pdo->query(
        "SELECT tb.id, tb.tag_name, tb.tag_type, tb.blacklisted_at, u.username AS blacklisted_by
         FROM tag_blacklist tb
         LEFT JOIN users u ON u.id = tb.blacklisted_by
         ORDER BY tb.blacklisted_at DESC"
    );
    json_response(['tags' => $stmt->fetchAll()]);
}

if ($method === 'POST' && $action === 'restore') {
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['id'] ?? 0);
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $stmt = $pdo->prepare("DELETE FROM tag_blacklist WHERE id = ?");
    $stmt->execute([$id]);
    json_response(['ok' => true]);
}

json_response(['error' => 'Method not allowed'], 405);
