# Contributing

Use JDK 17 and Android Studio or the Gradle wrapper. Run `./gradlew check`
before opening a pull request. Do not add Home Assistant URLs, tokens, alarm
codes, keystores, or generated APKs to the repository.

The project is intentionally D-pad-first. New controls must preserve logical
focus traversal, text state descriptions, and a safe offline failure mode.

## Using AI tools

You're welcome to use AI assistants, code completion, or any other tools to
help write your contribution. Disclosure is optional. You are responsible for
everything you submit: make sure you understand it, that it's tested, and that
you have the right to contribute it. Contributions are reviewed on quality,
not on how they were produced.

A few limits keep reviews sane:

- One focused change per pull request. For anything large, open an issue
  first so we can agree on the approach, then send it in reviewable pieces.
- Be ready to explain every change and why it's needed.
- Don't touch unrelated code, reformat files you didn't change, or weaken
  tests to make them pass.
- Automated pull requests, issues, and comments need a human who has read
  them.

Oversized or unexplained changes may be closed without a full review.

## Code of Conduct

Everyone taking part in this project is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).
