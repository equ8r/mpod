<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$action = $_GET['action'] ?? '';

function parse_digest_hours(?string $times): array {
    if (!$times) {
        return [];
    }
    $parts = preg_split('/\s*,\s*/', $times);
    $hours = [];
    foreach ($parts as $part) {
        if ($part === '') {
            continue;
        }
        if (!is_numeric($part)) {
            continue;
        }
        $hour = (int)$part;
        if ($hour >= 0 && $hour <= 23) {
            $hours[] = $hour;
        }
    }
    return array_values(array_unique($hours));
}

function clip_text(string $text, int $length = 180): string {
    $clean = trim(strip_tags(html_entity_decode($text)));
    if ($clean === '') {
        return '';
    }
    if (strlen($clean) <= $length) {
        return $clean;
    }
    $snippet = substr($clean, 0, $length);
    $lastSpace = strrpos($snippet, ' ');
    if ($lastSpace !== false) {
        $snippet = substr($snippet, 0, $lastSpace);
    }
    return rtrim($snippet) . '…';
}

function format_duration(?int $seconds): string {
    if (!$seconds || $seconds <= 0) {
        return '';
    }
    $mins = (int)floor($seconds / 60);
    $hours = (int)floor($mins / 60);
    $rem = $mins % 60;
    if ($hours > 0) {
        return "{$hours}h {$rem}m";
    }
    return "{$mins} min";
}

function unsubscribe_token(int $userId): string {
    $secret = env('DIGEST_KEY') ?? '';
    return hash_hmac('sha256', (string)$userId, $secret);
}

if ($action === 'unsubscribe') {
    $userId = isset($_GET['user_id']) ? (int)$_GET['user_id'] : 0;
    $token = $_GET['key'] ?? '';
    if (!$userId || $token === '') {
        http_response_code(400);
        echo 'Missing unsubscribe parameters.';
        exit;
    }
    $expected = unsubscribe_token($userId);
    if (!hash_equals($expected, $token)) {
        http_response_code(403);
        echo 'Invalid unsubscribe token.';
        exit;
    }
    $stmt = $pdo->prepare('UPDATE users SET digest_enabled = 0 WHERE id = ?');
    $stmt->execute([$userId]);
    echo 'You have been unsubscribed from MicahPod digests.';
    exit;
}

$secret = env('DIGEST_KEY');
$key = $_GET['key'] ?? '';
if (!$secret || !$key || !hash_equals($secret, $key)) {
    json_response(['error' => 'Forbidden'], 403);
}

$targetHour = isset($_GET['hour']) ? (int)$_GET['hour'] : (int)date('G');

$userStmt = $pdo->query("SELECT id, username, email, digest_times FROM users WHERE digest_enabled = 1 AND email_verified = 1");
$users = $userStmt->fetchAll();

$episodeStmt = $pdo->prepare(
    "SELECT e.id, e.title, e.description, e.description_html, e.pub_date, e.duration_seconds,
            e.audio_url, e.artwork_url, p.title AS podcast_title, p.artwork_url AS podcast_artwork,
            rs.score AS rec_score
     FROM episodes e
     JOIN podcasts p ON p.id = e.podcast_id
     LEFT JOIN recommendation_scores rs ON rs.episode_id = e.id AND rs.user_id = ?
     WHERE p.active = 1 AND e.pub_date > ?
     ORDER BY rs.score DESC, e.pub_date DESC
     LIMIT 20"
);

$lastSentStmt = $pdo->prepare("SELECT sent_at FROM digest_log WHERE user_id = ? ORDER BY sent_at DESC LIMIT 1");
$logStmt = $pdo->prepare("INSERT INTO digest_log (user_id, episode_count, episodes_json) VALUES (?, ?, ?)");

$sent = 0;
$skipped = 0;
$errors = 0;

