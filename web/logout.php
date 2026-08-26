<?php
require_once __DIR__ . '/api/config.php';
$_SESSION = [];
if (ini_get('session.use_cookies')) {
    $params = session_get_cookie_params();
    setcookie(session_name(), '', time() - 42000, $params['path'], $params['domain'], $params['secure'], $params['httponly']);
}
session_destroy();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Logging out…</title>
    <meta http-equiv="refresh" content="0;url=login.php">
</head>
<body>
<script>
(() => {
    const keys = [
        'micahpod_player_state',
        'micahpod_queue',
        'micahpod_queue_meta',
        'micahpod_player_minimized',
    ];
    keys.forEach((key) => {
        try { localStorage.removeItem(key); } catch (e) {}
        try { sessionStorage.removeItem(key); } catch (e) {}
    });
    window.location.replace('login.php');
})();
</script>
<p>Logging out… <a href="login.php">Continue</a></p>
</body>
</html>
