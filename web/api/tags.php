<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];
$action = $_GET['action'] ?? '';

if ($method === 'GET') {
    $all = ($_GET['all'] ?? '0') === '1';
    if ($all) {
        require_admin();
        $stmt = $pdo->query(
            "SELECT t.id, t.name, t.type, COUNT(et.id) AS usage_count
             FROM tags t
             LEFT JOIN episode_tags et ON et.tag_id = t.id
             GROUP BY t.id, t.name, t.type
             ORDER BY usage_count DESC"
        );
        json_response(['tags' => $stmt->fetchAll()]);
    }
    $episodeId = isset($_GET['episode_id']) ? (int)$_GET['episode_id'] : 0;
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }
    $stmt = $pdo->prepare(
        "SELECT t.id, t.name, t.type, et.source, et.confidence
         FROM episode_tags et
         JOIN tags t ON t.id = et.tag_id
         WHERE et.episode_id = ?"
    );
    $stmt->execute([$episodeId]);
    json_response(['tags' => $stmt->fetchAll()]);
}

if ($method === 'POST') {
    if ($action === 'purge_noise') {
        // Bulk remove a tag from episodes where it only appeared in descriptions (not titles)
        // Keeps associations where the tag name appears in the episode title
        require_admin();
        verify_csrf();
        $data = get_json_body();
        $tagId = (int)($data['tag_id'] ?? 0);
        if (!$tagId) {
            json_response(['error' => 'Missing tag_id'], 400);
        }
        $tagRow = $pdo->prepare('SELECT name, type FROM tags WHERE id = ?');
        $tagRow->execute([$tagId]);
        $tag = $tagRow->fetch();
        if (!$tag) {
            json_response(['error' => 'Tag not found'], 404);
        }
        // Find all episode associations for this tag
        $assocStmt = $pdo->prepare(
            "SELECT et.episode_id, e.title
             FROM episode_tags et
             JOIN episodes e ON e.id = et.episode_id
             WHERE et.tag_id = ? AND et.source = 'ai'"
        );
        $assocStmt->execute([$tagId]);
        $associations = $assocStmt->fetchAll();
        $removed = 0;
        $kept = 0;
        foreach ($associations as $assoc) {
            // Check if tag name appears in episode title
            if (stripos($assoc['title'], $tag['name']) !== false) {
                $kept++;
            } else {
                // Also check last name for guest tags
                $words = explode(' ', $tag['name']);
                $lastName = end($words);
                if (strlen($lastName) > 2 && stripos($assoc['title'], $lastName) !== false) {
                    $kept++;
                } else {
                    $pdo->prepare('DELETE FROM episode_tags WHERE episode_id = ? AND tag_id = ?')
                        ->execute([$assoc['episode_id'], $tagId]);
                    $removed++;
                }
            }
        }
        json_response(['ok' => true, 'removed' => $removed, 'kept' => $kept, 'tag_name' => $tag['name']]);
    }
    if ($action === 'merge') {
        require_admin();
        verify_csrf();
        $data = get_json_body();
        $sourceId = (int)($data['source_id'] ?? 0);
        $targetId = (int)($data['target_id'] ?? 0);
        if (!$sourceId || !$targetId || $sourceId === $targetId) {
            json_response(['error' => 'Invalid merge request'], 400);
        }
        $pdo->prepare("UPDATE IGNORE episode_tags SET tag_id = ? WHERE tag_id = ?")->execute([$targetId, $sourceId]);
        $pdo->prepare("DELETE FROM tags WHERE id = ?")->execute([$sourceId]);
        json_response(['ok' => true]);
    }
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $episodeId = (int)($data['episode_id'] ?? 0);
    $name = trim($data['name'] ?? '');
    $type = $data['type'] ?? 'topic';
    if (!$episodeId || $name === '' || !in_array($type, ['guest', 'topic'], true)) {
        json_response(['error' => 'Invalid tag data'], 400);
    }
    $stmt = $pdo->prepare(
        "INSERT INTO tags (name, type)
         VALUES (?, ?)
         ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)"
    );
    $stmt->execute([$name, $type]);
    $tagId = (int)$pdo->lastInsertId();
    $stmt2 = $pdo->prepare(
        "INSERT INTO episode_tags (episode_id, tag_id, source, confidence)
         VALUES (?, ?, 'manual', 1.0)
         ON DUPLICATE KEY UPDATE source = 'manual', confidence = 1.0"
    );
    $stmt2->execute([$episodeId, $tagId]);
    json_response(['ok' => true, 'tag_id' => $tagId]);
}

