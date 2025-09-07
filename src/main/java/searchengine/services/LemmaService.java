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
    // Части речи, которые исключаются из индексации
    private static final Set<String> EXCLUDE_POS = Set.of("МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ", "МС");
    private static final int MIN_LEMMA_LENGTH = 3;

    public LemmaService() {
        // Временная переменная для инициализации
        LuceneMorphology tempMorph = null;
        try {
            tempMorph = new RussianLuceneMorphology(); // Создание экземпляра русской морфологии
        } catch (IOException e) {
            log.error("Failed to initialize LuceneMorphology", e);
        }
        this.luceneMorph = tempMorph;
    }

    // Метод для извлечения лемм из текста
    // Map (ключ - лемма, значение - количество вхождений)
    public Map<String, Integer> collectLemmas(String text) {
        // Проверяем инициализирована ли морфология
        if (luceneMorph == null) {
            log.error("Morphology analyzer not initialized");
            return Collections.emptyMap();
        }

        // Проверяем что текст не null и не пустой
        if (text == null || text.isBlank()) {
            return Collections.emptyMap();
        }

        // Очищаем текст: приводим к нижнему регистру, убираем лишние символы
        String cleanedText = cleanText(text);
        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        // Разбиваем текст на слова по пробелам
        for (String word : cleanedText.split("\\s+")) {
            if (word.isBlank()) continue; // Пропускаем пустые слова
            processWord(word, lemmaFrequencies); // Обрабатываем каждое слово
        }

        return lemmaFrequencies;
    }

    // Метод для очистки текста
    private String cleanText(String text) {
        return text.toLowerCase() // Приводим текст к нижнему регистру
                .replaceAll("[^а-яё\\s]", " ") // Заменяем все не-буквы на пробелы
                .replaceAll("\\s+", " ") // Заменяем множественные пробелы на один
                .trim(); // Убираем пробелы в начале и конце
    }

    // Метод для обработки одного слова
    private void processWord(String word, Map<String, Integer> lemmaFrequencies) {
        try {
            List<String> normalForms = luceneMorph.getNormalForms(word); // Получаем нормальные формы слова (леммы)
            if (normalForms.isEmpty()) return; // Если нормальных форм нет, выходим

            String lemma = normalForms.get(0); // Берем первую нормальную форму (основную лемму)
            // Проверяем валидность леммы
            if (isValidLemma(lemma, word)) {
                // Увеличиваем счетчик для этой леммы
                lemmaFrequencies.put(lemma, lemmaFrequencies.getOrDefault(lemma, 0) + 1);
            }
        } catch (Exception e) {
            log.debug("Error processing word: {}", word, e);
        }
    }

    // Метод для проверки валидности леммы
    private boolean isValidLemma(String lemma, String originalWord) {
        // Проверка длины леммы
        if (lemma.length() < MIN_LEMMA_LENGTH) return false;

        try {
            // Получение морфологической информации о слове
            return luceneMorph.getMorphInfo(originalWord).stream()
                    .noneMatch(info -> EXCLUDE_POS.stream().anyMatch(info::contains)); // Проверяем что ни одна часть речи не входит в исключения
        } catch (Exception e) {
            log.debug("Error checking lemma validity", e);
            return false;
        }
    }
}
