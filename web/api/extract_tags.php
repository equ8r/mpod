<?php
require_once __DIR__ . '/config.php';

function normalize_guest_name(string $name): string {
    $name = preg_replace('/[\r\n\t]+/', ' ', $name);
    // Strip "Part I/II/III/IV/V/VI" or "Part 1/2/3" or "Pt. 1/2/3" (and everything after)
    $name = preg_replace('/\s*\b(?:Part|Pt\.?)\s*(?:[IVXivx]+|\d+)\b.*$/i', '', $name);
    // Strip content after sentence-ending periods (but not initials like "J." or "Jr.")
    $name = preg_replace('/(?<=[a-z]{2})\.\s+.*$/i', '', $name);
    $name = preg_replace('/\s{2,}/', ' ', $name);
    $name = trim($name, " \t\n\r\0\x0B-–—:;,.");
    return $name;
}

function guest_name_matches_author(string $name, ?string $author): bool {
    if (!$author) {
        return false;
    }
    $cleanName = strtolower(preg_replace('/[^a-z0-9 ]+/', '', $name));
    $cleanAuthor = strtolower(preg_replace('/[^a-z0-9 ]+/', '', $author));
    if ($cleanName === '' || $cleanAuthor === '') {
        return false;
    }
    return strpos($cleanAuthor, $cleanName) !== false || strpos($cleanName, $cleanAuthor) !== false;
}

function is_valid_guest_name(string $name, ?string $author): bool {
    $name = normalize_guest_name($name);
    if ($name === '' || guest_name_matches_author($name, $author)) {
        return false;
    }
    $words = preg_split('/\s+/', $name);
    if (!$words || count($words) < 2 || count($words) > 4) {
        return false;
    }
    // Reject entire name if it still contains Part/Pt/Sponsors/Episode fragments
    if (preg_match('/\b(?:Part|Pt|Sponsors?|Episode|Season|Ep)\b/i', $name)) {
        return false;
    }
    $stopwords = ['the', 'a', 'an', 'is', 'and', 'or', 'of', 'to', 'in', 'on', 'for', 'with', 'featuring', 'guest', 'episode', 'part', 'season', 'sponsors', 'presents'];
    foreach ($words as $word) {
        $cleanWord = strtolower(trim($word, ".-"));
        if ($cleanWord === '' || in_array($cleanWord, $stopwords, true)) {
            return false;
        }
        if (!preg_match('/^[A-Z][A-Za-z\'\.\-]*$/', $word)) {
            return false;
        }
    }
    return true;
}

function split_guest_candidates(string $candidate): array {
    $candidate = preg_replace('/\s*(?:-|\||\(|\[).*$/', '', $candidate);
    $candidate = preg_replace('/\s+on\s+.+$/i', '', $candidate);
    $candidate = normalize_guest_name($candidate);
    if ($candidate === '') {
        return [];
    }
    $parts = preg_split('/\s*(?:,|&|\band\b)\s*/i', $candidate);
    $names = [];
    foreach ($parts as $part) {
        $part = normalize_guest_name($part);
        if ($part !== '') {
            $names[] = $part;
        }
    }
    return $names;
}

function extract_guests_from_text(string $text, string $source, ?string $author): array {
    $guests = [];
    $patterns = [
        '/\bwith\s+([A-Z][A-Za-z\'\.\- ]{2,80})/i',
        '/\bfeaturing\s+([A-Z][A-Za-z\'\.\- ]{2,80})/i',
        '/\bft\.?\s+([A-Z][A-Za-z\'\.\- ]{2,80})/i',
        '/\bguest:\s*([A-Z][A-Za-z\'\.\- ]{2,80})/i',
    ];
    foreach ($patterns as $pattern) {
        if (preg_match_all($pattern, $text, $matches)) {
            foreach ($matches[1] as $candidate) {
                foreach (split_guest_candidates($candidate) as $name) {
                    if (is_valid_guest_name($name, $author)) {
                        $confidence = ($source === 'title') ? 0.9 : 0.85;
                        if (!isset($guests[$name]) || $confidence > $guests[$name]) {
                            $guests[$name] = $confidence;
                        }
                    }
                }
            }
        }
    }
    return $guests;
}

