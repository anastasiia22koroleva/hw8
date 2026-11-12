# Отчёт: CPU-профиль /nplus1 и /optimized

## Условия

- MacBook (Apple Silicon), JDK 23, Gradle 9.1, Spring Boot 3.5.6, Hibernate 6.6.29, H2 в памяти.
- База заполнена задачей `./gradlew runTestDataInitializer -Pmessages=2000 -Pauthors=200`:
  2000 сообщений, 200 авторов, каждый GET отдаёт все 2000 сообщений.
- Перед замерами прогрев - по 300 запросов на каждый эндпоинт, чтобы JIT успел скомпилировать горячий код.
- Нагрузка - curl, 4 запроса параллельно.
- Профилировщик - Java Flight Recorder (`settings=profile`), отдельная запись на каждый эндпоинт
  через `jcmd JFR.start / JFR.stop`. Приложение запущено через `./gradlew bootRun -Pjfr`, там же
  `-XX:FlightRecorderOptions=stackdepth=256`: стек Tomcat + Spring + Hibernate глубже 64 кадров
  (значение по умолчанию), и без этого flame graph обрезается снизу.

Сначала было сделано, как в задании, 100 запросов подряд, но сэмплов набралось всего 4 на запись:
сервер тратит на запрос несколько миллисекунд, а остальное время ждёт следующий curl. Поэтому для
flame graph по 15 000 запросов на эндпоинт, а для сравнения CPU - по 1000 запросов, три раза.

## Количество SQL на один GET

| | /nplus1 | /optimized |
|---|---|---|
| запросов в базу | 1 + 200 = 201 | 1 |

`/nplus1`: `select ... from messages order by id`, потом для каждого нового автора
`select ... from authors where id=?`. `/optimized`: один `select ... from messages join authors ...`.
Видно в логе с профилем `sql`, а тест `MessageServiceTest` проверяет то же через
`Statistics.getPrepareStatementCount()` (5 авторов → 6 и 1 запрос).

## Время и процессор

Процессорное время JVM (`ps -o cputime` до и после) на 1000 запросов:

| прогон | /nplus1, CPU | /nplus1, запросов/с | /optimized, CPU | /optimized, запросов/с |
|---|---|---|---|---|
| 1 | 12,4 с | 325 | 7,6 с | 483 |
| 2 | 11,2 с | 337 | 7,8 с | 468 |
| 3 | 11,0 с | 332 | 8,4 с | 425 |
| среднее | **11,5 мс на запрос** | **331** | **8,0 мс на запрос** | **459** |

Под записью JFR, 15 000 запросов: `/nplus1` - 43,7 с, `/optimized` - 30,0 с.
Сборок мусора за запись: 257 против 171.

Итого `/optimized` тратит примерно на 30 % меньше CPU на запрос и обрабатывает примерно на 40 %
больше запросов в секунду. Ответ у обоих одинаковый (тоже проверено тестом).

## Flame graph /nplus1

[flame-nplus1.svg](flame-nplus1.svg), 351 сэмпл в потоках `http-nio-8080-exec-*`.

| что | доля сэмплов |
|---|---|
| `MessageService.getMessagesNPlusOne` (тело метода) | 17,7 % |
| ├ `SimpleJpaRepository.findAll` - первый select по сообщениям | 14,0 % |
| └ `MessageDto.from` → `Author$HibernateProxy.getUsername` | 3,7 % |
| &nbsp;&nbsp;&nbsp;└ `AbstractLazyInitializer.initialize` → `SessionImpl.immediateLoad` | 3,4 % |
| commit транзакции и закрытие сессии | 62,7 % |
| ├ `StatefulPersistenceContext.afterTransactionCompletion` → `EntityEntryContext.downgradeLocks` | 36,5 % |
| └ `SessionImpl.close` (очистка persistence context) | 25,9 % |
| Jackson (сериализация ответа) | 12,5 % |
| H2 (сама работа базы) | 4,0 % |
| Hibernate целиком | 81,2 % |

