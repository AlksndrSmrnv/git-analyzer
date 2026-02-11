# Git Test Creation Counter (Kotlin/JVM)

CLI-утилита для подсчета, кто сколько **новых** JUnit 5 тестов (`@Test`) добавил в Git-репозиторий по истории коммитов.

## Сборка

```bash
mvn -q -DskipTests package
```

## Запуск через Maven

За последние 7 дней:

```bash
mvn -q exec:java -Dexec.mainClass=com.example.gitcounter.TestCreationCounter -Dexec.args="--since-days 7"
```

Вся история:

```bash
mvn -q exec:java -Dexec.mainClass=com.example.gitcounter.TestCreationCounter -Dexec.args="--all --branch main"
```

С verbose-логами:

```bash
mvn -q exec:java -Dexec.mainClass=com.example.gitcounter.TestCreationCounter -Dexec.args="--since-days 7 --verbose"
```

## Запуск через java -cp

```bash
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" com.example.gitcounter.TestCreationCounter --since-days 7
```

## Поддерживаемые аргументы

- `--all` - вся история
- `--since-days N` - последние `N` дней (по умолчанию `7`)
- `--branch <name>` - ветка/ref (по умолчанию `HEAD`)
- `--verbose` - подробные логи
- `--help` - справка
