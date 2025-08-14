package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;


@Slf4j
@Service
public class LemmaService {
    private final LuceneMorphology luceneMorph;
    private static final Set<String> EXCLUDE_POS = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ", "МС");
    private static final int MIN_LEMMA_LENGTH = 3;

    public LemmaService() {
        LuceneMorphology tempMorph = null;
        try {
            tempMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            log.error("Failed to initialize LuceneMorphology", e);
        }
        this.luceneMorph = tempMorph;
    }

    public Map<String, Integer> collectLemmas(String text) {
        if (luceneMorph == null) {
            log.error("Morphology analyzer not initialized");
            return Collections.emptyMap();
        }

        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }

        String cleanedText = cleanText(text);
        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        for (String word : cleanedText.split("\\s+")) {
            if (word.isBlank()) continue;
            processWord(word, lemmaFrequencies);
        }

        return lemmaFrequencies;
    }

    private String cleanText(String text) {
        return text.toLowerCase()
                .replaceAll("[^а-яё\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void processWord(String word, Map<String, Integer> lemmaFrequencies) {
        try {
            List<String> normalForms = luceneMorph.getNormalForms(word);
            if (normalForms.isEmpty()) return;

            String lemma = normalForms.get(0);
            if (isValidLemma(lemma, word)) {
                lemmaFrequencies.put(lemma, lemmaFrequencies.getOrDefault(lemma, 0) + 1);
            }
        } catch (Exception e) {
            log.debug("Error processing word: {}", word, e);
        }
    }

    private boolean isValidLemma(String lemma, String originalWord) {
        if (lemma.length() < MIN_LEMMA_LENGTH) return false;

        try {
            return luceneMorph.getMorphInfo(originalWord).stream()
                    .noneMatch(info -> EXCLUDE_POS.stream().anyMatch(info::contains));
        } catch (Exception e) {
            log.debug("Error checking lemma validity", e);
            return false;
        }
    }
}
