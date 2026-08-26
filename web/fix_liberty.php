<?php
require_once __DIR__ . '/api/config.php';
$pdo = db();
header('Content-Type: text/plain');

// Update feed URL
$pdo->prepare("UPDATE podcasts SET feed_url = ? WHERE title = 'Liberty and Finance'")
    ->execute(['https://feeds.soundcloud.com/users/soundcloud:users:985100308/sounds.rss']);
echo "Updated feed URL\n";

// Fetch episodes
$show = $pdo->query("SELECT id, title, feed_url FROM podcasts WHERE title = 'Liberty and Finance'")->fetch();
if (!$show) { echo "Show not found!\n"; exit; }

echo "Fetching: {$show['feed_url']}...\n";
$xml = @file_get_contents($show['feed_url']);
if (!$xml) { echo "FAILED to fetch feed\n"; exit; }

libxml_use_internal_errors(true);
$feed = simplexml_load_string($xml);
if (!$feed) { echo "FAILED to parse XML\n"; exit; }

$channel = $feed->channel;
$itunes_ns = $channel->children('http://www.itunes.com/dtds/podcast-1.0.dtd');
$art = (string)($itunes_ns->image['href'] ?? '');
if ($art) {
    $pdo->prepare("UPDATE podcasts SET artwork_url = ? WHERE id = ?")->execute([$art, $show['id']]);
    echo "Updated artwork\n";
}

$epStmt = $pdo->prepare(
    "INSERT IGNORE INTO episodes (podcast_id, guid, title, description, description_html, pub_date, duration_seconds, audio_url, artwork_url)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
);

$count = 0;
foreach ($channel->item as $item) {
    $itunes_ep = $item->children('http://www.itunes.com/dtds/podcast-1.0.dtd');
    $guid = (string)($item->guid ?? $item->link ?? '');
    $title = (string)($item->title ?? 'Untitled');
    $descHtml = (string)($item->children('content', true)->encoded ?? $item->description ?? '');
    $descText = strip_tags(html_entity_decode($descHtml));
    $pubDate = (string)($item->pubDate ?? '');
    $pubDt = $pubDate ? date('Y-m-d H:i:s', strtotime($pubDate)) : date('Y-m-d H:i:s');
    $dur = (string)($itunes_ep->duration ?? '');
    $durSec = null;
    if ($dur) {
        $parts = explode(':', $dur);
        if (count($parts) === 3) $durSec = (int)$parts[0]*3600 + (int)$parts[1]*60 + (int)$parts[2];
        elseif (count($parts) === 2) $durSec = (int)$parts[0]*60 + (int)$parts[1];
        else $durSec = (int)$dur;
    }
    $enclosure = $item->enclosure;
    $audioUrl = $enclosure ? (string)$enclosure['url'] : null;
    $epArt = (string)($itunes_ep->image['href'] ?? '');

    $epStmt->execute([$show['id'], $guid, $title, $descText, $descHtml, $pubDt, $durSec, $audioUrl, $epArt ?: null]);
    $count++;
}

$pdo->prepare("UPDATE podcasts SET last_checked = NOW(), last_new_episode = NOW() WHERE id = ?")->execute([$show['id']]);
echo "Loaded $count episodes\n";
echo "\n⚠️ DELETE THIS FILE\n";
