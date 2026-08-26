<?php
require_once __DIR__ . '/config.php';

$query = trim($_GET['q'] ?? '');
if ($query === '') {
    json_response(['results' => []]);
}

$baseUrl = env('ITUNES_SEARCH_URL', 'https://itunes.apple.com/search');
$url = $baseUrl . '?media=podcast&term=' . urlencode($query) . '&limit=20';

$ch = curl_init($url);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
$resp = curl_exec($ch);
$code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($code !== 200 || !$resp) {
    json_response(['error' => 'Search failed'], 500);
}

$data = json_decode($resp, true);
json_response(['results' => $data['results'] ?? []]);
