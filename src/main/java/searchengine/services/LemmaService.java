package searchengine.services;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LemmaService {
    private final LuceneMorphology luceneMorph;

    public LemmaService() throws IOException {
        this.luceneMorph = new RussianLuceneMorphology(); // Для английского: EnglishLuceneMorphology
    }

    // Получение лемм из текста с частотами
    public Map<String, Integer> collectLemmas(String text) {
        text = text.toLowerCase(Locale.ROOT).replaceAll("[^а-яё\\s]", " ");
        String[] words = text.split("\\s+");

        Map<String, Integer> lemmas = new HashMap<>();
        for (String word : words) {
            if (word.isBlank()) continue;

            List<String> wordBaseForms = luceneMorph.getNormalForms(word);
            if (wordBaseForms.isEmpty() || isServiceWord(word)) continue;

            String lemma = wordBaseForms.get(0);
            lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
        }
        return lemmas;
    }

    // Проверка, является ли слово служебной частью речи
    private boolean isServiceWord(String word) {
        List<String> morphInfo = luceneMorph.getMorphInfo(word);
        return morphInfo.stream().anyMatch(info ->
                info.contains("СОЮЗ") || info.contains("МЕЖД") ||
                        info.contains("ПРЕДЛ") || info.contains("ЧАСТ"));
    }
}
