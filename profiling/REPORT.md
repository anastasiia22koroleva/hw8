# Отчёт: CPU-профиль /nplus1 и /optimized

## Условия

- MacBook (Apple Silicon), JDK 23, Spring Boot 3.3.5, Hibernate 6.5.3, H2 в памяти.
- База заполнена задачей `./gradlew runTestDataInitializer -Pmessages=2000 -Pauthors=200`:
  2000 сообщений, 200 авторов, каждый GET отдаёт все 2000 сообщений.
- Перед замерами прогрев - по 300 запросов на каждый эндпоинт, чтобы JIT успел скомпилировать горячий код.
- Нагрузка - curl, 4 запроса параллельно.
- Профилировщик - Java Flight Recorder (`settings=profile`), отдельная запись на каждый эндпоинт
  через `jcmd JFR.start / JFR.stop`. Приложение запущено с `-XX:FlightRecorderOptions=stackdepth=256`
  (`./gradlew bootRun -Pjfr`), потому что стек Tomcat + Spring + Hibernate глубже 64 кадров и
  иначе flame graph обрезается снизу.

Сначала было сделано, как в задании, 100 запросов подряд, но сэмплов набралось всего 4 на запись:
сервер тратит на запрос несколько миллисекунд, а остальное время ждёт следующий curl. Поэтому для
flame graph по 15 000 запросов на эндпоинт, а для сравнения CPU - по 1000 запросов, три раза.

## Количество SQL на один GET

| | /nplus1 | /optimized |
|---|---|---|
| запросов в базу | 1 + 200 = 201 | 1 |

Проверено тестом `MessageServiceTest` через `Statistics.getPrepareStatementCount()`
(в тесте 5 авторов → 6 и 1 запрос).

## Время и процессор

Процессорное время JVM (`ps -o cputime` до и после) на 1000 запросов:

| прогон | /nplus1, CPU | /nplus1, запросов/с | /optimized, CPU | /optimized, запросов/с |
|---|---|---|---|---|
| 1 | 15,2 с | 245 | 10,9 с | 335 |
| 2 | 15,9 с | 235 | 11,1 с | 304 |
| 3 | 13,8 с | 270 | 9,9 с | 354 |
| среднее | **15,0 мс на запрос** | **250** | **10,6 мс на запрос** | **331** |

Под записью JFR, 15 000 запросов: `/nplus1` - 59,1 с, `/optimized` - 33,1 с.
Сборок мусора за запись: 318 против 193.

Итого `/optimized` тратит примерно на 30 % меньше CPU на запрос и держит примерно на 30 % больше
запросов в секунду. Ответ у обоих одинаковый (тоже проверено тестом).

## Flame graph /nplus1

[flame-nplus1.svg](flame-nplus1.svg), 727 сэмплов в потоках `http-nio-8080-exec-*`.

| что | доля сэмплов |
|---|---|
| `MessageService.getMessagesNPlusOne` всего | 39,3 % |
| ├ `SimpleJpaRepository.findAll` - первый select по сообщениям | 28,2 % |
| └ `MessageDto.from` → `Author$HibernateProxy.getUsername` | 10,7 % |
| &nbsp;&nbsp;&nbsp;└ `AbstractLazyInitializer.initialize` → `SessionImpl.immediateLoad` → `SingleIdEntityLoader.load` | 10,3 % |
| commit и закрытие сессии (`AbstractPlatformTransactionManager.commit`) | 41,1 % |
| ├ `StatefulPersistenceContext.afterTransactionCompletion` (`EntityEntryContext.downgradeLocks`) | 23,8 % |
| └ `SessionImpl.close` (очистка persistence context) | 17,3 % |
| Jackson (сериализация ответа) | 12,1 % |
| H2 (сама работа базы) | 9,1 % |
| Hibernate целиком | 80,2 % |

Самый «лишний» участок - правая башня графа: `MessageDto.from` (строка
`message.getAuthor().getUsername()`) → прокси ByteBuddy → `AbstractLazyInitializer.initialize` →
`DefaultLoadEventListener` → `SingleIdEntityLoader` → `JdbcSelectExecutor`. Это и есть N+1:
на каждого нового автора отдельный select со всей обвязкой Hibernate - событие загрузки,
подготовка statement, разбор строки результата, регистрация сущности в persistence context.
Сам H2 в этой башне занимает малую часть, основное время - накладные расходы Hibernate на запрос.

Второе заметное место - commit транзакции: после каждого запроса Hibernate проходит по всем
управляемым сущностям (`EntityEntryContext.downgradeLocks` → `processEachManagedEntity`) и сбрасывает
им режим блокировки. `AbstractEntityEntry.setLockMode` - первое место по собственному времени
(17,6 % сэмплов, в `/optimized` - 3,1 %), а вся ветка `afterTransactionCompletion` - 24 % против 8 %.
Сущностей в сессии в обоих случаях одинаково (2000 сообщений + 200 авторов), так что разница
неожиданная; часть её может быть погрешностью сэмплирования, но и в пробной записи (со стеками
по 64 кадра) commit у `/nplus1` был тяжелее: 21 % против 6 %.

## Flame graph /optimized

[flame-optimized.svg](flame-optimized.svg), 192 сэмпла.

| что | доля сэмплов |
|---|---|
| `MessageService.getMessagesOptimized` всего | 39,6 % |
| ├ `findAllWithAuthor` - один select с join | 30,7 % |
| └ `MessageDto.from` | 1,6 % |
| `AbstractLazyInitializer.initialize` | 0 % |
| commit и закрытие сессии | 31,2 % |
| ├ `afterTransactionCompletion` | 7,8 % |
| └ `SessionImpl.close` | 23,4 % |
| Jackson | 19,3 % |
| H2 | 5,2 % |
| Hibernate целиком | 61,5 % |

Башни ленивой загрузки нет совсем: `MessageDto.from` только читает уже заполненные поля.
Почти всё время сервиса - разбор одного результата (`StandardRowReader.readRow`,
`EntityJoinedFetchInitializer` - автор собирается из той же строки). Из-за этого доля Jackson
(формирование JSON) выросла с 12 до 19 %: полезной работы в запросе стало больше относительно
накладных.

## Выводы

1. Узкое место `/nplus1` - обращение к ленивому `author` в цикле (`MessageDto.from`),
   которое превращается в 200 дополнительных select-ов на каждый GET. На flame graph это
   отдельная башня `HibernateProxy.getUsername → AbstractLazyInitializer.initialize`, около 10 %
   сэмплов, плюс более дорогой commit.
2. `join fetch` в `findAllWithAuthor()` убирает эту башню полностью: один запрос, около 30 %
   меньше CPU на запрос и около 30 % больше пропускной способности.
3. Разница здесь занижена: H2 работает в памяти того же процесса, select к нему почти бесплатный.
   С настоящей базой по сети каждый из 200 лишних запросов добавит ещё и сетевую задержку,
   и `/nplus1` будет отставать по времени ответа гораздо сильнее, чем по CPU.
4. Другие варианты исправления: `@EntityGraph(attributePaths = "author")` на методе репозитория
   (так сделан `getMessage(id)`), `@BatchSize` на `Author` или `hibernate.default_batch_fetch_size` -
   тогда вместо 200 запросов будет 200 / размер пачки.

Замечание по методике: JFR на macOS снимает стеки редко (несколько сотен сэмплов за минуту),
и число сэмплов разных записей между собой не сравнимо, поэтому проценты выше - это доли внутри
одной записи, а абсолютное сравнение сделано по процессорному времени JVM.