foreach ($users as $user) {
    $hours = parse_digest_hours($user['digest_times'] ?? '');
    if ($hours && !in_array($targetHour, $hours, true)) {
        $skipped++;
        continue;
    }

    $lastSentStmt->execute([(int)$user['id']]);
    $lastRow = $lastSentStmt->fetch();
    $lastSent = $lastRow['sent_at'] ?? null;
    if ($lastSent && strtotime($lastSent) > (time() - 21600)) {
        $skipped++;
        continue;
    }

    $since = $lastSent ? $lastSent : date('Y-m-d H:i:s', time() - (12 * 3600));
    $episodeStmt->execute([(int)$user['id'], $since]);
    $episodes = $episodeStmt->fetchAll();
    if (!$episodes) {
        $skipped++;
        continue;
    }

    $siteUrl = site_url();
    $logoUrl = $siteUrl . '/assets/logo.png';
    $episodeRows = '';
    foreach ($episodes as $episode) {
        $title = htmlspecialchars((string)$episode['title']);
        $podcast = htmlspecialchars((string)($episode['podcast_title'] ?? ''));
        $descSource = $episode['description_html'] ?? $episode['description'] ?? '';
        $desc = htmlspecialchars(clip_text((string)$descSource, 180));
        $duration = format_duration(isset($episode['duration_seconds']) ? (int)$episode['duration_seconds'] : null);
        $durationText = $duration ? htmlspecialchars($duration) : '';
        $art = $episode['artwork_url'] ?: $episode['podcast_artwork'] ?: $logoUrl;
        if (!preg_match('#^https?://#', $art)) {
            $art = $siteUrl . '/' . ltrim($art, '/');
        }
        $episodeUrl = $siteUrl . '/index.php?episode_id=' . (int)$episode['id'];
        $recBadge = '';
        if ($episode['rec_score'] !== null && (float)$episode['rec_score'] >= 0.7) {
            $recBadge = "<span style=\"display:inline-block;margin-top:6px;padding:4px 8px;border-radius:12px;background:rgba(255,180,60,0.2);color:#ffd791;border:1px solid rgba(255,180,60,0.5);font-size:11px;\">🔥 Recommended</span>";
        }
        $episodeRows .= "
            <tr>
                <td style=\"padding:16px;border-bottom:1px solid #1e2a44;\">
                    <table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">
                        <tr>
                            <td width=\"72\" valign=\"top\">
                                <img src=\"{$art}\" alt=\"\" style=\"width:64px;height:64px;border-radius:8px;object-fit:cover;\">
                            </td>
                            <td style=\"padding-left:12px;vertical-align:top;\">
                                <div style=\"font-size:12px;color:#9fb2d1;margin-bottom:4px;\">{$podcast}</div>
                                <div style=\"font-size:16px;font-weight:700;margin-bottom:4px;\">
                                    <a href=\"{$episodeUrl}\" style=\"color:#79e0ff;text-decoration:none;\">{$title}</a>
                                </div>
                                <div style=\"font-size:12px;color:#8aa0c3;margin-bottom:6px;\">{$durationText}</div>
                                <div style=\"font-size:13px;color:#ccd6e8;line-height:1.4;\">{$desc}</div>
                                {$recBadge}
                            </td>
                        </tr>
                    </table>
                </td>
            </tr>
        ";
    }

    $count = count($episodes);
    $subject = "MicahPod Digest — {$count} new " . ($count === 1 ? 'episode' : 'episodes');
    $manageUrl = $siteUrl . '/index.php';
    $unsubUrl = $siteUrl . '/api/digest.php?action=unsubscribe&user_id=' . (int)$user['id'] . '&key=' . unsubscribe_token((int)$user['id']);
    $dateText = date('F j, Y');

    $html = "
        <html>
        <body style=\"margin:0;padding:0;background:#0f1724;color:#f5f7fb;font-family:Arial, sans-serif;\">
            <table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"padding:24px;\">
                <tr>
                    <td align=\"center\">
                        <table width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#0b1220;border-radius:12px;overflow:hidden;\">
                            <tr>
                                <td style=\"padding:20px 24px;border-bottom:1px solid #1e2a44;\">
                                    <div style=\"font-size:20px;font-weight:700;\">MicahPod Digest</div>
                                    <div style=\"font-size:12px;color:#9fb2d1;\">{$dateText}</div>
                                </td>
                            </tr>
                            <tr>
                                <td style=\"padding:0;\">
                                    <table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#141c2e;\">
                                        {$episodeRows}
                                    </table>
                                </td>
                            </tr>
                            <tr>
                                <td style=\"padding:18px 24px;font-size:12px;color:#9fb2d1;\">
                                    <div style=\"margin-bottom:8px;\">Rate these episodes at <a href=\"{$siteUrl}\" style=\"color:#79e0ff;text-decoration:none;\">micahyoung.us/micahpod</a>.</div>
                                    <div style=\"margin-bottom:8px;\"><a href=\"{$manageUrl}\" style=\"color:#79e0ff;text-decoration:none;\">Manage digest settings</a></div>
                                    <div><a href=\"{$unsubUrl}\" style=\"color:#79e0ff;text-decoration:none;\">Unsubscribe from digests</a></div>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
    ";

    $ok = smtp_send($user['email'], $subject, $html);
    if ($ok) {
        $ids = array_map(fn($row) => (int)$row['id'], $episodes);
        $logStmt->execute([(int)$user['id'], $count, json_encode($ids)]);
        $sent++;
    } else {
        $errors++;
    }
}

json_response([
    'ok' => true,
    'sent' => $sent,
    'skipped' => $skipped,
    'errors' => $errors,
]);
