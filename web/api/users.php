<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$action = $_GET['action'] ?? '';
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'POST' && $action === 'register') {
    verify_csrf();
    rate_limit('register', 5, 600);
    $data = get_json_body();
    $username = trim($data['username'] ?? '');
    $email = trim($data['email'] ?? '');
    $password = $data['password'] ?? '';
    if ($username === '' || $email === '' || $password === '') {
        json_response(['error' => 'Missing fields'], 400);
    }
    $stmt = $pdo->prepare('SELECT id FROM users WHERE username = ? OR email = ?');
    $stmt->execute([$username, $email]);
    if ($stmt->fetch()) {
        json_response(['error' => 'Username or email already exists'], 400);
    }
    $hash = password_hash($password, PASSWORD_DEFAULT);
    $token = generate_token(32);
    $stmt = $pdo->prepare(
        "INSERT INTO users (username, email, password_hash, verification_token)
         VALUES (?, ?, ?, ?)"
    );
    $stmt->execute([$username, $email, $hash, $token]);

    $verifyUrl = base_url() . "/users.php?action=verify&token={$token}";
    $html = "<p>Welcome to MicahPod!</p><p>Please verify your email:</p><p><a href=\"{$verifyUrl}\">Verify Email</a></p>";
    smtp_send($email, 'Verify your MicahPod account', $html);

    json_response(['ok' => true]);
}

if ($method === 'POST' && $action === 'login') {
    verify_csrf();
    rate_limit('login', 10, 600);
    $data = get_json_body();
    $email = trim($data['email'] ?? '');
    $password = $data['password'] ?? '';
    $remember = !empty($data['remember']);
    if ($email === '' || $password === '') {
        json_response(['error' => 'Missing credentials'], 400);
    }
    $stmt = $pdo->prepare('SELECT * FROM users WHERE email = ?');
    $stmt->execute([$email]);
    $user = $stmt->fetch();
    if (!$user || !password_verify($password, $user['password_hash'])) {
        json_response(['error' => 'Invalid credentials'], 401);
    }
    if ((int)$user['email_verified'] !== 1) {
        json_response(['error' => 'Email not verified'], 403);
    }
    if ($remember) {
        setcookie(session_name(), session_id(), [
            'expires' => time() + (60 * 60 * 24 * 30),
            'path' => '/',
            'httponly' => true,
            'samesite' => 'Lax',
            'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off'),
        ]);
    }
    $_SESSION['user_id'] = (int)$user['id'];
    $pdo->prepare('UPDATE users SET last_login = NOW() WHERE id = ?')->execute([(int)$user['id']]);
    json_response(['ok' => true, 'user' => [
        'id' => (int)$user['id'],
        'username' => $user['username'],
        'email' => $user['email'],
        'role' => $user['role'],
    ]]);
}

if ($method === 'GET' && $action === 'verify') {
    $token = $_GET['token'] ?? '';
    if ($token === '') {
        json_response(['error' => 'Missing token'], 400);
    }
    $stmt = $pdo->prepare('SELECT id FROM users WHERE verification_token = ?');
    $stmt->execute([$token]);
    $user = $stmt->fetch();
    if (!$user) {
        json_response(['error' => 'Invalid token'], 400);
    }
    $stmt = $pdo->prepare('UPDATE users SET email_verified = 1, verification_token = NULL WHERE id = ?');
    $stmt->execute([(int)$user['id']]);
    json_response(['ok' => true]);
}

if ($method === 'POST' && $action === 'reset_request') {
    verify_csrf();
    rate_limit('reset_request', 5, 600);
    $data = get_json_body();
    $email = trim($data['email'] ?? '');
    if ($email === '') {
        json_response(['error' => 'Missing email'], 400);
    }
    $stmt = $pdo->prepare('SELECT id FROM users WHERE email = ?');
    $stmt->execute([$email]);
    $user = $stmt->fetch();
    if ($user) {
        $token = generate_token(32);
        $stmt = $pdo->prepare('UPDATE users SET reset_token = ?, reset_expires = DATE_ADD(NOW(), INTERVAL 1 HOUR) WHERE id = ?');
        $stmt->execute([$token, (int)$user['id']]);
        $resetUrl = site_url() . "/reset.php?token={$token}";
        $html = "<p>Reset your MicahPod password:</p><p><a href=\"{$resetUrl}\">Reset Password</a></p>";
        smtp_send($email, 'MicahPod password reset', $html);
    }
    json_response(['ok' => true]);
}

