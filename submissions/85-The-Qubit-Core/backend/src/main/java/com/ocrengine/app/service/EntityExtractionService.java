package com.ocrengine.app.service;

import com.ocrengine.app.model.ExtractedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

@Service
public class EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

    // ── Due Date ─────────────────────────────────────────────────────────────
    private static final Pattern DUE_DATE_PATTERN = Pattern.compile(
        "(?i)(?:due\\s+(?:date|by|on)|payment\\s+due|payable\\s+by|deadline|" +
        "expiry\\s+date|expiration\\s+date|last\\s+date|pay\\s+by)" +
        "\\s*[:\\-]?\\s*([\\w\\d\\s,./\\-]{4,50}?)(?=[\\n\\r,;.]|$)"
    );

    // ── Signing Date ─────────────────────────────────────────────────────────
    private static final Pattern SIGNING_DATE_PATTERN = Pattern.compile(
        "(?i)(?:dated?|made\\s+(?:this|on)|executed\\s+(?:on|this)|" +
        "signed\\s+(?:on|this)|effective\\s+(?:date|from)|commencement\\s+date|" +
        "agreement\\s+date)" +
        "\\s*[:\\-]?\\s*([\\w\\d\\s,./\\-]{4,50}?)(?=[\\n\\r,;.]|$)"
    );

    // ── Financial amounts — captures full phrase e.g. "$35 billion" ──────────
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?i)" +
        // Symbol prefix + number + optional scale word
        "(?:[\\$₹€£¥]\\s?[\\d,]+(?:\\.\\d{1,2})?(?:\\s*(?:billion|million|lakh|crore|thousand|mn|bn))?)" +
        "|(?:(?:USD|INR|EUR|GBP|CAD|AUD)\\s?[\\d,]+(?:\\.\\d{1,2})?(?:\\s*(?:billion|million|lakh|crore|thousand))?)" +
        // Number + scale word (e.g. "35 billion dollars")
        "|(?:[\\d,]+(?:\\.\\d{1,2})?\\s*(?:billion|million|lakh|crore|thousand)\\s*(?:dollars|rupees|USD|INR)?)" +
        // Indian comma format
        "|(?:[\\d]{1,2},[\\d]{2},[\\d]{3}(?:\\.\\d{1,2})?)"
    );

    // ── Amount in words ───────────────────────────────────────────────────────
    private static final Pattern AMOUNT_IN_WORDS_PATTERN = Pattern.compile(
        "(?i)(?:rupees?|dollars?|euros?|pounds?)" +
        "\\s+(?:[A-Za-z\\s]+?)?" +
        "(?:crore|lakh|lac|thousand|hundred|million|billion)" +
        "(?:\\s+[A-Za-z\\s]+?)?(?:\\s+only|\\.)?",
        Pattern.CASE_INSENSITIVE
    );

    // ── Deductions ────────────────────────────────────────────────────────────
    private static final Pattern DEDUCTION_PATTERN = Pattern.compile(
        "(?i)(?:less\\s*:|minus|deduct(?:ion|ed)?|TDS|tax\\s+deduct|withhold|rebate|discount)" +
        "\\s*[:\\-]?\\s*(?:[\\$₹€£]\\s?)?[\\d,]+(?:\\.\\d{1,2})?"
    );

    // ── Signatory — must follow keyword AND start with capital name ───────────
    private static final Pattern SIGNATORY_NAMED_PATTERN = Pattern.compile(
        "(?i)(?:signed\\s+by|signature\\s+of|authorized\\s+by|signatory\\s*:" +
        "|signee\\s*:|executed\\s+by|witnessed\\s+by)" +
        "\\s*[:\\-]?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})"
    );

    // ── Ambiguous signatory ───────────────────────────────────────────────────
    private static final Pattern SIGNATORY_AMBIGUOUS_PATTERN = Pattern.compile(
        "(?i)(?:signed\\s+by|authorized\\s+(?:representative|signatory|person)|" +
        "duly\\s+authorized|competent\\s+authority)(?!\\s+[A-Z][a-zA-Z])"
    );

    // ── Written dates ─────────────────────────────────────────────────────────
    private static final Pattern WRITTEN_DATE_PATTERN = Pattern.compile(
        "(?i)(?:the\\s+)?" +
        "(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|" +
        "eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|" +
        "eighteenth|nineteenth|twentieth|twenty[-\\s]?(?:first|second|third|fourth|" +
        "fifth|sixth|seventh|eighth|ninth)|thirtieth|thirty[-\\s]?first)" +
        "\\s+day\\s+of\\s+" +
        "(?:January|February|March|April|May|June|July|August|September|October|November|December)" +
        "(?:,?\\s+(?:two thousand|\\d{4})[\\w\\s]*)?"
    );

    // ── Organization — STRICT: must end with a known company suffix OR follow known keyword ──
    // Prevents sentence fragments from being matched
    private static final Pattern ORG_STRICT_PATTERN = Pattern.compile(
        "(?i)" +
        // Pattern 1: Company name ending with legal suffix
        "([A-Z][A-Za-z0-9\\s&.,'-]{2,50}" +
        "(?:Ltd\\.?|LLC|Inc\\.?|Corp\\.?|Co\\.?|Limited|Pvt\\.?|PLC|LLP|" +
        "Technologies|Solutions|Services|Systems|Group|Holdings|Enterprises|Associates|Partners))" +
        // Pattern 2: Keyword followed by a proper noun (Title Case, short)
        "|(?:(?:company|client|vendor|employer|employee|contractor|lessor|lessee|" +
        "licensor|licensee|buyer|seller|supplier|between)\\s*[:\\-]?\\s*" +
        "([A-Z][A-Za-z0-9\\s&.,'-]{2,40})(?=[,\\n\\r;.]|\\s+(?:and|or|herein|with|as)))"
    );

    // ── Reference numbers ─────────────────────────────────────────────────────
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
        "(?i)(?:contract|agreement|invoice|order|po|ref|reference|case|doc(?:ument)?|ticket|file)" +
        "\\s*(?:no\\.?|number|#|id)\\s*[:\\-]?\\s*([\\w\\-./]{3,30})"
    );

    // ── General dates ─────────────────────────────────────────────────────────
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(?i)(?:(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
        "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)" +
        "\\s+\\d{1,2},?\\s+\\d{4})" +
        "|(?:\\d{1,2}[\\-/.]\\d{1,2}[\\-/.]\\d{2,4})" +
        "|(?:\\d{4}[\\-/.]\\d{1,2}[\\-/.]\\d{1,2})"
    );

    // ── Common English words — used to detect sentence fragments ─────────────
    private static final Set<String> FRAGMENT_INDICATORS = Set.of(
        "the","and","but","this","that","with","from","have","had","has",
        "was","were","been","they","their","them","what","when","where",
        "which","who","will","would","could","should","over","under","into",
        "than","then","send","want","place","sure","time","first","last",
        "year","years","make","made","said","also","just","more","most",
        "some","such","very","even","only","both","each","much","many"
    );

    // ─────────────────────────────────────────────────────────────────────────

    public List<ExtractedEntity> extractEntities(String text) {
        List<ExtractedEntity> entities = new ArrayList<>();
        if (text == null || text.isBlank()) return entities;

        log.info("Extracting entities from {} chars", text.length());

        extractDueDates(text, entities);
        extractSigningDates(text, entities);
        extractAmounts(text, entities);
        extractAmountsInWords(text, entities);
        extractDeductions(text, entities);
        extractNamedSignatories(text, entities);
        extractAmbiguousSignatories(text, entities);
        extractWrittenDates(text, entities);
        extractOrganizations(text, entities);
        extractReferenceNumbers(text, entities);
        extractGeneralDates(text, entities);

        log.info("Total entities: {}", entities.size());
        return entities;
    }

    // ── Extractors ───────────────────────────────────────────────────────────

    private void extractDueDates(String text, List<ExtractedEntity> out) {
        Matcher m = DUE_DATE_PATTERN.matcher(text);
        while (m.find()) {
            String val = groupOrFull(m, 1);
            if (isValidValue(val, 80)) out.add(entity("DUE_DATE", val, ctx(text, m.start()), 0.93));
        }
    }

    private void extractSigningDates(String text, List<ExtractedEntity> out) {
        Matcher m = SIGNING_DATE_PATTERN.matcher(text);
        while (m.find()) {
            String val = groupOrFull(m, 1);
            if (isValidValue(val, 80)) out.add(entity("SIGNING_DATE", val, ctx(text, m.start()), 0.90));
        }
    }

    private void extractAmounts(String text, List<ExtractedEntity> out) {
        Matcher m = AMOUNT_PATTERN.matcher(text);
        int count = 0;
        Set<String> seen = new HashSet<>();
        while (m.find() && count < 15) {
            String val = m.group().trim();
            // Deduplicate and skip bare single-digit amounts like "$3" without context
            if (isValidValue(val, 60) && !seen.contains(val) && val.replaceAll("[^\\d]","").length() >= 2) {
                out.add(entity("TOTAL_AMOUNT", val, ctx(text, m.start()), 0.88));
                seen.add(val);
                count++;
            }
        }
    }

    private void extractAmountsInWords(String text, List<ExtractedEntity> out) {
        Matcher m = AMOUNT_IN_WORDS_PATTERN.matcher(text);
        while (m.find()) {
            String val = m.group().trim();
            if (isValidValue(val, 100)) out.add(entity("AMOUNT_IN_WORDS", val, ctx(text, m.start()), 0.80));
        }
    }

    private void extractDeductions(String text, List<ExtractedEntity> out) {
        Matcher m = DEDUCTION_PATTERN.matcher(text);
        while (m.find()) {
            String val = m.group().trim();
            if (isValidValue(val, 80)) out.add(entity("DEDUCTION", val, ctx(text, m.start()), 0.85));
        }
    }

    private void extractNamedSignatories(String text, List<ExtractedEntity> out) {
        Matcher m = SIGNATORY_NAMED_PATTERN.matcher(text);
        while (m.find()) {
            String val = groupOrFull(m, 1);
            if (isValidValue(val, 60) && !isFragment(val))
                out.add(entity("SIGNATORY", val, ctx(text, m.start()), 0.87));
        }
    }

    private void extractAmbiguousSignatories(String text, List<ExtractedEntity> out) {
        Matcher m = SIGNATORY_AMBIGUOUS_PATTERN.matcher(text);
        while (m.find())
            out.add(entity("SIGNATORY_INCOMPLETE", m.group().trim() + " [Name not specified]",
                    ctx(text, m.start()), 0.60));
    }

    private void extractWrittenDates(String text, List<ExtractedEntity> out) {
        Matcher m = WRITTEN_DATE_PATTERN.matcher(text);
        while (m.find()) {
            String raw = m.group().trim();
            out.add(entity("DATE_WRITTEN_FORM", raw, ctx(text, m.start()), 0.78));
        }
    }

    private void extractOrganizations(String text, List<ExtractedEntity> out) {
        Matcher m = ORG_STRICT_PATTERN.matcher(text);
        int count = 0;
        Set<String> seen = new HashSet<>();
        while (m.find() && count < 8) {
            // Try group 1 first (suffix-based), then group 2 (keyword-based)
            String val = null;
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) { val = m.group(g).trim(); break; }
            }
            if (val == null) val = m.group().trim();

            // Strict validation: must NOT be a sentence fragment
            if (isValidValue(val, 80) && !isFragment(val) && !seen.contains(val.toLowerCase())) {
                out.add(entity("ORGANIZATION", val, ctx(text, m.start()), 0.80));
                seen.add(val.toLowerCase());
                count++;
            }
        }
    }

    private void extractReferenceNumbers(String text, List<ExtractedEntity> out) {
        Matcher m = REFERENCE_PATTERN.matcher(text);
        while (m.find()) {
            String val = groupOrFull(m, 1);
            if (isValidValue(val, 30)) out.add(entity("REFERENCE_NUMBER", val, ctx(text, m.start()), 0.91));
        }
    }

    private void extractGeneralDates(String text, List<ExtractedEntity> out) {
        Set<String> capturedCtx = new HashSet<>();
        out.forEach(e -> { if (e.getContext() != null) capturedCtx.add(e.getValue()); });

        Matcher m = DATE_PATTERN.matcher(text);
        int count = 0;
        Set<String> seen = new HashSet<>();
        while (m.find() && count < 10) {
            String val = m.group().trim();
            if (isValidValue(val, 40) && !seen.contains(val) && !capturedCtx.contains(val)) {
                out.add(entity("DATE", val, ctx(text, m.start()), 0.85));
                seen.add(val);
                count++;
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Validates that an extracted value is clean — not a sentence fragment.
     * Rejects values that:
     *  - Start with lowercase (indicates mid-sentence capture)
     *  - Are too long
     *  - Contain too many common English words (fragment indicator)
     */
    private boolean isValidValue(String val, int maxLen) {
        if (val == null || val.isBlank()) return false;
        if (val.length() > maxLen) return false;
        // Must not start with a lowercase letter (mid-sentence fragment)
        if (Character.isLowerCase(val.charAt(0))) return false;
        return true;
    }

    /**
     * Checks if a string is a sentence fragment rather than a proper entity name.
     * Returns true if >= 40% of words are common English words.
     */
    private boolean isFragment(String val) {
        if (val == null || val.isBlank()) return false;
        String[] words = val.toLowerCase().split("\\s+");
        if (words.length < 3) return false; // Short values are fine

        long fragmentWords = Arrays.stream(words)
                .filter(FRAGMENT_INDICATORS::contains)
                .count();
        double ratio = (double) fragmentWords / words.length;
        return ratio >= 0.40;
    }

    private String groupOrFull(Matcher m, int group) {
        try {
            String g = m.group(group);
            return g != null ? g.trim() : m.group().trim();
        } catch (IndexOutOfBoundsException e) {
            return m.group().trim();
        }
    }

    private ExtractedEntity entity(String type, String value, String context, double conf) {
        return ExtractedEntity.builder()
                .type(type).value(value).context(context).confidence(conf)
                .build();
    }

    private String ctx(String text, int pos) {
        int start = Math.max(0, pos - 80);
        int end   = Math.min(text.length(), pos + 80);
        return "..." + text.substring(start, end).replaceAll("[\\n\\r]+", " ").trim() + "...";
    }
}
