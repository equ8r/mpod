<?php
require_once __DIR__ . '/api/config.php';
$csrf = csrf_token();
$token = $_GET['token'] ?? '';
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="csrf-token" content="<?= htmlspecialchars($csrf) ?>">
    <title>Reset Password - MicahPod</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body class="auth-page">
    <div class="auth-card">
        <h1>Reset password</h1>
        <?php if ($token): ?>
        <form id="reset-form" data-token="<?= htmlspecialchars($token) ?>">
            <label>New password</label>
            <input type="password" name="password" required minlength="8">
            <label>Confirm password</label>
            <input type="password" name="confirm" required minlength="8">
            <button class="btn btn-primary" type="submit">Set new password</button>
        </form>
        <?php else: ?>
        <form id="reset-request-form">
            <label>Email</label>
            <input type="email" name="email" required>
            <button class="btn btn-primary" type="submit">Send reset link</button>
        </form>
        <?php endif; ?>
        <div class="auth-links">
            <a href="login.php">Back to login</a>
        </div>
        <div id="auth-message" class="auth-message"></div>
    </div>
    <script src="js/auth.js"></script>
</body>
</html>