if ($method === 'POST' && $action === 'reset') {
    verify_csrf();
    $data = get_json_body();
    $token = $data['token'] ?? '';
    $password = $data['password'] ?? '';
    if ($token === '' || $password === '') {
        json_response(['error' => 'Missing token or password'], 400);
    }
    $stmt = $pdo->prepare('SELECT id FROM users WHERE reset_token = ? AND reset_expires > NOW()');
    $stmt->execute([$token]);
    $user = $stmt->fetch();
    if (!$user) {
        json_response(['error' => 'Invalid or expired token'], 400);
    }
    $hash = password_hash($password, PASSWORD_DEFAULT);
    $stmt = $pdo->prepare('UPDATE users SET password_hash = ?, reset_token = NULL, reset_expires = NULL WHERE id = ?');
    $stmt->execute([$hash, (int)$user['id']]);
    json_response(['ok' => true]);
}

if ($method === 'GET' && $action === 'me') {
    $user = current_user();
    if (!$user) {
        json_response(['user' => null]);
    }
    json_response(['user' => $user]);
}

if ($method === 'PUT' && $action === 'update_digest') {
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $enabled = isset($data['digest_enabled']) ? (int)$data['digest_enabled'] : 1;
    $times = trim($data['digest_times'] ?? '10,22');
    $stmt = $pdo->prepare('UPDATE users SET digest_enabled = ?, digest_times = ? WHERE id = ?');
    $stmt->execute([$enabled ? 1 : 0, $times, (int)$user['id']]);
    json_response(['ok' => true]);
}

if ($method === 'GET' && $action === 'list') {
    require_admin();
    $stmt = $pdo->query('SELECT id, username, email, role, email_verified, created_at, last_login FROM users ORDER BY created_at DESC');
    json_response(['users' => $stmt->fetchAll()]);
}

if ($method === 'PUT' && $action === 'update_role') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['id'] ?? 0);
    $role = $data['role'] ?? 'user';
    if (!$id || !in_array($role, ['user', 'admin'], true)) {
        json_response(['error' => 'Invalid user or role'], 400);
    }
    $stmt = $pdo->prepare('UPDATE users SET role = ? WHERE id = ?');
    $stmt->execute([$role, $id]);
    json_response(['ok' => true]);
}

if ($method === 'PUT' && $action === 'verify') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['id'] ?? 0);
    if (!$id) {
        json_response(['error' => 'Missing user id'], 400);
    }
    $stmt = $pdo->prepare('UPDATE users SET email_verified = 1, verification_token = NULL WHERE id = ?');
    $stmt->execute([$id]);
    json_response(['ok' => true]);
}

if ($method === 'PUT' && $action === 'resend_verification') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['id'] ?? 0);
    if (!$id) {
        json_response(['error' => 'Missing user id'], 400);
    }
    $stmt = $pdo->prepare('SELECT email, verification_token FROM users WHERE id = ? AND email_verified = 0');
    $stmt->execute([$id]);
    $user = $stmt->fetch();
    if (!$user) {
        json_response(['error' => 'User not found or already verified'], 400);
    }
    $token = $user['verification_token'] ?: generate_token();
    if (!$user['verification_token']) {
        $pdo->prepare('UPDATE users SET verification_token = ? WHERE id = ?')->execute([$token, $id]);
    }
    $verifyUrl = site_url() . "/api/users.php?action=verify&token={$token}";
    $html = "<p>Welcome to MicahPod!</p><p>Please verify your email:</p><p><a href=\"{$verifyUrl}\">Verify Email</a></p>";
    $sent = smtp_send($user['email'], 'Verify your MicahPod account', $html);
    json_response(['ok' => $sent, 'sent' => $sent]);
}

if ($method === 'DELETE' && $action === 'delete') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $stmt = $pdo->prepare('DELETE FROM users WHERE id = ?');
    $stmt->execute([$id]);
    json_response(['ok' => true]);
}

json_response(['error' => 'Invalid action'], 400);
