<?php
/**
 * Seed starter podcasts. Hit once, then delete.
 */
require_once __DIR__ . '/api/config.php';
$pdo = db();
header('Content-Type: text/plain');

$podcasts = [];

// Search iTunes for 4 shows
$itunes = [
    'Tucker Carlson Show',
    'The Briefing with Albert Mohler',
    'Competent Man Podcast',
    'Club Random with Bill Maher',
];

foreach ($itunes as $query) {
    $url = 'https://itunes.apple.com/search?' . http_build_query([
        'term' => $query, 'media' => 'podcast', 'limit' => 3
    ]);
    $json = @file_get_contents($url);
    if (!$json) { echo "FAILED to search iTunes for: $query\n"; continue; }
    $data = json_decode($json, true);
    $results = $data['results'] ?? [];
    if (empty($results)) { echo "NOT FOUND: $query\n"; continue; }
    $best = $results[0];
    echo "Found: {$best['collectionName']} (iTunes ID: {$best['collectionId']})\n";
    echo "  Feed: {$best['feedUrl']}\n";
    $podcasts[] = [
        'title' => $best['collectionName'],
        'feed_url' => $best['feedUrl'] ?? null,
        'artwork_url' => $best['artworkUrl600'] ?? null,
        'author' => $best['artistName'] ?? null,
        'itunes_id' => $best['collectionId'] ?? null,
        'website_url' => $best['collectionViewUrl'] ?? null,
        'category' => $best['primaryGenreName'] ?? null,
    ];
    usleep(300000);
}

// Liberty and Finance (SoundCloud RSS)
$podcasts[] = [
    'title' => 'Liberty and Finance',
    'feed_url' => 'https://feeds.soundcloud.com/users/soundcloud:users:233578683/sounds.rss',
    'artwork_url' => null,
    'author' => 'Liberty and Finance',
    'itunes_id' => null,
    'website_url' => 'https://soundcloud.com/libertyandfinance',
    'category' => 'News',
];
echo "Added manually: Liberty and Finance (SoundCloud RSS)\n";

// Insert into DB
echo "\n--- Inserting podcasts ---\n";
$stmt = $pdo->prepare(
    "INSERT IGNORE INTO podcasts (title, feed_url, artwork_url, author, itunes_id, website_url, category, added_by)
     VALUES (?, ?, ?, ?, ?, ?, ?, 1)"
);
foreach ($podcasts as $p) {
    $stmt->execute([
        $p['title'], $p['feed_url'], $p['artwork_url'], $p['author'],
        $p['itunes_id'], $p['website_url'], $p['category']
    ]);
    echo "  Inserted: {$p['title']}\n";
}

// Now fetch episodes from RSS feeds
echo "\n--- Fetching episodes from RSS feeds ---\n";
$shows = $pdo->query("SELECT id, title, feed_url FROM podcasts WHERE feed_url IS NOT NULL AND active = 1")->fetchAll();

$totalEps = 0;
foreach ($shows as $show) {
    echo "\nFetching: {$show['title']}...\n";
    $xml = @file_get_contents($show['feed_url']);
    if (!$xml) { echo "  FAILED to fetch feed\n"; continue; }

    libxml_use_internal_errors(true);
    $feed = simplexml_load_string($xml);
    if (!$feed) { echo "  FAILED to parse XML\n"; continue; }

    $channel = $feed->channel ?? $feed;
    $items = $channel->item ?? [];
    $count = 0;

    // Update podcast artwork from feed if missing
    if (empty($show['artwork_url'] ?? '')) {
        $itunes_ns = $channel->children('http://www.itunes.com/dtds/podcast-1.0.dtd');
        $art = (string)($itunes_ns->image['href'] ?? '');
        if ($art) {
            $pdo->prepare("UPDATE podcasts SET artwork_url = ? WHERE id = ?")->execute([$art, $show['id']]);
            echo "  Updated artwork from feed\n";
        }
    }

    $epStmt = $pdo->prepare(
        "INSERT IGNORE INTO episodes (podcast_id, guid, title, description, description_html, pub_date, duration_seconds, audio_url, artwork_url, episode_number, season_number)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    );

    foreach ($items as $item) {
        $itunes_ep = $item->children('http://www.itunes.com/dtds/podcast-1.0.dtd');

        $guid = (string)($item->guid ?? $item->link ?? '');
        $title = (string)($item->title ?? 'Untitled');
        $descHtml = (string)($item->children('content', true)->encoded ?? $item->description ?? '');
        $descText = strip_tags(html_entity_decode($descHtml));
        $pubDate = (string)($item->pubDate ?? '');
        $pubDt = $pubDate ? date('Y-m-d H:i:s', strtotime($pubDate)) : date('Y-m-d H:i:s');

        // Duration
        $dur = (string)($itunes_ep->duration ?? '');
        $durSec = null;
        if ($dur) {
            $parts = explode(':', $dur);
            if (count($parts) === 3) $durSec = (int)$parts[0]*3600 + (int)$parts[1]*60 + (int)$parts[2];
            elseif (count($parts) === 2) $durSec = (int)$parts[0]*60 + (int)$parts[1];
            else $durSec = (int)$dur;
        }

        // Audio URL
        $enclosure = $item->enclosure;
        $audioUrl = $enclosure ? (string)$enclosure['url'] : null;

        // Episode art
        $epArt = (string)($itunes_ep->image['href'] ?? '');

        // Episode/season numbers
        $epNum = (string)($itunes_ep->episode ?? '');
        $seasonNum = (string)($itunes_ep->season ?? '');

        $epStmt->execute([
            $show['id'], $guid, $title, $descText, $descHtml, $pubDt,
            $durSec, $audioUrl, $epArt ?: null,
            $epNum !== '' ? (int)$epNum : null,
            $seasonNum !== '' ? (int)$seasonNum : null,
        ]);
        $count++;
    }

    $pdo->prepare("UPDATE podcasts SET last_checked = NOW(), last_new_episode = NOW() WHERE id = ?")->execute([$show['id']]);
    echo "  Loaded $count episodes\n";
    $totalEps += $count;
}

echo "\n=== DONE ===\n";
echo "Podcasts: " . count($podcasts) . "\n";
echo "Episodes: $totalEps\n";
echo "\n⚠️  DELETE THIS FILE after seeding!\n";
