<?php
require_once __DIR__ . '/api/config.php';

$token = $_GET['token'] ?? '';
$message = 'Missing verification token.';
$success = false;

if ($token !== '') {
    $url = base_url() . "/users.php?action=verify&token=" . urlencode($token);
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    $resp = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($code === 200) {
        $success = true;
        $message = 'Email verified. You can now log in.';
    } else {
        $message = 'Verification failed or token expired.';
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Email Verification - MicahPod</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body class="auth-page">
    <div class="auth-card">
        <h1>Email verification</h1>
        <p class="<?= $success ? 'success' : 'error' ?>"><?= htmlspecialchars($message) ?></p>
        <a class="btn btn-primary" href="login.php">Go to login</a>
    </div>
</body>
</html>