function extract_guest_from_title_patterns(string $title, ?string $author): array {
    $guests = [];
    $separatorPatterns = [' - ', ' | '];
    foreach ($separatorPatterns as $sep) {
        if (strpos($title, $sep) !== false) {
            $parts = explode($sep, $title);
            $candidate = trim(end($parts));
            foreach (split_guest_candidates($candidate) as $name) {
                if (is_valid_guest_name($name, $author)) {
                    $guests[$name] = 0.9;
                }
            }
        }
    }
    if (preg_match('/^([A-Z][A-Za-z\'\.\- ]+)\s+on\s+/i', $title, $matches)) {
        foreach (split_guest_candidates($matches[1]) as $name) {
            if (is_valid_guest_name($name, $author)) {
                $guests[$name] = 0.9;
            }
        }
    }
    return $guests;
}

function normalize_for_comparison(string $name): string {
    $name = strtolower($name);
    // Strip common titles/prefixes
    $name = preg_replace('/\b(?:president|senator|rep|representative|governor|dr|mr|mrs|ms|prof|judge|gen|col|sgt|rev|sr|jr|st|lord|sir|dame|hon|rt)\b\.?/i', '', $name);
    // Keep only alpha chars for comparison
    $name = preg_replace('/[^a-z]/', '', $name);
    return $name;
}

function find_existing_tag(string $name, string $type, array &$existingByType): ?int {
    $norm = normalize_for_comparison($name);
    if ($norm === '' || !isset($existingByType[$type])) {
        return null;
    }
    foreach ($existingByType[$type] as $existing) {
        // Exact normalized match
        if ($existing['norm'] === $norm) {
            return $existing['id'];
        }
        // One is a substring of the other (handles "Zelensky" vs "Zelenskyy" or "Volodymyr Zelenskyy")
        $shorter = strlen($norm) < strlen($existing['norm']) ? $norm : $existing['norm'];
        $longer = strlen($norm) >= strlen($existing['norm']) ? $norm : $existing['norm'];
        if (strlen($shorter) >= 5 && strpos($longer, $shorter) !== false) {
            return $existing['id'];
        }
        // Levenshtein for close matches (typos, transliterations)
        if (strlen($norm) >= 5 && strlen($existing['norm']) >= 5) {
            $distance = levenshtein($norm, $existing['norm']);
            $maxLen = max(strlen($norm), strlen($existing['norm']));
            if ($distance <= 2 && $distance / $maxLen < 0.2) {
                return $existing['id'];
            }
        }
    }
    return null;
}

function keyword_matches(string $text, string $keyword): bool {
    if ($keyword === '') {
        return false;
    }
    if (strpos($keyword, ' ') !== false) {
        return stripos($text, $keyword) !== false;
    }
    return (bool)preg_match('/\b' . preg_quote($keyword, '/') . '\b/i', $text);
}

function extract_topic_tags(string $title, string $description): array {
    $topicClusters = [
        'politics' => ['politics', 'government', 'election', 'policy', 'immigration', 'foreign policy', 'freedom', 'liberty', 'constitution', 'taxes'],
        'economics' => ['economics', 'inflation', 'market', 'markets', 'stocks', 'investing'],
        'faith' => ['faith', 'religion', 'church', 'christian', 'bible', 'theology'],
        'culture' => ['culture', 'media', 'entertainment', 'comedy'],
        'technology' => ['technology', 'tech', 'software', 'ai', 'artificial intelligence'],
        'health' => ['health', 'medicine', 'wellness'],
        'education' => ['education', 'school', 'learning', 'homeschool'],
        'parenting' => ['parenting', 'family', 'kids', 'children'],
        'war' => ['war', 'military', 'defense', 'conflict'],
        'bitcoin' => ['bitcoin'],
        'crypto' => ['crypto', 'cryptocurrency', 'blockchain'],
        'gold' => ['gold', 'precious metals'],
        'silver' => ['silver'],
    ];

    $topics = [];
    foreach ($topicClusters as $topic => $keywords) {
        $titleMatches = [];
        $descMatches = [];
        foreach ($keywords as $keyword) {
            if (keyword_matches($title, $keyword)) {
                $titleMatches[] = $keyword;
            }
            if (keyword_matches($description, $keyword)) {
                $descMatches[] = $keyword;
            }
        }
        $distinct = array_unique(array_merge($titleMatches, $descMatches));
        $distinctCount = count($distinct);
        if ($distinctCount === 0) {
            continue;
        }
        if ($distinctCount >= 2) {
            $topics[$topic] = count($titleMatches) > 0 ? 0.9 : 0.7;
        } else {
            $topics[$topic] = 0.5;
        }
    }
    return $topics;
}

