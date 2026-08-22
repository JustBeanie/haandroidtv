# Contributing

Use JDK 17 and Android Studio or the Gradle wrapper. Run `./gradlew check`
before opening a pull request. Do not add Home Assistant URLs, tokens, alarm
codes, keystores, or generated APKs to the repository.

The project is intentionally D-pad-first. New controls must preserve logical
focus traversal, text state descriptions, and a safe offline failure mode.
