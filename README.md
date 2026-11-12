# Сообщения: Gradle, отладка и профилирование

Небольшое Spring Boot приложение с сообщениями и авторами (H2 в памяти). В нём два проблемных
сценария из прошлого задания - N+1 и `LazyInitializationException` - и на них отрабатываются
сборка Gradle, дебаггер IntelliJ IDEA и профилировщик (Java Flight Recorder).

Сущности: `Author` (id, username) и `Message` (id, text, createdAt, author).
Связь `Message.author` - `@ManyToOne(fetch = LAZY)`, то есть вместо автора Hibernate кладёт прокси.

| Метод | URL | Что делает |
|---|---|---|
| POST | `/api/messages` | принимает массив `[{"author": "...", "text": "..."}]`, авторов создаёт сам |
| GET | `/api/messages/nplus1` | все сообщения через `findAll()` - проблема N+1 |
| GET | `/api/messages/optimized` | все сообщения одним запросом с `join fetch` |
| GET | `/api/messages/{id}` | одно сообщение (автор через `@EntityGraph`) |
| GET | `/api/messages/{id}/fail` | одно сообщение вне транзакции - `LazyInitializationException` |

`spring.jpa.open-in-view=false` выключен специально: иначе Spring держит сессию открытой до конца
HTTP-запроса и ленивая ошибка не воспроизводится.

## Требования

JDK 17 или новее (проверялось на 23). Gradle ставить не нужно - wrapper скачает Gradle 9.1,
который запускается на JDK 17-25.
Для задачи заполнения базы нужен `curl` (в macOS, Linux и Windows 10+ он есть).

## Задание 1. Сборка Gradle

```bash
./gradlew build      # компиляция + тесты
./gradlew bootRun    # запуск на http://localhost:8080
```

В Windows вместо `./gradlew` - `gradlew.bat`.

Что в `build.gradle`:

- плагины `org.springframework.boot` и `io.spring.dependency-management`, отсюда задачи `bootRun` и `bootJar`;
- в начале файла отдельный блок Hibernate / JPA: версии в `ext` (`hibernateVersion`,
  `jakartaPersistenceVersion`, `h2Version`) и свой блок `dependencies` с этими переменными;
- задача `runTestDataInitializer` - генерирует JSON с тестовыми сообщениями в `build/test-data.json`
  и отправляет его `curl`-ом POST-запросом на `/api/messages`;
- `./gradlew bootRun -Pjfr` - запуск с записью Java Flight Recorder в `build/jfr/app.jfr`.

Заполнение базы (приложение уже должно быть запущено, команда - во втором терминале):

```bash
./gradlew runTestDataInitializer
# по умолчанию 1000 сообщений от 50 авторов, можно поменять:
./gradlew runTestDataInitializer -Pmessages=2000 -Pauthors=200
```

Ответ: `{"saved":2000,"total":2000}`. Проверка:

```bash
curl http://localhost:8080/api/messages/5
curl http://localhost:8080/api/messages/5/fail
```

Второй запрос возвращает 500:
`{"error":"LazyInitializationException","message":"Could not initialize proxy [messages.model.Author#5] - no session"}`.

Чтобы видеть все SQL-запросы в консоли: `./gradlew bootRun --args='--spring.profiles.active=sql'`.

Тесты (`MessageServiceTest`) через статистику Hibernate проверяют, что `/nplus1` делает
1 + (число авторов) запросов, `/optimized` - ровно один, а `getMessageToFail` падает с
`LazyInitializationException`.

## Задание 2. Отладка в IntelliJ IDEA

Открыть папку проекта (File → Open), IDEA сама подхватит Gradle. Запуск в отладке:
`MessagesApplication` → Debug, либо в окне Gradle `Tasks → application → bootRun` → правой кнопкой → Debug.
Базу заполнить `./gradlew runTestDataInitializer`, запросы слать curl-ом из терминала.

### Пошаговое выполнение

Точка останова - `MessageService.getMessagesOptimized()`, строка 74
(`messageRepository.findAllWithAuthor()`). Можно поставить ещё одну в
`MessageController.optimized()`, строка 46, чтобы начать с контроллера.

`curl http://localhost:8080/api/messages/optimized`, дальше:

- в контроллере F7 (Step Into) заходит не сразу в сервис, а в прокси Spring
  `MessageService$$SpringCGLIB$$0` - это обёртка `@Transactional`, которая открывает транзакцию.
  Чтобы не ходить по коду Spring, удобнее Smart Step Into (Shift + F7) или добавить
  `org.springframework.*` в Settings → Build, Execution, Deployment → Debugger → Stepping →
  "Do not step into the classes";