/**
 * Build boilerplate fingerprints per podcast.
 * Splits descriptions into sentences, finds sentences that appear in >30% of episodes.
 * Returns array keyed by podcast_id => [sentence1, sentence2, ...]
 */
function detect_boilerplate(PDO $pdo): array {
    $rows = $pdo->query(
        "SELECT e.podcast_id, e.description
         FROM episodes e
         JOIN podcasts p ON p.id = e.podcast_id
         WHERE p.active = 1 AND e.description IS NOT NULL AND e.description != ''"
    )->fetchAll();

    // Group by podcast
    $byPodcast = [];
    foreach ($rows as $row) {
        $pid = (int)$row['podcast_id'];
        $byPodcast[$pid][] = $row['description'];
    }

    $boilerplate = [];
    foreach ($byPodcast as $pid => $descriptions) {
        $totalEps = count($descriptions);
        if ($totalEps < 5) continue; // Need enough episodes to detect patterns

        // Extract sentences/phrases (split on periods, newlines)
        $sentenceCounts = [];
        foreach ($descriptions as $desc) {
            // Split into chunks (sentences or line breaks)
            $chunks = preg_split('/(?<=[.!?])\s+|\n+/', $desc);
            $seen = []; // Dedup within same episode
            foreach ($chunks as $chunk) {
                $chunk = trim($chunk);
                if (strlen($chunk) < 30) continue; // Skip short fragments
                $norm = strtolower(preg_replace('/\s+/', ' ', $chunk));
                if (isset($seen[$norm])) continue;
                $seen[$norm] = true;
                $sentenceCounts[$norm] = ($sentenceCounts[$norm] ?? 0) + 1;
            }
        }

        // Sentences appearing in >30% of episodes are boilerplate
        $threshold = $totalEps * 0.3;
        $boilerplate[$pid] = [];
        foreach ($sentenceCounts as $sentence => $count) {
            if ($count >= $threshold) {
                $boilerplate[$pid][] = $sentence;
            }
        }
    }

    return $boilerplate;
}

/**
 * Strip boilerplate sentences from a description.
 */
function strip_boilerplate(string $description, array $boilerplateSentences): string {
    if (empty($boilerplateSentences)) return $description;
    $descLower = strtolower(preg_replace('/\s+/', ' ', $description));
    foreach ($boilerplateSentences as $sentence) {
        $pos = strpos($descLower, $sentence);
        if ($pos !== false) {
            // Remove from the boilerplate sentence to the end of description
            // (boilerplate is usually at the end — promo/ad copy)
            $description = trim(substr($description, 0, $pos));
            $descLower = strtolower(preg_replace('/\s+/', ' ', $description));
        }
    }
    return $description;
}

