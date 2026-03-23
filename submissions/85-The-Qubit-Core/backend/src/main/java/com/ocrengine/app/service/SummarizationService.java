package com.ocrengine.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Extractive summarization — handles Category 6 edge cases:
 * #29 — Very short doc: don't pad, return as-is
 * #30 — Very long doc: cap to one concise paragraph
 * #31 — Repetitive doc: deduplicate near-identical sentences
 * #32 — No meaningful content: return "No extractable content found"
 * #33 — Contradictory clauses: detect and flag them
 */
@Service
public class SummarizationService {

    private static final Logger log = LoggerFactory.getLogger(SummarizationService.class);

    private static final int MIN_WORDS_FOR_SUMMARY = 30;
    private static final int MAX_SUMMARY_SENTENCES = 5;
    private static final double SIMILARITY_THRESHOLD = 0.75; // for deduplication

    private static final Set<String> STOP_WORDS = Set.of(
        "a","an","the","is","it","its","in","on","at","to","for","of","and","or",
        "but","not","with","this","that","these","those","are","was","were","be",
        "been","being","have","has","had","do","does","did","will","would","could",
        "should","may","might","shall","by","from","as","if","then","than","so",
        "such","each","both","all","any","more","most","also","into","about","after",
        "before","between","through","during","above","below","up","down","out","off"
    );

    private static final List<String> LEGAL_KEYWORDS = List.of(
        "agreement","contract","party","parties","obligation","payment","amount",
        "total","invoice","date","term","condition","clause","whereas","hereto",
        "thereof","hereby","penalty","liability","confidential","terminate","renewal",
        "notice","signatory","execute","effective","warranty","indemnify","govern",
        "law","jurisdiction","dispute","arbitration","breach","remedy","damages"
    );

    // Edge Case #33 — Contradiction detection patterns
    private static final List<String[]> CONTRADICTION_PAIRS = List.of(
        new String[]{"30\\s+days", "60\\s+days"},
        new String[]{"30\\s+days", "90\\s+days"},
        new String[]{"60\\s+days", "90\\s+days"},
        new String[]{"payment\\s+due\\s+in\\s+\\d+", "payment\\s+due\\s+in\\s+\\d+"},
        new String[]{"non[-\\s]?refundable", "refundable"},
        new String[]{"non[-\\s]?transferable", "transferable"},
        new String[]{"exclusive", "non[-\\s]?exclusive"},
        new String[]{"terminat[a-z]+\\s+with\\s+\\d+\\s+days", "terminat[a-z]+\\s+with\\s+\\d+\\s+days"}
    );

    public SummarizationResult summarize(String text) {
        if (text == null || text.isBlank()) {
            // Edge Case #32
            return SummarizationResult.builder()
                    .summary("No extractable content found in this document.")
                    .contradictions(List.of())
                    .isEmpty(true)
                    .build();
        }

        String cleaned = cleanText(text);
        String[] words = cleaned.split("\\s+");
        int wordCount = (int) Arrays.stream(words).filter(w -> !w.isBlank()).count();

        // Edge Case #32 — blank or near-blank page
        if (wordCount < MIN_WORDS_FOR_SUMMARY) {
            String shortContent = cleaned.trim();
            if (shortContent.length() < 50) {
                return SummarizationResult.builder()
                        .summary("No meaningful content found. The document may be a cover sheet, blank page, or contain only a logo/watermark.")
                        .contradictions(List.of())
                        .isEmpty(true)
                        .build();
            }
            // Edge Case #29 — very short doc, return as-is without padding
            return SummarizationResult.builder()
                    .summary(shortContent)
                    .contradictions(List.of())
                    .isEmpty(false)
                    .build();
        }

        String[] sentences = splitIntoSentences(cleaned);

        // Edge Case #31 — deduplicate near-identical sentences
        sentences = deduplicateSentences(sentences);

        // Edge Case #30 — cap to MAX_SUMMARY_SENTENCES for very long docs
        String summary;
        if (sentences.length <= MAX_SUMMARY_SENTENCES) {
            summary = String.join(" ", sentences);
        } else {
            summary = buildExtractedSummary(sentences, MAX_SUMMARY_SENTENCES);
        }

        // Edge Case #33 — detect contradictions
        List<String> contradictions = detectContradictions(text);

        if (!contradictions.isEmpty()) {
            summary += "\n\n⚠️ POTENTIAL CONTRADICTIONS DETECTED: " +
                    String.join("; ", contradictions);
        }

        log.info("Summary: {} sentences, {} contradictions", sentences.length, contradictions.size());

        return SummarizationResult.builder()
                .summary(summary.trim())
                .contradictions(contradictions)
                .isEmpty(false)
                .build();
    }

