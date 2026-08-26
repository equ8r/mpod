<?php
declare(strict_types=1);

session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'httponly' => true,
    'samesite' => 'Lax',
]);
session_start();

function load_env(string $path): array {
    if (!file_exists($path)) {
        return [];
    }
    $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    $env = [];
    foreach ($lines as $line) {
        $line = trim($line);
        if ($line === '' || str_starts_with($line, '#') || strpos($line, '=') === false) {
            continue;
        }
        [$key, $value] = explode('=', $line, 2);
        $env[trim($key)] = trim($value);
    }
    return $env;
}

function env(string $key, ?string $default = null): ?string {
    static $env = null;
    if ($env === null) {
        $root = dirname(__DIR__, 2);
        $env = load_env($root . DIRECTORY_SEPARATOR . '.env');
    }
    return $env[$key] ?? $default;
}

function get_db(): PDO { return db(); }

function db(): PDO {
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $host = env('DB_HOST');
    $name = env('DB_NAME');
    $user = env('DB_USER');
    $pass = env('DB_PASS');
    if (!$host || !$name || !$user) {
        throw new RuntimeException('Database credentials missing in .env');
    }
    $dsn = "mysql:host={$host};dbname={$name};charset=utf8mb4";
    $pdo = new PDO($dsn, $user, $pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    return $pdo;
}

function json_response($data, int $code = 200): void {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data);
    exit;
}

function get_json_body(): array {
    $raw = file_get_contents('php://input');
    if (!$raw) {
        return [];
    }
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function current_user(): ?array {
    if (!isset($_SESSION['user_id'])) {
        return null;
    }
    $stmt = db()->prepare(
        'SELECT id, username, email, role, email_verified, digest_enabled, digest_times, default_recommended_only, hide_played
         FROM users WHERE id = ?'
    );
    $stmt->execute([$_SESSION['user_id']]);
    $user = $stmt->fetch();
    return $user ?: null;
}

function require_login(): array {
    $user = current_user();
    if (!$user) {
        json_response(['error' => 'Unauthorized'], 401);
    }
    return $user;
}

function require_admin(): array {
    $user = require_login();
    if ($user['role'] !== 'admin') {
        json_response(['error' => 'Forbidden'], 403);
    }
    return $user;
}

function generate_token(int $bytes = 32): string {
    return bin2hex(random_bytes($bytes));
}

function csrf_token(): string {
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = generate_token(16);
    }
    return $_SESSION['csrf_token'];
}

function verify_csrf(): void {
    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'GET') {
        return;
    }
    $token = $_SERVER['HTTP_X_CSRF_TOKEN'] ?? ($_POST['csrf_token'] ?? '');
    if (!$token || empty($_SESSION['csrf_token']) || !hash_equals($_SESSION['csrf_token'], $token)) {
        json_response(['error' => 'Invalid CSRF token'], 400);
    }
}

function compute_episode_completed(PDO $pdo, int $episodeId, float $positionSeconds): int {
    $durationStmt = $pdo->prepare('SELECT duration_seconds FROM episodes WHERE id = ?');
    $durationStmt->execute([$episodeId]);
    $duration = $durationStmt->fetchColumn();
    if ($duration === false || $duration === null) {
        return 0;
    }
    $durationVal = (float)$duration;
    if ($durationVal <= 0) {
        return 0;
    }
    return ($positionSeconds >= ($durationVal * 0.9)) ? 1 : 0;
}

function parse_client_updated_at(?string $value): ?string {
    if ($value === null || trim($value) === '') {
        return null;
    }
    $ts = strtotime($value);
    if ($ts === false) {
        return null;
    }
    return gmdate('Y-m-d H:i:s', $ts);
}

/**
 * Upsert per-episode listening progress.
 * Manual/forced writes always win. Auto writes only apply when newer than stored updated_at.
 *
 * @return array{applied:bool,reason:string,completed:int,progress:?array}
 */