if ($method === 'PUT' && $action === 'update') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['id'] ?? 0);
    $name = trim($data['name'] ?? '');
    $type = $data['type'] ?? null;
    if (!$id) {
        json_response(['error' => 'Invalid tag data'], 400);
    }
    if ($name !== '') {
        $pdo->prepare('UPDATE tags SET name = ? WHERE id = ?')->execute([$name, $id]);
    }
    if ($type && in_array($type, ['guest', 'topic'], true)) {
        $pdo->prepare('UPDATE tags SET type = ? WHERE id = ?')->execute([$type, $id]);
    }
    json_response(['ok' => true]);
}

if ($method === 'GET' && $action === 'episode_tags') {
    require_admin();
    $episodeId = (int)($_GET['episode_id'] ?? 0);
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }
    $stmt = $pdo->prepare(
        "SELECT t.id, t.name, t.type, et.source, et.confidence
         FROM episode_tags et JOIN tags t ON t.id = et.tag_id
         WHERE et.episode_id = ? ORDER BY t.type, t.name"
    );
    $stmt->execute([$episodeId]);
    json_response(['tags' => $stmt->fetchAll()]);
}

if ($method === 'DELETE' && $action === 'remove_episode_tag') {
    require_admin();
    verify_csrf();
    $episodeId = (int)($_GET['episode_id'] ?? 0);
    $tagId = (int)($_GET['tag_id'] ?? 0);
    if (!$episodeId || !$tagId) {
        json_response(['error' => 'Missing ids'], 400);
    }
    $pdo->prepare('DELETE FROM episode_tags WHERE episode_id = ? AND tag_id = ?')->execute([$episodeId, $tagId]);
    json_response(['ok' => true]);
}

if ($method === 'POST' && $action === 'reextract') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $episodeId = (int)($data['episode_id'] ?? 0);
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }
    // Strip all AI-generated tags (keep manual ones)
    $pdo->prepare("DELETE FROM episode_tags WHERE episode_id = ? AND source = 'ai'")->execute([$episodeId]);
    // Check if episode now has zero tags — if so, it'll be picked up by next extraction
    $stmt = $pdo->prepare("SELECT COUNT(*) FROM episode_tags WHERE episode_id = ?");
    $stmt->execute([$episodeId]);
    $remaining = (int)$stmt->fetchColumn();
    if ($remaining === 0) {
        // Run extraction immediately for this episode
        require_once __DIR__ . '/extract_tags.php';
        // Re-run just for this episode by temporarily making it look untagged (it already is)
        $result = extract_tags_for_unlabeled_episodes($pdo);
        json_response(['ok' => true, 'reextracted' => true, 'result' => $result]);
    }
    json_response(['ok' => true, 'reextracted' => false, 'manual_tags_kept' => $remaining]);
}

if ($method === 'DELETE') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $tagStmt = $pdo->prepare('SELECT name, type FROM tags WHERE id = ?');
    $tagStmt->execute([$id]);
    $tagRow = $tagStmt->fetch();
    if ($tagRow) {
        $blacklistStmt = $pdo->prepare(
            "INSERT IGNORE INTO tag_blacklist (tag_name, tag_type, blacklisted_by)
             VALUES (?, ?, ?)"
        );
        $blacklistStmt->execute([$tagRow['name'], $tagRow['type'], $_SESSION['user_id'] ?? null]);
    }
    $stmt = $pdo->prepare('DELETE FROM tags WHERE id = ?');
    $stmt->execute([$id]);
    json_response(['ok' => true]);
}

json_response(['error' => 'Method not allowed'], 405);
