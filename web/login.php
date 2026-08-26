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
    <title>Login - MicahPod</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body class="auth-page">
    <div class="auth-card">
        <h1>Welcome back</h1>
        <form id="login-form">
            <label>Email</label>
            <input type="email" name="email" required>
            <label>Password</label>
            <input type="password" name="password" required>
            <label class="toggle">
                <input type="checkbox" name="remember">
                <span>Remember me</span>
            </label>
            <button class="btn btn-primary" type="submit">Login</button>
        </form>
        <div class="auth-links">
            <a href="reset.php">Forgot password?</a>
            <a href="register.php">Create account</a>
        </div>
        <div id="auth-message" class="auth-message"></div>
    </div>
    <script src="js/auth.js"></script>
</body>
</html>
