# MicahPod Deployment

## Quick Deploy

```
cd C:\Users\micah\Projects\micahpod
python deploy.py
```

This uploads the entire `web/` directory + `.env` to DreamHost via SFTP.

## What Gets Deployed

- All PHP files in `web/` (pages + API)
- JavaScript (`web/js/`)
- CSS (`web/css/`)
- Assets (`web/assets/`)
- `.env` file (one level above web root on server)

## Deploy Credentials

Stored in `.env`:
- Host: `iad1-shared-b7-37.dreamhost.com`
- User: `ftp_micah`
- Remote path: `/home/ftp_micah/micahyoung.us/micahpod`

## Running Migrations

Database migrations can't run from local machine (IP-restricted). Use the server:

1. Create `run_migration.php` in project root with the SQL
2. Upload: `python upload_migration.py`
3. Hit: `https://micahyoung.us/micahpod/run_migration.php`
4. Verify output
5. Remove from server (the upload script or a manual SFTP delete)

**Template for `run_migration.php`:**
```php
<?php
$root = dirname(__DIR__);
$lines = file($root . '/.env', FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
$env = [];
foreach ($lines as $line) {
    $line = trim($line);
    if ($line === '' || $line[0] === '#' || strpos($line, '=') === false) continue;
    [$k, $v] = explode('=', $line, 2);
    $env[trim($k)] = trim($v);
}
$pdo = new PDO(
    "mysql:host={$env['DB_HOST']};dbname={$env['DB_NAME']};charset=utf8mb4",
    $env['DB_USER'], $env['DB_PASS'],
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
);
try {
    $pdo->exec("YOUR SQL HERE");
    echo "Migration complete.";
} catch (Exception $e) {
    echo "Error: " . $e->getMessage();
}
```

## Post-Deploy Checklist

1. Verify site loads: https://micahyoung.us/micahpod/
2. Check browser console for JS/CSS errors
3. Test login
4. Verify episode feed loads
5. If DB migration was included, verify new features work
