<?php
/**
 * MicahPod Database Setup
 * Hit this once from a browser to create all tables.
 * DELETE THIS FILE after setup for security.
 */

$env = [];
$envFile = dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env';
if (file_exists($envFile)) {
    foreach (file($envFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line === '' || str_starts_with($line, '#') || strpos($line, '=') === false) continue;
        [$key, $value] = explode('=', $line, 2);
        $env[trim($key)] = trim($value);
    }
}

$host = $env['DB_HOST'] ?? '';
$name = $env['DB_NAME'] ?? '';
$user = $env['DB_USER'] ?? '';
$pass = $env['DB_PASS'] ?? '';

header('Content-Type: text/plain');

if (!$host || !$name || !$user) {
    echo "ERROR: Missing DB credentials in .env\n";
    exit;
}

echo "Connecting to $host / $name as $user...\n";

try {
    $pdo = new PDO("mysql:host=$host;dbname=$name;charset=utf8mb4", $user, $pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    echo "Connected!\n\n";
} catch (PDOException $e) {
    echo "CONNECTION FAILED: " . $e->getMessage() . "\n";
    exit;
}

$tables = [
    'users' => "CREATE TABLE IF NOT EXISTS users (
        id INT AUTO_INCREMENT PRIMARY KEY,
        username VARCHAR(50) UNIQUE NOT NULL,
        email VARCHAR(255) UNIQUE NOT NULL,
        password_hash VARCHAR(255) NOT NULL,
        role ENUM('user', 'admin') DEFAULT 'user',
        email_verified TINYINT(1) DEFAULT 0,
        verification_token VARCHAR(64) NULL,
        reset_token VARCHAR(64) NULL,
        reset_expires DATETIME NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        last_login DATETIME NULL,
        digest_enabled TINYINT(1) DEFAULT 1,
        digest_times VARCHAR(20) DEFAULT '10,22'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'podcasts' => "CREATE TABLE IF NOT EXISTS podcasts (
        id INT AUTO_INCREMENT PRIMARY KEY,
        title VARCHAR(255) NOT NULL,
        description TEXT,
        feed_url VARCHAR(500) NULL,
        website_url VARCHAR(500) NULL,
        artwork_url VARCHAR(500) NULL,
        author VARCHAR(255) NULL,
        category VARCHAR(100) NULL,
        itunes_id INT NULL,
        is_manual TINYINT(1) DEFAULT 0,
        active TINYINT(1) DEFAULT 1,
        last_checked DATETIME NULL,
        last_new_episode DATETIME NULL,
        added_by INT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'episodes' => "CREATE TABLE IF NOT EXISTS episodes (
        id INT AUTO_INCREMENT PRIMARY KEY,
        podcast_id INT NOT NULL,
        guid VARCHAR(500) NULL,
        title VARCHAR(500) NOT NULL,
        description TEXT,
        description_html TEXT,
        pub_date DATETIME NOT NULL,
        duration_seconds INT NULL,
        audio_url VARCHAR(1000) NULL,
        artwork_url VARCHAR(500) NULL,
        episode_number INT NULL,
        season_number INT NULL,
        is_manual TINYINT(1) DEFAULT 0,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_podcast_guid (podcast_id, guid),
        INDEX idx_pub_date (pub_date),
        CONSTRAINT fk_episodes_podcast FOREIGN KEY (podcast_id) REFERENCES podcasts(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'episode_ratings' => "CREATE TABLE IF NOT EXISTS episode_ratings (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        episode_id INT NOT NULL,
        rating TINYINT NOT NULL,
        notes TEXT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_user_episode (user_id, episode_id),
        CONSTRAINT fk_er_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_er_episode FOREIGN KEY (episode_id) REFERENCES episodes(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'tags' => "CREATE TABLE IF NOT EXISTS tags (
        id INT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(255) NOT NULL,
        type ENUM('guest', 'topic') NOT NULL,
        UNIQUE KEY uq_tag (name, type)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'episode_tags' => "CREATE TABLE IF NOT EXISTS episode_tags (
        id INT AUTO_INCREMENT PRIMARY KEY,
        episode_id INT NOT NULL,
        tag_id INT NOT NULL,
        source ENUM('manual', 'ai') DEFAULT 'manual',
        confidence FLOAT DEFAULT 1.0,
        UNIQUE KEY uq_episode_tag (episode_id, tag_id),
        CONSTRAINT fk_et_episode FOREIGN KEY (episode_id) REFERENCES episodes(id) ON DELETE CASCADE,
        CONSTRAINT fk_et_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'tag_ratings' => "CREATE TABLE IF NOT EXISTS tag_ratings (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        tag_id INT NOT NULL,
        rating TINYINT NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_user_tag (user_id, tag_id),
        CONSTRAINT fk_tr_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_tr_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'taste_profiles' => "CREATE TABLE IF NOT EXISTS taste_profiles (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL UNIQUE,
        profile_json JSON,
        last_computed DATETIME,
        version INT DEFAULT 1,
        CONSTRAINT fk_tp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'digest_log' => "CREATE TABLE IF NOT EXISTS digest_log (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        sent_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        episode_count INT,
        episodes_json JSON,
        CONSTRAINT fk_dl_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",

    'recommendation_scores' => "CREATE TABLE IF NOT EXISTS recommendation_scores (
        id INT AUTO_INCREMENT PRIMARY KEY,
        user_id INT NOT NULL,
        episode_id INT NOT NULL,
        score FLOAT NOT NULL,
        factors_json JSON,
        computed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_user_episode_rec (user_id, episode_id),
        CONSTRAINT fk_rs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_rs_episode FOREIGN KEY (episode_id) REFERENCES episodes(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
];

$success = 0;
$failed = 0;

foreach ($tables as $name => $sql) {
    try {
        $pdo->exec($sql);
        echo "✓ Created table: $name\n";
        $success++;
    } catch (PDOException $e) {
        echo "✗ FAILED table $name: " . $e->getMessage() . "\n";
        $failed++;
    }
}

echo "\n---\nDone: $success created, $failed failed.\n";

// Create default admin user (micah / admin)
echo "\nCreating default admin user (micah)...\n";
try {
    $hash = password_hash('MicahPod!2026', PASSWORD_DEFAULT);
    $stmt = $pdo->prepare("INSERT IGNORE INTO users (username, email, password_hash, role, email_verified) VALUES (?, ?, ?, 'admin', 1)");
    $stmt->execute(['micah', 'mty@circpros.com', $hash]);
    if ($stmt->rowCount() > 0) {
        echo "✓ Admin user created: micah / MicahPod!2026\n";
    } else {
        echo "- Admin user already exists, skipped.\n";
    }
} catch (PDOException $e) {
    echo "✗ Failed to create admin user: " . $e->getMessage() . "\n";
}

echo "\n⚠️  DELETE THIS FILE (setup_db.php) after setup!\n";