function upsert_episode_progress(
    PDO $pdo,
    int $userId,
    int $episodeId,
    float $positionSeconds,
    bool $force = false,
    string $source = 'auto',
    ?string $clientUpdatedAt = null
): array {
    $allowedSources = ['auto', 'manual', 'beacon'];
    if (!in_array($source, $allowedSources, true)) {
        $source = 'auto';
    }
    $positionSeconds = max(0.0, $positionSeconds);
    $completed = compute_episode_completed($pdo, $episodeId, $positionSeconds);
    $incomingAt = parse_client_updated_at($clientUpdatedAt) ?? gmdate('Y-m-d H:i:s');

    $existingStmt = $pdo->prepare(
        'SELECT position_seconds, completed, updated_at, started_at, last_played_at, saved_manually_at, save_source
         FROM user_episode_progress
         WHERE user_id = ? AND episode_id = ?'
    );
    $existingStmt->execute([$userId, $episodeId]);
    $existing = $existingStmt->fetch();

    if ($existing && !$force) {
        $existingTs = strtotime((string)$existing['updated_at']);
        $incomingTs = strtotime($incomingAt);
        if ($existingTs !== false && $incomingTs !== false && $incomingTs < $existingTs) {
            return [
                'applied' => false,
                'reason' => 'stale',
                'completed' => (int)$existing['completed'],
                'progress' => [
                    'episode_id' => $episodeId,
                    'position_seconds' => (float)$existing['position_seconds'],
                    'completed' => (int)$existing['completed'],
                    'updated_at' => $existing['updated_at'],
                    'started_at' => $existing['started_at'],
                    'last_played_at' => $existing['last_played_at'],
                    'saved_manually_at' => $existing['saved_manually_at'],
                    'save_source' => $existing['save_source'],
                ],
            ];
        }

        // Guard against auto/beacon clients that reload media and report 0:00,
        // which used to wipe Continue Listening bookmarks.
        $existingPos = (float)$existing['position_seconds'];
        if (
            $source !== 'manual'
            && $existingPos >= 30.0
            && $positionSeconds < 15.0
            && ($existingPos - $positionSeconds) >= 20.0
        ) {
            return [
                'applied' => false,
                'reason' => 'zero_regression',
                'completed' => (int)$existing['completed'],
                'progress' => [
                    'episode_id' => $episodeId,
                    'position_seconds' => $existingPos,
                    'completed' => (int)$existing['completed'],
                    'updated_at' => $existing['updated_at'],
                    'started_at' => $existing['started_at'],
                    'last_played_at' => $existing['last_played_at'],
                    'saved_manually_at' => $existing['saved_manually_at'],
                    'save_source' => $existing['save_source'],
                ],
            ];
        }
    }

    $startedAt = $existing['started_at'] ?? gmdate('Y-m-d H:i:s');
    $savedManuallyAt = $existing['saved_manually_at'] ?? null;
    if ($force || $source === 'manual') {
        $savedManuallyAt = gmdate('Y-m-d H:i:s');
        $source = 'manual';
    }

    $stmt = $pdo->prepare(
        'INSERT INTO user_episode_progress
            (user_id, episode_id, position_seconds, completed, started_at, last_played_at, saved_manually_at, save_source, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
            position_seconds = VALUES(position_seconds),
            completed = VALUES(completed),
            started_at = COALESCE(started_at, VALUES(started_at)),
            last_played_at = VALUES(last_played_at),
            saved_manually_at = VALUES(saved_manually_at),
            save_source = VALUES(save_source),
            updated_at = VALUES(updated_at)'
    );
    $now = gmdate('Y-m-d H:i:s');
    $stmt->execute([
        $userId,
        $episodeId,
        $positionSeconds,
        $completed,
        $startedAt,
        $now,
        $savedManuallyAt,
        $source,
        $force || $source === 'manual' ? $now : $incomingAt,
    ]);

    $rowStmt = $pdo->prepare(
        'SELECT episode_id, position_seconds, completed, updated_at, started_at, last_played_at, saved_manually_at, save_source
         FROM user_episode_progress
         WHERE user_id = ? AND episode_id = ?'
    );
    $rowStmt->execute([$userId, $episodeId]);
    $row = $rowStmt->fetch();

    return [
        'applied' => true,
        'reason' => 'ok',
        'completed' => $completed,
        'progress' => $row ? [
            'episode_id' => (int)$row['episode_id'],
            'position_seconds' => (float)$row['position_seconds'],
            'completed' => (int)$row['completed'],
            'updated_at' => $row['updated_at'],
            'started_at' => $row['started_at'],
            'last_played_at' => $row['last_played_at'],
            'saved_manually_at' => $row['saved_manually_at'],
            'save_source' => $row['save_source'],
        ] : null,
    ];
}

function rate_limit(string $key, int $maxAttempts, int $windowSeconds): void {
    $now = time();
    if (!isset($_SESSION['rate_limit'])) {
        $_SESSION['rate_limit'] = [];
    }
    if (!isset($_SESSION['rate_limit'][$key])) {
        $_SESSION['rate_limit'][$key] = ['count' => 0, 'reset' => $now + $windowSeconds];
    }
    if ($now > $_SESSION['rate_limit'][$key]['reset']) {
        $_SESSION['rate_limit'][$key] = ['count' => 0, 'reset' => $now + $windowSeconds];
    }
    $_SESSION['rate_limit'][$key]['count']++;
    if ($_SESSION['rate_limit'][$key]['count'] > $maxAttempts) {
        json_response(['error' => 'Too many requests. Try again later.'], 429);
    }
}

function smtp_send(string $to, string $subject, string $html): bool {
    $host = env('SMTP_HOST');
    $port = (int)env('SMTP_PORT', '587');
    $user = env('SMTP_USER');
    $pass = env('SMTP_PASS');
    $from = env('SMTP_FROM', $user);
    $fromName = env('SMTP_FROM_NAME', 'MicahPod');
    if (!$host || !$user || !$pass) {
        return false;
    }

    $socket = @stream_socket_client("tcp://{$host}:{$port}", $errno, $errstr, 15);
    if (!$socket) {
        return false;
    }
    stream_set_timeout($socket, 10);

    // Read full SMTP response (handles multi-line replies like EHLO)
    $readReply = function () use ($socket): string {
        $reply = '';
        while (($line = fgets($socket, 512)) !== false) {
            $reply .= $line;
            // Last line of multi-line response has space at position 3 (e.g. "250 OK")
            // Continuation lines have dash (e.g. "250-SIZE 35882577")
            if (isset($line[3]) && $line[3] !== '-') {
                break;
            }
        }
        return $reply;
    };
    $write = function (string $cmd) use ($socket): void {
        fwrite($socket, $cmd . "\r\n");
    };

    $readReply(); // greeting
    $write("EHLO micahpod");
    $readReply();
    $write("STARTTLS");
    $startTls = $readReply();
    if (strpos($startTls, '220') === false) {
        fclose($socket);
        return false;
    }

    if (!stream_socket_enable_crypto($socket, true, STREAM_CRYPTO_METHOD_TLSv1_2_CLIENT)) {
        fclose($socket);
        return false;
    }

    $write("EHLO micahpod");
    $readReply();
    $write("AUTH LOGIN");
    $authReply = $readReply();
    if (strpos($authReply, '334') === false) {
        fclose($socket);
        return false;
    }
    $write(base64_encode($user));
    $readReply();
    $write(base64_encode($pass));
    $authResult = $readReply();
    if (strpos($authResult, '235') === false) {
        fclose($socket);
        return false;
    }

    $write("MAIL FROM:<{$user}>");
    $readReply();
    $write("RCPT TO:<{$to}>");
    $rcpt = $readReply();
    if (strpos($rcpt, '250') === false) {
        fclose($socket);
        return false;
    }
    $write("DATA");
    $readReply();

    $headers = [];
    $headers[] = "From: {$fromName} <{$user}>";
    $headers[] = "To: {$to}";
    $headers[] = "Subject: {$subject}";
    $headers[] = "MIME-Version: 1.0";
    $headers[] = "Content-Type: text/html; charset=UTF-8";
    $headers[] = "Date: " . date('r');

    $message = implode("\r\n", $headers) . "\r\n\r\n" . $html . "\r\n.";
    $write($message);
    $dataResult = $readReply();
    $write("QUIT");
    fclose($socket);

    return strpos($dataResult, '250') !== false;
}

function require_method(string $method): void {
    if ($_SERVER['REQUEST_METHOD'] !== $method) {
        json_response(['error' => 'Method not allowed'], 405);
    }
}

function base_url(): string {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $path = rtrim(dirname($_SERVER['SCRIPT_NAME']), '/\\');
    return "{$scheme}://{$host}{$path}";
}

function site_url(): string {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $path = $_SERVER['SCRIPT_NAME'] ?? '/';
    $path = preg_replace('#/api/.*$#', '', $path);
    $path = rtrim($path, '/\\');
    return "{$scheme}://{$host}{$path}";
}

function refresh_podcast_feeds(?int $podcastId = null): array {
    $pdo = db();
    if ($podcastId) {
        $stmt = $pdo->prepare("SELECT id, title, feed_url, last_new_episode, etag, last_modified FROM podcasts WHERE id = ? AND active = 1 AND feed_url IS NOT NULL");
        $stmt->execute([$podcastId]);
        $shows = $stmt->fetchAll();
    } else {
        $shows = $pdo->query("SELECT id, title, feed_url, last_new_episode, etag, last_modified FROM podcasts WHERE active = 1 AND feed_url IS NOT NULL")->fetchAll();
    }

    if (empty($shows)) {
        return ['ok' => true, 'output' => 'No podcasts to refresh', 'new_episodes' => 0, 'podcasts_refreshed' => 0, 'details' => []];
    }

    $epStmt = $pdo->prepare(
        "INSERT IGNORE INTO episodes (podcast_id, guid, title, description, description_html, pub_date, duration_seconds, audio_url, artwork_url, episode_number, season_number)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    );

    $totalNew = 0;
    $results = [];
    $lines = [];
    $allNewEpisodeIds = [];

    foreach ($shows as $show) {
        $headers = ['User-Agent: MicahPod/1.0'];
        if (!empty($show['etag'])) {
            $headers[] = 'If-None-Match: ' . $show['etag'];
        }
        if (!empty($show['last_modified'])) {
            $headers[] = 'If-Modified-Since: ' . $show['last_modified'];
        }
        $ctx = stream_context_create([
            'http' => [
                'timeout' => 30,
                'user_agent' => 'MicahPod/1.0',
                'header' => implode("\r\n", $headers),
            ],
        ]);
        $xml = @file_get_contents($show['feed_url'], false, $ctx);
        $responseHeaders = $http_response_header ?? [];
        $statusCode = null;
        if (!empty($responseHeaders[0]) && preg_match('#HTTP/\S+\s+(\d{3})#', $responseHeaders[0], $matches)) {
            $statusCode = (int)$matches[1];
        }
        $respEtag = null;
        $respLastMod = null;
        foreach ($responseHeaders as $header) {
            $lower = strtolower($header);
            if (str_starts_with($lower, 'etag:')) {
                $respEtag = trim(substr($header, 5));
            } elseif (str_starts_with($lower, 'last-modified:')) {
                $respLastMod = trim(substr($header, 14));
            }
        }
        if ($statusCode === 304) {
            $etagToStore = $respEtag ?? $show['etag'];
            $lastModToStore = $respLastMod ?? $show['last_modified'];
            $pdo->prepare("UPDATE podcasts SET last_checked = NOW(), etag = ?, last_modified = ? WHERE id = ?")
                ->execute([$etagToStore, $lastModToStore, $show['id']]);
            $results[] = [
                'podcast' => $show['title'],
                'status' => 'ok',
                'new_episodes' => 0,
                'total_parsed' => 0,
                'new_episode_ids' => [],
            ];
            $lines[] = "{$show['title']}: not modified";
            continue;
        }
        if (!$xml) {
            $results[] = ['podcast' => $show['title'], 'status' => 'failed', 'error' => 'Could not fetch feed'];
            $lines[] = "{$show['title']}: failed to fetch feed";
            continue;
        }

        libxml_use_internal_errors(true);
        $feed = simplexml_load_string($xml);
        if (!$feed) {
            $results[] = ['podcast' => $show['title'], 'status' => 'failed', 'error' => 'Could not parse XML'];
            $lines[] = "{$show['title']}: failed to parse XML";
            continue;
        }

        $channel = $feed->channel ?? $feed;
        $items = $channel->item ?? [];
        $totalParsed = count($items);

        $itunes_ch = $channel->children('http://www.itunes.com/dtds/podcast-1.0.dtd');
        $feedArt = (string)($itunes_ch->image['href'] ?? '');
        if ($feedArt) {
            $pdo->prepare("UPDATE podcasts SET artwork_url = COALESCE(NULLIF(artwork_url, ''), ?) WHERE id = ?")
                ->execute([$feedArt, $show['id']]);
        }

        $newCount = 0;
        $podcastNewIds = [];
        $lastNewEpisode = $show['last_new_episode'] ?? null;
        $lastNewEpisodeTs = $lastNewEpisode ? strtotime($lastNewEpisode) : null;
        foreach ($items as $item) {
            $itunes_ep = $item->children('http://www.itunes.com/dtds/podcast-1.0.dtd');

            $guid = (string)($item->guid ?? $item->link ?? '');
            $title = (string)($item->title ?? 'Untitled');
            $descHtml = (string)($item->children('content', true)->encoded ?? $item->description ?? '');
            $descText = strip_tags(html_entity_decode($descHtml));
            $pubDate = (string)($item->pubDate ?? '');
            $pubTimestamp = $pubDate ? strtotime($pubDate) : time();
            if ($pubTimestamp === false) {
                $pubTimestamp = time();
            }
            if ($lastNewEpisodeTs !== null && $pubTimestamp <= $lastNewEpisodeTs) {
                continue;
            }
            $pubDt = date('Y-m-d H:i:s', $pubTimestamp);

            $dur = (string)($itunes_ep->duration ?? '');
            $durSec = null;
            if ($dur) {
                $parts = explode(':', $dur);
                if (count($parts) === 3) $durSec = (int)$parts[0] * 3600 + (int)$parts[1] * 60 + (int)$parts[2];
                elseif (count($parts) === 2) $durSec = (int)$parts[0] * 60 + (int)$parts[1];
                else $durSec = (int)$dur;
            }

            $enclosure = $item->enclosure;
            $audioUrl = $enclosure ? (string)$enclosure['url'] : null;

            $epArt = (string)($itunes_ep->image['href'] ?? '');
            $epNum = (string)($itunes_ep->episode ?? '');
            $seasonNum = (string)($itunes_ep->season ?? '');

            $epStmt->execute([
                $show['id'], $guid, $title, $descText, $descHtml, $pubDt,
                $durSec, $audioUrl, $epArt ?: null,
                $epNum !== '' ? (int)$epNum : null,
                $seasonNum !== '' ? (int)$seasonNum : null,
            ]);

            if ($epStmt->rowCount() > 0) {
                $newCount++;
                $newId = (int)$pdo->lastInsertId();
                if ($newId > 0) {
                    $podcastNewIds[] = $newId;
                    $allNewEpisodeIds[] = $newId;
                }
            }
        }

        $etagToStore = $respEtag ?? $show['etag'];
        $lastModToStore = $respLastMod ?? $show['last_modified'];
        $pdo->prepare("UPDATE podcasts SET last_checked = NOW(), etag = ?, last_modified = ? WHERE id = ?")
            ->execute([$etagToStore, $lastModToStore, $show['id']]);
        if ($newCount > 0) {
            $pdo->prepare("UPDATE podcasts SET last_new_episode = NOW() WHERE id = ?")->execute([$show['id']]);
        }

        $totalNew += $newCount;
        $results[] = [
            'podcast' => $show['title'],
            'status' => 'ok',
            'new_episodes' => $newCount,
            'total_parsed' => $totalParsed,
            'new_episode_ids' => $podcastNewIds,
        ];
        $lines[] = "{$show['title']}: {$newCount} new episodes";
    }

    return [
        'ok' => true,
        'new_episodes' => $totalNew,
        'podcasts_refreshed' => count($results),
        'details' => $results,
        'new_episode_ids' => $allNewEpisodeIds,
        'output' => implode("\n", $lines),
    ];
}
