# Search Engine - Поисковый движок

![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.1-brightgreen.svg)
![MySQL](https://img.shields.io/badge/MySQL-8.0%2B-orange.svg)

## О проекте

Search Engine - это полнофункциональный поисковый движок, разработанный на Java с использованием Spring Boot. Система позволяет индексировать веб-сайты, обрабатывать текстовый контент и осуществлять быстрый поиск по проиндексированным данным.

Проект включает все требуемые функциональные возможности:

- Многопоточный обход сайтов
- Морфологический анализ слов
- Построение поискового индекса
- Релевантный поиск с ранжированием
- Веб-интерфейс для управления

## Основные возможности

- **Многопоточная индексация** - одновременный обход нескольких сайтов
- **Лемматизация** - поиск слов с учетом морфологии
- **Релевантный поиск** - ранжирование результатов по степени соответствия
- **Статистика** - мониторинг состояния индексации в реальном времени
- **Веб-интерфейс** - удобное управление через браузер

## Технологический стек

### Backend
- **Java 17** - основной язык программирования
- **Spring Boot 2.7.1** - фреймворк для создания приложения
- **Spring Data JPA** - работа с базой данных
- **Spring Web** - REST API и веб-контроллеры
- **Jsoup** - парсинг HTML контента

### База данных
- **MySQL 8.0+** - реляционная база данных
- **Hibernate** - ORM для работы с БД

### Обработка текста
- **Lucene Morphology** - морфологический анализ
- **Лемматизация** - приведение слов к начальной форме

### Frontend
- **Thymeleaf** - шаблонизатор для HTML
- **Bootstrap** - CSS фреймворк
- **JavaScript** - интерактивность интерфейса

### Инструменты
- **Maven** - сборка проекта и управление зависимостями
- **Lombok** - генерация шаблонного кода
- **Logback** - логирование приложения

## Старт приложения

### Предварительные требования

Перед запуском убедитесь, что у вас установлено:

- **Java 17 или новее**
```bash
  java -version
```

- **MySQL Server 8.0 или новее**
```bash
  mysql --version
```

- **Maven 3.6 или новее**
```bash
    mvn -version
```

### Шаг 1: Настройка базы данных

1. Запустите MySQL сервер 
2. Создайте базу данных:
```sql
CREATE DATABASE search_engine CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
3. Создайте пользователя:
```sql
CREATE USER 'se_user'@'localhost' IDENTIFIED BY 'se_password';
GRANT ALL PRIVILEGES ON search_engine.* TO 'se_user'@'localhost';
FLUSH PRIVILEGES;
```
### Шаг 2: Настройка проекта

1. Клонируйте или скачайте проект 
2. Перейдите в директорию проекта:
```bash
  cd search-engine
```
3. Настройте подключение к БД в application.yaml:
```yaml
spring:
  datasource:
    username: se_user       # Ваш пользователь MySQL
    password: se_password   # Ваш пароль MySQL
    url: jdbc:mysql://localhost:3306/search_engine?useSSL=false&requireSSL=false&allowPublicKeyRetrieval=true
```
### Шаг 3: Сборка и запуск

**Способ 1: Запуск через Maven**
```bash
    # Сборка и запуск
    mvn spring-boot:run
    
    # Или сначала соберите, потом запустите
    mvn clean package
    java -jar target/SearchEngine-1.0-SNAPSHOT.jar
```

**Способ 2: Запуск в IDE**

1. Откройте проект в IntelliJ IDEA или Eclipse 
2. Найдите класс Application.java 
3. Запустите метод main()

### Шаг 4: Использование

После запуска приложение будет доступно по адресу:

```text
http://localhost:8080
```
## Использование системы

### Dashboard

- Просмотр общей статистики
- Мониторинг статуса индексации
- Информация по каждому сайту

### Management

- Запуск индексации - начать полную индексацию всех сайтов
- Остановка индексации - прервать текущий процесс
- Добавление страницы - индексация отдельной URL

### Search

- Поиск по всем сайтам - введите запрос в поле поиска
- Поиск по конкретному сайту - выберите сайт из выпадающего списка
- Пагинация - просмотр результатов постранично

## Конфигурация

### Настройка сайтов для индексации

Отредактируйте файл application.yaml:

```yaml
indexing-settings:
  sites:
    - url: https://example.com
      name: Example Site
    - url: https://another-site.com
      name: Another Site
  user-agent: YourSearchBot
  referrer: https://www.google.com
```

### Параметры запуска

Дополнительные параметры можно передать через командную строку:

```bash
  java -jar target/SearchEngine-1.0-SNAPSHOT.jar \
    --server.port=9090 \
    --spring.datasource.username=myuser \
    --spring.datasource.password=mypass
```
## Решение проблем

### Ошибка подключения к MySQL

```yaml
# Проверьте настройки в application.yaml
spring:
  datasource:
    username: ваш_пользователь
    password: ваш_пароль
    url: jdbc:mysql://localhost:3306/search_engine?useSSL=false&requireSSL=false&allowPublicKeyRetrieval=true
```

### Ошибка индексации сайтов

- Проверьте доступность сайтов из конфигурации
- Убедитесь, что firewall не блокирует бота

### Проблемы с морфологией

- Убедитесь что файлы в папке libs/ присутствуют
- Проверьте права доступа к файлам

## Разработка

### Структура проекта

```text
src/
├── main/
│   ├── java/searchengine/
│   │   ├── config/          # Конфигурационные классы
│   │   ├── controllers/     # Веб-контроллеры
│   │   ├── dto/statistics/  # Data Transfer Objects для статистики
│   │   ├── model/           # Сущности базы данных
│   │   ├── repositories/    # Репозитории Spring Data
│   │   ├── services/        # Бизнес-логика
│   │   └── Application.java
│   ├── resources/
│   │   ├── static/assets/   # Статические файлы (CSS, JS, изображения)
│   │   └── templates/       # HTML шаблоны
├── application.yaml
├── pom.xml
└── README.md
```

## Поддержка

Если у вас возникли вопросы или проблемы:
- Проверьте логи приложения в консоли
- Убедитесь в корректности настроек БД
- Проверьте доступность сайтов для индексации