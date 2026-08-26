<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

function fetch_comment(PDO $pdo, int $commentId): ?array {
    $stmt = $pdo->prepare(
        "SELECT c.id, c.episode_id, c.user_id, c.parent_id, c.body, c.created_at, c.edited_at, u.username
         FROM episode_comments c
         JOIN users u ON u.id = c.user_id
         WHERE c.id = ?"
    );
    $stmt->execute([$commentId]);
    $row = $stmt->fetch();
    if (!$row) {
        return null;
    }
    $row['id'] = (int)$row['id'];
    $row['episode_id'] = (int)$row['episode_id'];
    $row['user_id'] = (int)$row['user_id'];
    $row['parent_id'] = $row['parent_id'] !== null ? (int)$row['parent_id'] : null;
    return $row;
}

function build_comment_tree(array $rows): array {
    $byId = [];
    foreach ($rows as $row) {
        $row['id'] = (int)$row['id'];
        $row['episode_id'] = (int)$row['episode_id'];
        $row['user_id'] = (int)$row['user_id'];
        $row['parent_id'] = $row['parent_id'] !== null ? (int)$row['parent_id'] : null;
        $row['replies'] = [];
        $byId[$row['id']] = $row;
    }
    $tree = [];
    foreach ($byId as $id => &$comment) {
        if ($comment['parent_id'] !== null && isset($byId[$comment['parent_id']])) {
            $byId[$comment['parent_id']]['replies'][] = &$comment;
        } else {
            $tree[] = &$comment;
        }
    }
    unset($comment);
    return $tree;
}

if ($method === 'GET') {
    $action = $_GET['action'] ?? '';
    if ($action === 'counts') {
        $idsParam = trim($_GET['episode_ids'] ?? '');
        if ($idsParam === '') {
            json_response(['counts' => []]);
        }
        $ids = array_values(array_unique(array_filter(array_map('intval', explode(',', $idsParam)))));
        if (!$ids) {
            json_response(['counts' => []]);
        }
        $placeholders = implode(',', array_fill(0, count($ids), '?'));
        $stmt = $pdo->prepare(
            "SELECT episode_id, COUNT(*) AS count
             FROM episode_comments
             WHERE episode_id IN ({$placeholders})
             GROUP BY episode_id"
        );
        $stmt->execute($ids);
        $counts = [];
        foreach ($ids as $id) {
            $counts[$id] = 0;
        }
        foreach ($stmt->fetchAll() as $row) {
            $counts[(int)$row['episode_id']] = (int)$row['count'];
        }
        json_response(['counts' => $counts]);
    }

    $episodeId = isset($_GET['episode_id']) ? (int)$_GET['episode_id'] : 0;
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }
    $stmt = $pdo->prepare(
        "SELECT c.id, c.episode_id, c.user_id, c.parent_id, c.body, c.created_at, c.edited_at, u.username
         FROM episode_comments c
         JOIN users u ON u.id = c.user_id
         WHERE c.episode_id = ?
         ORDER BY c.created_at ASC, c.id ASC"
    );
    $stmt->execute([$episodeId]);
    $rows = $stmt->fetchAll();
    $tree = build_comment_tree($rows);
    json_response([
        'comments' => $tree,
        'total_count' => count($rows),
    ]);
}

if ($method === 'POST') {
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $action = $data['action'] ?? '';
    if ($action !== 'create') {
        json_response(['error' => 'Invalid action'], 400);
    }
    $episodeId = (int)($data['episode_id'] ?? 0);
    $parentId = isset($data['parent_id']) ? (int)$data['parent_id'] : null;
    $body = trim((string)($data['body'] ?? ''));
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }
    if ($body === '') {
        json_response(['error' => 'Comment body required'], 400);
    }
    if (strlen($body) > 5000) {
        json_response(['error' => 'Comment too long (max 5000 characters)'], 400);
    }
    if ($parentId !== null && $parentId > 0) {
        $parentStmt = $pdo->prepare("SELECT id, episode_id, parent_id FROM episode_comments WHERE id = ?");
        $parentStmt->execute([$parentId]);
        $parentRow = $parentStmt->fetch();
        if (!$parentRow) {
            json_response(['error' => 'Parent comment not found'], 404);
        }
        if ((int)$parentRow['episode_id'] !== $episodeId) {
            json_response(['error' => 'Parent comment mismatch'], 400);
        }
        $parentDepth = 1;
        $currentParent = $parentRow['parent_id'] !== null ? (int)$parentRow['parent_id'] : null;
        while ($currentParent !== null) {
            $depthStmt = $pdo->prepare("SELECT parent_id FROM episode_comments WHERE id = ?");
            $depthStmt->execute([$currentParent]);
            $depthRow = $depthStmt->fetch();
            if (!$depthRow) {
                break;
            }
            $parentDepth++;
            if ($parentDepth >= 3) {
                break;
            }
            $currentParent = $depthRow['parent_id'] !== null ? (int)$depthRow['parent_id'] : null;
        }
        if ($parentDepth >= 3) {
            json_response(['error' => 'Max reply depth reached'], 400);
        }
    } else {
        $parentId = null;
    }
    $stmt = $pdo->prepare(
        "INSERT INTO episode_comments (episode_id, user_id, parent_id, body)
         VALUES (?, ?, ?, ?)"
    );
    $stmt->execute([$episodeId, (int)$user['id'], $parentId, $body]);
    $newId = (int)$pdo->lastInsertId();
    $comment = fetch_comment($pdo, $newId);
    json_response(['comment' => $comment, 'ok' => true]);
}

if ($method === 'PUT') {
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $action = $data['action'] ?? ($_GET['action'] ?? '');
    if ($action !== 'edit') {
        json_response(['error' => 'Invalid action'], 400);
    }
    $commentId = (int)($data['comment_id'] ?? ($_GET['id'] ?? 0));
    $body = trim((string)($data['body'] ?? ''));
    if (!$commentId) {
        json_response(['error' => 'Missing comment_id'], 400);
    }
    if ($body === '') {
        json_response(['error' => 'Comment body required'], 400);
    }
    if (strlen($body) > 5000) {
        json_response(['error' => 'Comment too long (max 5000 characters)'], 400);
    }
    $existing = fetch_comment($pdo, $commentId);
    if (!$existing) {
        json_response(['error' => 'Comment not found'], 404);
    }
    if ((int)$existing['user_id'] !== (int)$user['id']) {
        json_response(['error' => 'Forbidden'], 403);
    }
    $stmt = $pdo->prepare("UPDATE episode_comments SET body = ?, edited_at = NOW() WHERE id = ?");
    $stmt->execute([$body, $commentId]);
    $updated = fetch_comment($pdo, $commentId);
    json_response(['comment' => $updated, 'ok' => true]);
}

if ($method === 'DELETE') {
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $commentId = (int)($data['comment_id'] ?? ($_GET['id'] ?? 0));
    if (!$commentId) {
        json_response(['error' => 'Missing comment_id'], 400);
    }
    $existing = fetch_comment($pdo, $commentId);
    if (!$existing) {
        json_response(['error' => 'Comment not found'], 404);
    }
    $isOwner = (int)$existing['user_id'] === (int)$user['id'];
    $isAdmin = ($user['role'] ?? '') === 'admin';
    if (!$isOwner && !$isAdmin) {
        json_response(['error' => 'Forbidden'], 403);
    }
    $stmt = $pdo->prepare("DELETE FROM episode_comments WHERE id = ?");
    $stmt->execute([$commentId]);
    json_response(['ok' => true]);
}

json_response(['error' => 'Method not allowed'], 405);