    // ── Sentence splitting ───────────────────────────────────────────────────
    private String[] splitIntoSentences(String text) {
        String prepared = text
                .replaceAll("---\\s*Page Break\\s*---", " ")
                .replaceAll("\\[Page \\d+: Could not be processed\\]", "")
                .replaceAll("\\s{3,}", " ")
                .replaceAll("[\\r\\n]+", " ");

        return Arrays.stream(prepared.split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(s -> s.length() > 20 && s.split("\\s+").length >= 4)
                .toArray(String[]::new);
    }

    // ── Edge Case #31 — Deduplicate near-identical sentences ────────────────
    private String[] deduplicateSentences(String[] sentences) {
        List<String> unique = new ArrayList<>();
        for (String s : sentences) {
            boolean isDuplicate = unique.stream().anyMatch(
                existing -> jaccardSimilarity(s, existing) >= SIMILARITY_THRESHOLD
            );
            if (!isDuplicate) unique.add(s);
        }
        if (unique.size() < sentences.length) {
            log.info("Deduplication removed {} redundant sentences",
                    sentences.length - unique.size());
        }
        return unique.toArray(new String[0]);
    }

    // ── Jaccard similarity for deduplication ─────────────────────────────────
    private double jaccardSimilarity(String a, String b) {
        Set<String> setA = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\W+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\W+")));
        setA.removeAll(STOP_WORDS);
        setB.removeAll(STOP_WORDS);
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    // ── TextRank-inspired sentence scoring ──────────────────────────────────
    private String buildExtractedSummary(String[] sentences, int maxSentences) {
        Map<String, Integer> tf = buildTermFrequency(String.join(" ", sentences));
        Map<Integer, Double> scores = new LinkedHashMap<>();

        for (int i = 0; i < sentences.length; i++) {
            scores.put(i, scoreSentence(sentences[i], i, sentences.length, tf));
        }

        List<Integer> topIdx = scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(maxSentences)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (int idx : topIdx) {
            String s = sentences[idx].trim();
            if (!s.isBlank() && s.length() > 20) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(s);
                if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) sb.append(".");
            }
        }
        return sb.toString().trim();
    }

    private double scoreSentence(String s, int pos, int total, Map<String, Integer> tf) {
        double score = 0;
        String[] words = s.toLowerCase().split("\\W+");
        for (String w : words) {
            if (!STOP_WORDS.contains(w) && tf.containsKey(w)) score += tf.get(w);
        }
        if (words.length > 0) score /= words.length;

        double posRatio = (double) pos / total;
        if (posRatio < 0.2) score *= 1.4;
        else if (posRatio > 0.9) score *= 1.2;

        String lower = s.toLowerCase();
        for (String kw : LEGAL_KEYWORDS) {
            if (lower.contains(kw)) score += 2.0;
        }
        if (words.length < 6)  score *= 0.7;
        if (words.length > 50) score *= 0.8;
        return score;
    }

    private Map<String, Integer> buildTermFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String word : text.toLowerCase().split("\\W+")) {
            if (word.length() > 2 && !STOP_WORDS.contains(word)) {
                tf.merge(word, 1, Integer::sum);
            }
        }
        return tf;
    }

    // ── Edge Case #33 — Contradiction detection ──────────────────────────────
    private List<String> detectContradictions(String text) {
        List<String> found = new ArrayList<>();
        String lower = text.toLowerCase();

        // Payment terms contradiction
        List<String> paymentTerms = new ArrayList<>();
        Matcher pm = Pattern.compile("(?:payment\\s+due|due\\s+in|within|pay\\s+within)" +
                "\\s+(\\d+)\\s+days", Pattern.CASE_INSENSITIVE).matcher(text);
        while (pm.find()) paymentTerms.add(pm.group(1));

        Set<String> uniqueTerms = new HashSet<>(paymentTerms);
        if (uniqueTerms.size() > 1) {
            found.add("Conflicting payment terms found: " +
                    String.join(" vs ", uniqueTerms.stream()
                            .map(t -> t + " days").collect(Collectors.toList())));
        }

        // Refundable contradiction
        if (lower.contains("non-refundable") && lower.contains("refundable")
                && !lower.contains("non-refundable and refundable")) {
            found.add("Document contains both 'refundable' and 'non-refundable' clauses");
        }

        // Exclusive / non-exclusive
        if (lower.contains("exclusive license") && lower.contains("non-exclusive")) {
            found.add("Document contains both 'exclusive' and 'non-exclusive' license terms");
        }

        // Termination notice period
        List<String> noticePeriods = new ArrayList<>();
        Matcher nm = Pattern.compile("(?:terminat[a-z]+|notice)\\s+(?:with|of|period\\s+of)?" +
                "\\s*(\\d+)\\s+(?:days|months)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (nm.find()) noticePeriods.add(nm.group(1));
        Set<String> uniqueNotice = new HashSet<>(noticePeriods);
        if (uniqueNotice.size() > 1) {
            found.add("Conflicting termination/notice periods: " +
                    String.join(" vs ", uniqueNotice.stream()
                            .map(t -> t + " days/months").collect(Collectors.toList())));
        }

        return found;
    }

    private String cleanText(String text) {
        return text
                .replaceAll("---\\s*Page Break\\s*---", "\n")
                .replaceAll("\\[Page \\d+: Could not be processed\\]", "")
                .replaceAll("\\s{3,}", " ")
                .trim();
    }

    // ── Result DTO ───────────────────────────────────────────────────────────
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SummarizationResult {
        private String summary;
        private List<String> contradictions;
        private boolean isEmpty;
    }
}
