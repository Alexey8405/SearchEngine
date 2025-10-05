package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class LemmaService {
    private final LuceneMorphology russianMorph;
    private final LuceneMorphology englishMorph;

    // Части речи, которые исключаются из индексации (для русского)
    private static final Set<String> RUSSIAN_EXCLUDE_POS = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ", "МС");
    // Части речи, которые исключаются из индексации (для английского)
    private static final Set<String> ENGLISH_EXCLUDE_POS = Set.of("CONJ", "PREP", "ARTICLE", "PART", "INT");

    private static final int MIN_LEMMA_LENGTH = 3;
    // Регулярные выражения для определения языка
    private static final Pattern RUSSIAN_PATTERN = Pattern.compile("[а-яё]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENGLISH_PATTERN = Pattern.compile("[a-z]", Pattern.CASE_INSENSITIVE);

    public LemmaService() {
        // Временные переменные для инициализации +1 переменной
        LuceneMorphology tempRussianMorph = null;
        LuceneMorphology tempEnglishMorph = null;
        try {
            tempRussianMorph = new RussianLuceneMorphology(); // создание экземпляра русской морфологии
            tempEnglishMorph = new EnglishLuceneMorphology(); // создание экземпляра английской морфологии
        } catch (IOException e) {
            log.error("Failed to initialize LuceneMorphology", e);
        }
        this.russianMorph = tempRussianMorph;
        this.englishMorph = tempEnglishMorph;
    }

    // Метод для извлечения лемм из текста
    // Map (ключ - лемма, значение - количество вхождений)
    public Map<String, Integer> collectLemmas(String text) {
        // Проверяем инициализирована ли морфология
        if (russianMorph == null || englishMorph == null) {
            log.error("Morphology analyzer not initialized");
            return Collections.emptyMap();
        }

        // Проверяем что текст не null и не пустой
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        // Разбиваем текст на слова и обрабатываем каждое слово
        String[] words = text.toLowerCase()
                .replaceAll("[^a-zA-Zа-яё\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .split("\\s+");

        // Проверка на минимальную длину леммы
        for (String word : words) {
            if (word.isBlank() || word.length() < MIN_LEMMA_LENGTH) continue;
            processWord(word, lemmaFrequencies);
        }

        return lemmaFrequencies;
    }

    // Метод для обработки одного слова
    private void processWord(String word, Map<String, Integer> lemmaFrequencies) {
        try {
            String language = detectLanguage(word);
            LuceneMorphology morphology = getMorphologyForLanguage(language);
            Set<String> excludePos = getExcludePosForLanguage(language);

            if (morphology == null) return;

            List<String> normalForms = morphology.getNormalForms(word);
            if (normalForms.isEmpty()) return;

            String lemma = normalForms.get(0);
            if (isValidLemma(lemma, word, morphology, excludePos)) {
                lemmaFrequencies.put(lemma, lemmaFrequencies.getOrDefault(lemma, 0) + 1);
            }
        } catch (Exception e) {
            log.debug("Error processing word: {}", word, e);
        }
    }

    // Метод определения языка слова
    private String detectLanguage(String word) {
        boolean hasRussian = RUSSIAN_PATTERN.matcher(word).find();
        boolean hasEnglish = ENGLISH_PATTERN.matcher(word).find();

        if (hasRussian && !hasEnglish) return "russian";
        if (hasEnglish && !hasRussian) return "english";
        if (hasRussian && hasEnglish) return "russian"; // Приоритет русскому

        return "unknown";
    }

    // Получение морфологии по языку
    private LuceneMorphology getMorphologyForLanguage(String language) {
        return switch (language) {
            case "russian" -> russianMorph;
            case "english" -> englishMorph;
            default -> null;
        };
    }

    // Получение исключаемых частей речи по языку
    private Set<String> getExcludePosForLanguage(String language) {
        return switch (language) {
            case "russian" -> RUSSIAN_EXCLUDE_POS;
            case "english" -> ENGLISH_EXCLUDE_POS;
            default -> Collections.emptySet();
        };
    }

    // Метод проверки валидности леммы с поддержкой языка
    private boolean isValidLemma(String lemma, String originalWord,
                                 LuceneMorphology morphology, Set<String> excludePos) {
        if (lemma.length() < MIN_LEMMA_LENGTH) return false;

        try {
            // Получение морфологической информации о слове
            return morphology.getMorphInfo(originalWord).stream()
                    .noneMatch(info -> excludePos.stream().anyMatch(info::contains)); // Проверяем что ни одна часть речи не входит в исключения
        } catch (Exception e) {
            log.debug("Error checking lemma validity", e);
            return false;
        }
    }

    // Метод для определения языка слова (для использования в поиске)
    public String getWordLanguage(String word) {
        return detectLanguage(word);
    }

    // Метод для получения морфологии по языку
    public LuceneMorphology getMorphologyByLanguage(String language) {
        return getMorphologyForLanguage(language);
    }
}
