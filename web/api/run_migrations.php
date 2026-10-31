<?php
error_reporting(E_ALL);
ini_set('display_errors', '1');
header('Content-Type: application/json');

require_once __DIR__ . '/config.php';

$key = $_GET['key'] ?? '';
if ($key !== env('DIGEST_KEY')) {
    http_response_code(403);
    echo json_encode(['error' => 'forbidden']);
    exit;
}

$db = get_db();
$results = [];

$statements = [
    "ALTER TABLE users ADD COLUMN default_recommended_only TINYINT(1) NOT NULL DEFAULT 0",
    "ALTER TABLE users ADD COLUMN hide_played TINYINT(1) NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS user_player_state (
        user_id INT NOT NULL,
        episode_id INT NULL,
        position_seconds FLOAT DEFAULT 0,
        queue JSON NULL,
        speed FLOAT DEFAULT 1,
        volume FLOAT DEFAULT 1,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    "CREATE TABLE IF NOT EXISTS user_episode_progress (
        user_id INT NOT NULL,
        episode_id INT NOT NULL,
        position_seconds FLOAT DEFAULT 0,
        completed TINYINT(1) NOT NULL DEFAULT 0,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (user_id, episode_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    "CREATE TABLE IF NOT EXISTS tag_blacklist (
        id INT AUTO_INCREMENT PRIMARY KEY,
        tag_name VARCHAR(255) NOT NULL,
        tag_type ENUM('topic','guest') NOT NULL,
        blacklisted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        blacklisted_by INT NULL,
        UNIQUE KEY uq_tag_blacklist (tag_name, tag_type)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    "ALTER TABLE podcasts ADD COLUMN curated TINYINT(1) NOT NULL DEFAULT 1",
    "ALTER TABLE user_subscriptions ADD COLUMN include_in_feed TINYINT(1) NOT NULL DEFAULT 1",
    "CREATE TABLE IF NOT EXISTS podcast_introductions (
        id INT AUTO_INCREMENT PRIMARY KEY,
        podcast_id INT NOT NULL,
        user_id INT NOT NULL,
        source VARCHAR(32) NOT NULL DEFAULT 'user',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_podcast_intro_user (podcast_id, user_id),
        KEY idx_podcast_intro_podcast (podcast_id),
        KEY idx_podcast_intro_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
];

foreach ($statements as $sql) {
    try {
        $db->exec($sql);
        $results[] = ['ok' => true, 'sql' => substr($sql, 0, 80)];
    } catch (PDOException $e) {
        $msg = $e->getMessage();
        $dup = (strpos($msg, 'Duplicate column') !== false || strpos($msg, 'already exists') !== false);
        $results[] = ['ok' => $dup, 'skipped' => $dup, 'sql' => substr($sql, 0, 80), 'error' => $msg];
    }
}

echo json_encode(['results' => $results], JSON_PRETTY_PRINT);
