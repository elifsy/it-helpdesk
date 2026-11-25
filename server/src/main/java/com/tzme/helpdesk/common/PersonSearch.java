package com.tzme.helpdesk.common;

import com.tzme.helpdesk.domain.User;
import net.sourceforge.pinyin4j.PinyinHelper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class PersonSearch {
    private static final Pattern TONE_MARKS = Pattern.compile("\\p{M}");
    private static final Pattern LETTERS = Pattern.compile("[a-z]+");

    private PersonSearch() { }

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    public static List<List<String>> namePinyin(String name) {
        List<List<String>> syllables = new ArrayList<>();
        boolean hasPinyin = false;
        for (char character : normalize(name).toCharArray()) {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(character);
            if (readings != null) {
                hasPinyin = true;
                syllables.add(Arrays.stream(readings)
                        .map(reading -> reading.substring(0, reading.length() - 1).replace("u:", "u"))
                        .distinct().toList());
            } else if (character >= 'a' && character <= 'z') {
                syllables.add(List.of(String.valueOf(character).replace('v', 'u')));
            } else if (Character.isLetterOrDigit(character)) {
                syllables.add(List.of(String.valueOf(character)));
            }
        }
        return hasPinyin ? syllables : List.of();
    }

    public record Query(String value, List<String> terms, List<String> phonetics) {
        public boolean matches(User user) {
            String details = normalize(user.getName() + " " + text(user.getDeptName()) + " " + text(user.getEmployeeNo()));
            List<List<String>> pinyin = null;
            for (int index = 0; index < terms.size(); index++) {
                if (details.contains(terms.get(index))) continue;
                String spelling = phonetics.get(index);
                if (spelling.isEmpty()) return false;
                if (pinyin == null) pinyin = namePinyin(user.getName());
                if (!phoneticMatch(pinyin, spelling, false) && !phoneticMatch(pinyin, spelling, true)) return false;
            }
            return true;
        }

        public int nameRank(User user) {
            String name = normalize(user.getName());
            if (name.equals(value)) return 0;
            if (name.startsWith(value)) return 1;
            return name.contains(value) ? 2 : 3;
        }
    }

    public static Query query(String value) {
        String normalized = normalize(value);
        List<String> terms = normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
        return new Query(normalized, terms, terms.stream().map(PersonSearch::phoneticTerm).toList());
    }

    private static String phoneticTerm(String value) {
        String spelling = TONE_MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("")
                .replace('v', 'u').replace("u:", "u").replace("'", "").replace("’", "");
        return LETTERS.matcher(spelling).matches() ? spelling : "";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    // Track how much of the query can match at syllable boundaries without expanding every reading of the name.
    private static boolean phoneticMatch(List<List<String>> pinyin, String query, boolean initials) {
        Set<Integer> offsets = Set.of(0);
        for (List<String> readings : pinyin) {
            Set<Integer> next = new HashSet<>();
            next.add(0);
            for (int offset : offsets) {
                String remaining = query.substring(offset);
                for (String reading : readings) {
                    String part = initials ? reading.substring(0, 1) : reading;
                    if (part.startsWith(remaining)) return true;
                    if (remaining.startsWith(part)) next.add(offset + part.length());
                }
            }
            offsets = next;
        }
        return false;
    }
}