Справа на графе узкая красная башня: `MessageDto.from` (строка
`message.getAuthor().getUsername()`) → прокси `Author$HibernateProxy` →
`AbstractLazyInitializer.initialize` → `SessionImpl.immediateLoad` → загрузка одного автора по id.
Это и есть N+1: на каждого нового автора отдельный select со всей обвязкой Hibernate.
В Hibernate 6.6 такой select по первичному ключу стоит недорого, поэтому сама башня занимает
всего несколько процентов.

Больше всего времени в `/nplus1` уходит на конец транзакции. После commit Hibernate обходит
все сущности в сессии и сбрасывает им режим блокировки (`downgradeLocks` → `processEachManagedEntity`
→ `AbstractEntityEntry.setLockMode`, первое место по собственному времени - 25 %), а потом очищает
сессию в `SessionImpl.close`. В `/optimized` ветка `downgradeLocks` - 8 %.

Почему разница такая большая, по профилю сказать не получилось. `setLockMode` - это просто запись
поля (видно в байткоде), а сущностей в сессии в обоих случаях почти одинаково: 2000 сообщений
и 200 авторов (в `/nplus1` к ним добавляются ещё 200 прокси). Похоже, что часть этой доли - особенность
сэмплирования JFR, которое приписывает время цикла одному методу. Но во всех трёх записях, которые
делались (в том числе на Hibernate 6.5 до обновления версий), commit у `/nplus1` был заметно тяжелее.

## Flame graph /optimized

[flame-optimized.svg](flame-optimized.svg), 221 сэмпл.

| что | доля сэмплов |
|---|---|
| `MessageService.getMessagesOptimized` (тело метода) | 38,9 % |
| ├ `findAllWithAuthor` - один select с join | 29,0 % |
| └ `MessageDto.from` | 2,7 % |
| `AbstractLazyInitializer.initialize` | 0 % |
| commit транзакции и закрытие сессии | 33,9 % |
| ├ `afterTransactionCompletion` → `downgradeLocks` | 8,1 % |
| └ `SessionImpl.close` | 25,8 % |
| Jackson | 19,5 % |
| H2 | 7,2 % |
| Hibernate целиком | 61,1 % |

Красной башни ленивой загрузки нет совсем: `MessageDto.from` только читает уже заполненные поля.
Основная работа сервиса - разбор одного результата (`StandardRowReader.readRow`, 25 %): автор
собирается из той же строки, что и сообщение. Доля Jackson (формирование JSON) выросла с 12,5 до
19,5 % - полезной работы в запросе стало больше относительно накладных расходов.

## Выводы

1. Участок с избыточной нагрузкой - цикл в `MessageService.getMessagesNPlusOne()`, где
   `MessageDto.from` обращается к ленивому `author`. Он превращается в 200 дополнительных
   select-ов на каждый GET (красная башня `HibernateProxy.getUsername → AbstractLazyInitializer.initialize`)
   и в более тяжёлое завершение транзакции.
2. `join fetch` в `findAllWithAuthor()` убирает ленивую загрузку полностью: один запрос,
   примерно на 30 % меньше CPU на запрос и примерно на 40 % больше запросов в секунду.
3. Разница здесь занижена: H2 работает в памяти того же процесса, и select к нему почти бесплатный.
   С настоящей базой по сети каждый из 200 лишних запросов добавит ещё и сетевую задержку,
   и `/nplus1` будет отставать по времени ответа гораздо сильнее, чем по CPU.
4. Другие варианты исправления: `@EntityGraph(attributePaths = "author")` на методе репозитория
   (так сделан `getMessage(id)`), `@BatchSize` на `Author` или `hibernate.default_batch_fetch_size` -
   тогда вместо 200 запросов будет 200 / размер пачки.

Замечание по методике: JFR на macOS снимает стеки редко (несколько сотен сэмплов за минуту),
и число сэмплов в разных записях между собой не сравнимо. Поэтому проценты выше - это доли внутри
одной записи, а абсолютное сравнение сделано по процессорному времени JVM.
