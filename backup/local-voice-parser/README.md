# Archived local voice parser

This folder preserves the retired rule-based voice interpretation stage. These Kotlin files are intentionally outside `app/src` and are not compiled into TaskSync.

The active voice flow now captures speech, lets the user edit and confirm the transcript, and stops there. It does not split instructions, summarize tasks, extract task fields, or write tasks.

For a future brain stage, use the confirmed transcript as input and implement that stage separately. The archived parser and its tests are reference material only; they are not wired into the app.
