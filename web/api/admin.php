<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_admin();
$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET' && $action === 'stats') {
    $stats = [];
    $stats['total_episodes'] = (int)$pdo->query('SELECT COUNT(*) FROM episodes')->fetchColumn();
    $stats['total_ratings'] = (int)$pdo->query('SELECT COUNT(*) FROM episode_ratings')->fetchColumn();
    $stats['active_users'] = (int)$pdo->query("SELECT COUNT(*) FROM users WHERE last_login IS NOT NULL")->fetchColumn();
    $stats['last_refresh'] = $pdo->query("SELECT MAX(last_checked) FROM podcasts")->fetchColumn();
    json_response(['stats' => $stats]);
}

if ($method === 'GET' && $action === 'digest_log') {
    $stmt = $pdo->query(
        "SELECT dl.id, dl.user_id, u.email, dl.sent_at, dl.episode_count
         FROM digest_log dl
         JOIN users u ON u.id = dl.user_id
         ORDER BY dl.sent_at DESC
         LIMIT 100"
    );
    json_response(['digest_log' => $stmt->fetchAll()]);
}

if ($method === 'POST') {
    verify_csrf();
    if ($action === 'extract_tags') {
        require_once __DIR__ . '/extract_tags.php';
        $result = extract_tags_for_unlabeled_episodes($pdo);
        json_response([
            'ok' => $result['ok'] ?? true,
            'output' => $result['output'] ?? 'Tag extraction complete.',
            'episodes_processed' => $result['episodes_processed'] ?? 0,
            'tags_inserted' => $result['tags_inserted'] ?? 0,
        ]);
    }
    if ($action === 'recompute') {
        require_once __DIR__ . '/taste_profile.php';
        require_once __DIR__ . '/score_episodes.php';
        $users = $pdo->query("SELECT id FROM users")->fetchAll();
        $lines = [];
        $profiles = 0;
        $totalScored = 0;
        foreach ($users as $row) {
            $uid = (int)$row['id'];
            $profileResult = compute_taste_profile($pdo, $uid);
            $profiles++;
            $scoreResult = score_episodes_for_user($pdo, $uid);
            $scored = $scoreResult['scored'] ?? 0;
            $totalScored += $scored;
            $lines[] = "User {$uid}: profile v{$profileResult['version']} + {$scored} scores";
        }
        $output = $lines ? implode("\n", $lines) : 'No users found.';
        json_response([
            'ok' => true,
            'output' => $output,
            'profiles' => $profiles,
            'scores' => $totalScored,
        ]);
    }
    json_response(['error' => 'Unknown action'], 400);
}

json_response(['error' => 'Invalid action'], 400);