- в сервисе на строке 74 F7 ведёт не в `MessageRepository` (это интерфейс, кода там нет), а в
  `JdkDynamicAopProxy.invoke` - прокси, который Spring Data создаёт для репозитория. Дальше по цепочке
  интерцепторов (транзакция, `QueryExecutorMethodInterceptor`) вызов доходит до выполнения JPQL из
  `@Query`; методы вроде `findAll()` и `findById()` попадают в `SimpleJpaRepository`;
- F8 (Step Over) по строкам 75-78 - цикл, где из каждого `Message` собирается `MessageDto`.

### Variables и Watches: почему N+1

Точка останова в `MessageService.getMessagesNPlusOne()`, строка 64
(`result.add(MessageDto.from(message))`), запрос `curl http://localhost:8080/api/messages/nplus1`.

В Variables у `message`:

- `id`, `text`, `createdAt` - уже заполнены, они пришли первым запросом `select ... from messages`;
- `author` - объект класса `Author$HibernateProxy`, внутри `$$_hibernate_interceptor`
  с `initialized = false`, а поля `id`/`username` самого прокси пустые. Известен только id автора
  (он лежит в `author_id` у сообщения).

В Watches полезно добавить:

```
org.hibernate.Hibernate.isInitialized(message.getAuthor())
message.getAuthor().getClass().getSimpleName()
```

Если сделать F8, `MessageDto.from` вызовет `getUsername()`, прокси пойдёт в базу отдельным
`select ... from authors where id=?`, и для следующего сообщения того же автора
`isInitialized` уже будет `true` (автор лежит в сессии). Новый автор - новый запрос.
Поэтому запросов получается 1 + число разных авторов: при 200 авторах - 201 на один GET.
Важно: watch с `getUsername()` (и иногда сам показ `author` в Variables, если IDEA вызывает у
прокси `toString()`) инициализирует прокси, и запрос уйдёт раньше, чем до него дойдёт код.

Условная точка останова: правой кнопкой по точке на строке 64 → Condition:

```java
message.getId() == 5
```

Остановка будет только на пятом сообщении. В том же окне можно включить "Evaluate and log"
с выражением `message.getAuthor().getUsername()`, не останавливая выполнение.

### LazyInitializationException

`getMessageToFail(id)` специально без `@Transactional`. Транзакция у `findById` открывается и
закрывается внутри `SimpleJpaRepository`, поэтому на строке 97 сессии уже нет, а автор - прокси.

1. Точка останова в `MessageService.getMessageToFail`, строка 95. `curl http://localhost:8080/api/messages/5/fail`.
2. F8 - строка 97, `message` уже получено. Alt + F8 (Option + F8 на Mac), выражение
   `message.getAuthor().getUsername()` → Evaluate. Результат - исключение
   `org.hibernate.LazyInitializationException: Could not initialize proxy [messages.model.Author#5] - no session`.
3. Для сравнения: точка останова внутри `SimpleJpaRepository.findById` (или F7 со строки 95),
   то же выражение там вычисляется нормально - транзакция и сессия ещё открыты.
   Ещё вариант - точка на строке 64 в `getMessagesNPlusOne()`: метод под `@Transactional`,
   и `getUsername()` работает.

Вывод: ошибка возникает не при загрузке сообщения, а в момент первого обращения к полю ленивого
прокси, когда `Session`, которой он принадлежит, уже закрыта. Лечится тем, что автор загружается
заранее (`join fetch` / `@EntityGraph`, как в `getMessage(id)`), либо работа с сущностью идёт
внутри `@Transactional`. Включать `open-in-view` или `enable_lazy_load_no_trans` - плохой путь.

## Задание 3. Профилирование

Подробный отчёт с цифрами - [profiling/REPORT.md](profiling/REPORT.md),
flame graph - [profiling/flame-nplus1.svg](profiling/flame-nplus1.svg) и
[profiling/flame-optimized.svg](profiling/flame-optimized.svg) (открывать в браузере, при наведении
видны полное имя метода и число сэмплов).

Как снять профиль самому:

- IntelliJ IDEA Ultimate: Run → "Run 'MessagesApplication' with Profiler" (IntelliJ Profiler / JFR),
  дать нагрузку, Stop → вкладки Flame Graph и Call Tree;
- без IDEA, через JFR:

```bash
./gradlew bootRun
./gradlew runTestDataInitializer -Pmessages=2000 -Pauthors=200

jcmd                                   # найти PID MessagesApplication
jcmd <PID> JFR.start name=nplus1 settings=profile
curl -s -Z --parallel-max 4 -o /dev/null "http://localhost:8080/api/messages/nplus1?r=[1-3000]"
jcmd <PID> JFR.stop name=nplus1 filename=nplus1.jfr
```

Файл `.jfr` открывается в IntelliJ IDEA Ultimate (File → Open) или JDK Mission Control.
`?r=[1-3000]` - это перебор URL у curl (3000 запросов, по 4 параллельно), сервер параметр игнорирует.
