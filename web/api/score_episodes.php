<?php
require_once __DIR__ . '/config.php';

function score_episodes_for_user(PDO $pdo, int $userId, array $options = []): array {
    $profileStmt = $pdo->prepare("SELECT profile_json FROM taste_profiles WHERE user_id = ?");
    $profileStmt->execute([$userId]);
    $profileRow = $profileStmt->fetch();
    if (!$profileRow || !$profileRow['profile_json']) {
        return ['ok' => false, 'error' => 'Taste profile not found', 'scored' => 0];
    }
    $profile = json_decode($profileRow['profile_json'], true);
    if (!is_array($profile)) {
        return ['ok' => false, 'error' => 'Taste profile is invalid', 'scored' => 0];
    }

    $episodeIds = $options['episode_ids'] ?? null;
    $params = [$userId];
    $where = [
        'er.id IS NULL',
        'p.active = 1',
        'e.pub_date >= DATE_SUB(NOW(), INTERVAL 90 DAY)',
    ];
    if (is_array($episodeIds) && $episodeIds) {
        $episodeIds = array_values(array_unique(array_map('intval', $episodeIds)));
        $placeholders = implode(',', array_fill(0, count($episodeIds), '?'));
        $where[] = "e.id IN ({$placeholders})";
        $params = array_merge($params, $episodeIds);
    }

    $whereSql = implode(' AND ', $where);
    $episodeStmt = $pdo->prepare(
        "SELECT e.id, e.podcast_id
         FROM episodes e
         JOIN podcasts p ON p.id = e.podcast_id
         LEFT JOIN episode_ratings er ON er.episode_id = e.id AND er.user_id = ?
         WHERE {$whereSql}"
    );
    $episodeStmt->execute($params);
    $episodes = $episodeStmt->fetchAll();

    if (!$episodes) {
        return ['ok' => true, 'scored' => 0, 'output' => 'No episodes to score.'];
    }

    $episodeIds = array_map(fn($row) => (int)$row['id'], $episodes);
    $tagStmt = null;
    $tagsByEpisode = [];
    if ($episodeIds) {
        $inClause = implode(',', array_fill(0, count($episodeIds), '?'));
        $tagStmt = $pdo->prepare(
            "SELECT et.episode_id, t.name, t.type
             FROM episode_tags et
             JOIN tags t ON t.id = et.tag_id
             WHERE et.episode_id IN ({$inClause})"
        );
        $tagStmt->execute($episodeIds);
        foreach ($tagStmt->fetchAll() as $row) {
            $eid = (int)$row['episode_id'];
            if (!isset($tagsByEpisode[$eid])) {
                $tagsByEpisode[$eid] = ['topic' => [], 'guest' => []];
            }
            $tagsByEpisode[$eid][$row['type']][] = $row['name'];
        }
    }

    $podcastAffinity = $profile['podcast_affinity'] ?? [];
    $topicPrefs = $profile['topic_preferences'] ?? [];
    $guestPrefs = $profile['guest_preferences'] ?? [];

    $insertStmt = $pdo->prepare(
        "INSERT INTO recommendation_scores (user_id, episode_id, score, factors_json, computed_at)
         VALUES (?, ?, ?, ?, NOW())
         ON DUPLICATE KEY UPDATE score = VALUES(score), factors_json = VALUES(factors_json), computed_at = NOW()"
    );

    $scored = 0;
    foreach ($episodes as $episode) {
        $episodeId = (int)$episode['id'];
        $podcastId = (string)$episode['podcast_id'];

        $podcastScore = 0.0;
        if (isset($podcastAffinity[$podcastId])) {
            $base = (float)$podcastAffinity[$podcastId]['score'];
            $count = (int)($podcastAffinity[$podcastId]['count'] ?? 0);
            $confidence = min(1.0, $count / 10);
            $podcastScore = $base * $confidence;
        }

        $episodeTags = $tagsByEpisode[$episodeId] ?? ['topic' => [], 'guest' => []];
        $topicScores = [];
        $topTopic = null;
        $topTopicScore = null;
        foreach ($episodeTags['topic'] as $topicName) {
            if (isset($topicPrefs[$topicName])) {
                $score = (float)$topicPrefs[$topicName]['score'];
                $topicScores[] = $score;
                if ($topTopicScore === null || $score > $topTopicScore) {
                    $topTopic = $topicName;
                    $topTopicScore = $score;
                }
            }
        }
        $topicScore = $topicScores ? array_sum($topicScores) / count($topicScores) : 0.0;

        $guestScore = null;
        $topGuest = null;
        $topGuestScore = null;
        foreach ($episodeTags['guest'] as $guestName) {
            if (isset($guestPrefs[$guestName])) {
                $score = (float)$guestPrefs[$guestName]['score'];
                if ($topGuestScore === null || $score > $topGuestScore) {
                    $topGuest = $guestName;
                    $topGuestScore = $score;
                }
                if ($guestScore === null || $score > $guestScore) {
                    $guestScore = $score;
                }
            }
        }
        if ($guestScore === null) {
            $guestScore = 0.0;
        }

        $finalScore = ($podcastScore * 0.40) + ($topicScore * 0.35) + ($guestScore * 0.25);
        $finalScore = ($finalScore + 1) / 2;
        if ($finalScore < 0) $finalScore = 0;
        if ($finalScore > 1) $finalScore = 1;

        $factors = [
            'podcast_affinity' => round($podcastScore, 3),
            'topic_match' => round($topicScore, 3),
            'guest_match' => round($guestScore, 3),
            'top_topic' => $topTopic,
            'top_guest' => $topGuest,
        ];

        $insertStmt->execute([$userId, $episodeId, $finalScore, json_encode($factors, JSON_UNESCAPED_SLASHES)]);
        $scored++;
    }

    return ['ok' => true, 'scored' => $scored, 'output' => "Scored {$scored} episodes."];
}

if (PHP_SAPI !== 'cli' && realpath(__FILE__) === realpath($_SERVER['SCRIPT_FILENAME'] ?? '')) {
    $pdo = db();
    require_method('POST');
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $targetUserId = isset($data['user_id']) ? (int)$data['user_id'] : (int)$user['id'];
    if ($targetUserId !== (int)$user['id'] && $user['role'] !== 'admin') {
        json_response(['error' => 'Forbidden'], 403);
    }
    $episodeIds = isset($data['episode_ids']) && is_array($data['episode_ids']) ? $data['episode_ids'] : null;
    $result = score_episodes_for_user($pdo, $targetUserId, ['episode_ids' => $episodeIds]);
    json_response($result);
}