function extract_tags_for_unlabeled_episodes(PDO $pdo): array {
    $episodes = $pdo->query(
        "SELECT e.id, e.podcast_id, e.title, e.description, p.author
         FROM episodes e
         JOIN podcasts p ON p.id = e.podcast_id
         LEFT JOIN episode_tags et ON et.episode_id = e.id
         WHERE et.id IS NULL"
    )->fetchAll();

    if (!$episodes) {
        return ['ok' => true, 'episodes_processed' => 0, 'tags_inserted' => 0, 'output' => 'No untagged episodes found.'];
    }

    // Detect boilerplate per podcast
    $boilerplate = detect_boilerplate($pdo);

    $blacklistRows = $pdo->query("SELECT tag_name, tag_type FROM tag_blacklist")->fetchAll();
    $blacklist = ['guest' => [], 'topic' => []];
    foreach ($blacklistRows as $row) {
        $type = $row['tag_type'] ?? '';
        $name = strtolower(trim((string)($row['tag_name'] ?? '')));
        if ($name !== '' && isset($blacklist[$type])) {
            $blacklist[$type][$name] = true;
        }
    }

    $tagStmt = $pdo->prepare(
        "INSERT INTO tags (name, type)
         VALUES (?, ?)
         ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)"
    );
    $episodeTagStmt = $pdo->prepare(
        "INSERT INTO episode_tags (episode_id, tag_id, source, confidence)
         VALUES (?, ?, 'ai', ?)
         ON DUPLICATE KEY UPDATE source = 'ai', confidence = GREATEST(confidence, VALUES(confidence))"
    );

    // Load existing tags for fuzzy dedup
    $existingTags = $pdo->query("SELECT id, name, type FROM tags")->fetchAll();
    $existingByType = ['guest' => [], 'topic' => []];
    foreach ($existingTags as $t) {
        $type = $t['type'] ?? '';
        if (isset($existingByType[$type])) {
            $existingByType[$type][] = ['id' => (int)$t['id'], 'name' => $t['name'], 'norm' => normalize_for_comparison($t['name'])];
        }
    }

    // Count episodes per podcast for frequency cap
    $podcastEpCounts = [];
    foreach ($episodes as $ep) {
        $pid = (int)$ep['podcast_id'];
        $podcastEpCounts[$pid] = ($podcastEpCounts[$pid] ?? 0) + 1;
    }

    // First pass: collect all candidate guest tags per podcast to detect frequency abuse
    $guestCandidatesByPodcast = []; // podcast_id => [name => [episode_ids]]
    $episodeGuestTags = []; // episode_id => [name => confidence]
    $episodeTopicTags = []; // episode_id => [name => confidence]
    $titleGuests = []; // episode_id => [name => true] - guests found in TITLE specifically

    $tagCache = [];
    $tagsInserted = 0;
    $episodesProcessed = 0;
    $lines = [];
    $skippedBoilerplate = 0;

    foreach ($episodes as $episode) {
        $episodesProcessed++;
        $title = trim((string)($episode['title'] ?? ''));
        $description = trim((string)($episode['description'] ?? ''));
        $author = $episode['author'] ?? null;
        $pid = (int)$episode['podcast_id'];
        $eid = (int)$episode['id'];

        // Strip boilerplate from description
        $cleanDesc = strip_boilerplate($description, $boilerplate[$pid] ?? []);

        // Extract guests from title (high confidence)
        $titleGuestTags = [];
        foreach (extract_guest_from_title_patterns($title, $author) as $name => $confidence) {
            $titleGuestTags[$name] = $confidence;
        }
        foreach (extract_guests_from_text($title, 'title', $author) as $name => $confidence) {
            if (!isset($titleGuestTags[$name]) || $confidence > $titleGuestTags[$name]) {
                $titleGuestTags[$name] = $confidence;
            }
        }

        // Extract guests from CLEANED description
        $descGuestTags = [];
        foreach (extract_guests_from_text($cleanDesc, 'description', $author) as $name => $confidence) {
            $descGuestTags[$name] = $confidence;
        }

        // Merge: title guests get priority
        $guestTags = $titleGuestTags;
        foreach ($descGuestTags as $name => $confidence) {
            if (!isset($guestTags[$name]) || $confidence > $guestTags[$name]) {
                $guestTags[$name] = $confidence;
            }
        }

        // Track which guests came from titles
        $titleGuests[$eid] = [];
        foreach ($titleGuestTags as $name => $conf) {
            $titleGuests[$eid][strtolower($name)] = true;
        }

        // Collect for frequency analysis
        foreach ($guestTags as $name => $conf) {
            $guestCandidatesByPodcast[$pid][$name][] = $eid;
        }

        $episodeGuestTags[$eid] = $guestTags;
        $episodeTopicTags[$eid] = extract_topic_tags($title, $cleanDesc);
    }

    // Second pass: apply frequency cap and insert
    // Get total episode counts per podcast (all episodes, not just untagged)
    $allPodcastCounts = [];
    $countRows = $pdo->query("SELECT podcast_id, COUNT(*) as cnt FROM episodes GROUP BY podcast_id")->fetchAll();
    foreach ($countRows as $cr) {
        $allPodcastCounts[(int)$cr['podcast_id']] = (int)$cr['cnt'];
    }

    foreach ($episodes as $episode) {
        $eid = (int)$episode['id'];
        $pid = (int)$episode['podcast_id'];
        $guestTags = $episodeGuestTags[$eid] ?? [];
        $topicTags = $episodeTopicTags[$eid] ?? [];
        $totalForPodcast = $allPodcastCounts[$pid] ?? 1;

        $episodeTagCount = 0;

        foreach ($guestTags as $name => $confidence) {
            if (isset($blacklist['guest'][strtolower($name)])) {
                continue;
            }

            // Frequency cap: if this guest appears in >30% of this podcast's episodes,
            // only keep it if it was found in the TITLE of THIS episode
            $candidateCount = count($guestCandidatesByPodcast[$pid][$name] ?? []);
            $freqRatio = $candidateCount / max($totalForPodcast, 1);
            if ($freqRatio > 0.3 && !isset($titleGuests[$eid][strtolower($name)])) {
                $skippedBoilerplate++;
                continue;
            }

            $cacheKey = "guest:{$name}";
            if (!isset($tagCache[$cacheKey])) {
                $existingId = find_existing_tag($name, 'guest', $existingByType);
                if ($existingId !== null) {
                    $tagCache[$cacheKey] = $existingId;
                } else {
                    $tagStmt->execute([$name, 'guest']);
                    $newId = (int)$pdo->lastInsertId();
                    $tagCache[$cacheKey] = $newId;
                    $existingByType['guest'][] = ['id' => $newId, 'name' => $name, 'norm' => normalize_for_comparison($name)];
                }
            }
            $episodeTagStmt->execute([$eid, $tagCache[$cacheKey], $confidence]);
            $tagsInserted++;
            $episodeTagCount++;
        }

        foreach ($topicTags as $name => $confidence) {
            if (isset($blacklist['topic'][strtolower($name)])) {
                continue;
            }
            $cacheKey = "topic:{$name}";
            if (!isset($tagCache[$cacheKey])) {
                $existingId = find_existing_tag($name, 'topic', $existingByType);
                if ($existingId !== null) {
                    $tagCache[$cacheKey] = $existingId;
                } else {
                    $tagStmt->execute([$name, 'topic']);
                    $newId = (int)$pdo->lastInsertId();
                    $tagCache[$cacheKey] = $newId;
                    $existingByType['topic'][] = ['id' => $newId, 'name' => $name, 'norm' => normalize_for_comparison($name)];
                }
            }
            $episodeTagStmt->execute([$eid, $tagCache[$cacheKey], $confidence]);
            $tagsInserted++;
            $episodeTagCount++;
        }

        if ($episodeTagCount > 0) {
            $lines[] = "Episode {$episode['id']}: tagged {$episodeTagCount}";
        }
    }

    if ($skippedBoilerplate > 0) {
        array_unshift($lines, "⚠️ Skipped {$skippedBoilerplate} boilerplate guest tags (high-frequency per podcast, not in title)");
    }

    $output = $lines ? implode("\n", $lines) : 'No tags extracted.';
    return [
        'ok' => true,
        'episodes_processed' => $episodesProcessed,
        'tags_inserted' => $tagsInserted,
        'boilerplate_skipped' => $skippedBoilerplate,
        'output' => $output,
    ];
}

if (PHP_SAPI !== 'cli' && realpath(__FILE__) === realpath($_SERVER['SCRIPT_FILENAME'] ?? '')) {
    $pdo = db();
    require_method('POST');
    require_admin();
    verify_csrf();
    $result = extract_tags_for_unlabeled_episodes($pdo);
    json_response($result);
}
