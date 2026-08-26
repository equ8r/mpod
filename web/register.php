<?php
require_once __DIR__ . '/api/config.php';
$csrf = csrf_token();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="csrf-token" content="<?= htmlspecialchars($csrf) ?>">
    <title>Register - MicahPod</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body class="auth-page">
    <div class="auth-card">
        <h1>Create account</h1>
        <form id="register-form">
            <label>Username</label>
            <input type="text" name="username" required>
            <label>Email</label>
            <input type="email" name="email" required>
            <label>Password</label>
            <input type="password" name="password" required minlength="8">
            <label>Confirm password</label>
            <input type="password" name="confirm" required minlength="8">
            <button class="btn btn-primary" type="submit">Register</button>
        </form>
        <div class="auth-links">
            <a href="login.php">Already have an account?</a>
        </div>
        <div id="auth-message" class="auth-message"></div>
    </div>
    <script src="js/auth.js"></script>
</body>
</html>
