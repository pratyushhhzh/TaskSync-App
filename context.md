# TaskSync — Project Context and Handoff

_Last reviewed: 24 October 2026_

This document is a compact handoff for someone new to TaskSync. It describes what is present in the repository, what the app is meant to become, and what remains. Treat **Implemented** as code that exists in this repository; **Planned** means a product goal that is not complete just because a screen, dependency, or placeholder exists.

## 1. What TaskSync is

TaskSync is an Android task manager intended to grow into a personal task assistant. Its first useful product is a conventional to-do app: users can create and organize tasks, set due dates and times, priorities, reminders, and recurrence, and keep project work grouped. The main differentiating MVP goal is a **voice assistant** that turns natural speech into accurate task actions, including multiple tasks and changes to existing tasks, while asking the user to review uncertain interpretations before saving.

The app is currently local-first. Tasks and projects are stored in the device's Room database. Supabase libraries/configuration and schema/setup artifacts exist, but the current task flow does not sync task or project data to Supabase.

## 2. Technology and architecture verified in this repository

- Native Android application written in **Kotlin**.
- UI built with **Jetpack Compose** and Material 3.
- Navigation Compose provides the bottom-tab navigation.
- **Room** is the local persistence layer, with DAO, entity, database, and repository layers.
- Android's `SpeechRecognizer` service supplies speech-to-text through `SpeechCaptureController`; the app's command interpretation is local Kotlin rule-based code.
- Android alarm/notification components schedule task reminders.
- Gradle Kotlin DSL build files; current app configuration uses `compileSdk 35`, `targetSdk 35`, and `minSdk 26`.
- Supabase Auth and Ktor dependencies/configuration are present. `AuthRepository` is currently a nonfunctional local-phase stub; the presence of those dependencies or login/register screens must not be described as finished authentication or data syncing.

Main code areas:

- `app/src/main/java/com/example/tasksync/ui/` — Compose screens, navigation, view models, and voice UI.
- `app/src/main/java/com/example/tasksync/data/local/` — Room database, DAOs, and entities.
- `app/src/main/java/com/example/tasksync/data/repository/` — task and project persistence operations.
- `app/src/main/java/com/example/tasksync/data/reminder/` — Android reminder scheduling and delivery.
- `app/src/main/java/com/example/tasksync/ui/assistant/` — speech capture, intent classification, local voice interpreter, proposal UI.
- `app/src/test/java/com/example/tasksync/VoiceTaskInterpreterTest.kt` — interpreter unit and regression tests.

## 3. What the app has today

### Task manager

- Main navigation tabs: **Tasks**, **Inbox**, **Calendar**, and **Projects**.
- Create and edit local tasks with a title, optional description, deadline date, due time, priority (1–4, where 1 is highest and 4 is no priority), reminder setting, and recurrence rule.
- Complete, reopen, edit, and delete tasks through the task UI and task repository flows.
- Tasks are ordered by status/completion, priority, due date, and creation time in the Room DAO.
- Task detail UI supports reminders and recurrence. The repository schedules/cancels notifications as tasks change. Completing a recurring task can create its next occurrence.
- Calendar view displays a month, marks days with tasks, and lists tasks for a selected date.
- Inbox is available for unplanned/no-deadline tasks.
- Tasks associated with a project show project context in the task UI.

### Projects

- Create projects with a name, optional description, and optional deadline.
- Project tasks are stored locally and associated with the project.
- Each project has a Kanban board with **To do**, **In progress**, and **Done** lanes. Tasks can be moved between statuses.
- The board is intended to be the project-specific Kanban experience; the app's ordinary task list remains the personal task manager.
- Project collaboration and cross-device team synchronization are not complete.

### Voice assistant

Current path: Android speech recognition → transcript → `VoiceTaskBrain` / `VoiceTaskInterpreter` → structured proposals or recognized existing-task actions → review dialog → explicit Apply → view model/repository writes to Room.

- Speech capture can listen across successive recognition segments until the user explicitly stops. Speech-to-text quality and service availability depend on Android/device recognition services; this is not a custom offline speech model.
- The interpreter is **local, deterministic, rule-based Kotlin**, not a trained AI model, language model, or self-learning system. There is no external AI API or paid inference service in this flow.
- Create proposals can extract concise titles and explicit dates, times, priorities, recurrence, and reminder intent. Relative dates use an injectable `Clock` and its timezone for deterministic interpretation.
- Multi-task transcripts are segmented into individual proposals; recognized filler, some speech restarts, and ambiguous “from next week” date language have regression coverage.
- The review dialog shows proposed operations and supports manual editing before the user applies them. Interpretation itself does not write to Room.
- Existing rule support also includes a limited set of task commands such as completion/reopen/delete, subtasks, and updates to matched tasks' date/time, priority, recurrence, and description. This is pattern-based support and must not be mistaken for robust conversational understanding.
- A command such as a medication reminder with an explicit time can propose a reminder at that time without inventing a due date. If the user says “from next week,” the interpreter should leave the date blank and request review rather than silently treating it as a deadline.

## 4. Voice-brain design and current phase status

The planned “brain” is currently a **language-understanding pipeline**, not a neural model. The near-term strategy is to improve the on-device rule-based interpreter because TaskSync must work for ordinary/older Android phones and a small initial user group without a paid service or cloud dependency.

Pipeline responsibilities to keep distinct:

1. **Speech capture/transcription** — Android recognition service produces text.
2. **Transcript cleanup** — normalize punctuation, common recognition variants, and disfluencies without changing meaning.
3. **Intent classification** — determine create, update/reschedule, complete, delete, query, etc.
4. **Clause segmentation** — split multiple task statements while preserving which date/time/priority belongs to which task; avoid splitting titles or speech self-corrections.
5. **Title and field extraction** — produce concise titles and optional due date, time, priority, recurrence, reminder, and other supported fields.
6. **Existing-task resolution** — match update/completion commands to saved tasks and refuse to guess when multiple matches are plausible.
7. **Validation and review plan** — represent uncertainty explicitly and show proposed changes for user correction/confirmation.
8. **Execution** — only after confirmation, call the existing view-model/repository flow to write the action to Room and schedule/cancel reminders.

### Completed work in this direction

- A structured proposal/action representation exists and keeps parsing separate from persistence.
- Single-task parsing and the initial multi-task parsing phases exist.
- The parser has rule support for common relative dates, explicit times, priorities, reminders, recurrence, and several task commands.
- Recent real-device reproduction was traced and fixed: dangling conversational filler is removed at clause edges; repeated obligation prefixes are collapsed; medication reminder phrasing is cleaned; “from next week” is not used as a guessed due date and is flagged for confirmation.
- Unit regression tests cover the supplied real transcript plus medication reminders, self-correction, filler, ambiguous date language, ordinary task parsing, multi-task metadata, and injected-clock/timezone behavior.
- The current requested verification completed successfully: `:app:testDebugUnitTest :app:assembleDebug` — **BUILD SUCCESSFUL**, including 19 unit tests and the debug APK build. This is code/build verification, not a claim that all voice inputs work perfectly on physical devices.

### Still to achieve for the voice MVP

1. **Reliable task reference resolution.** Improve matching of natural references (“move my football game”) to existing tasks, including typo tolerance, similar names, and explicit ambiguity when more than one task matches.
2. **Safer update planning.** Ensure updates never fall through into task creation; require a confident match before changing, completing, reopening, or deleting a task. Keep destructive actions especially clear in review.
3. **Better natural-language extraction.** Expand titles and metadata rules for common phrasing, corrections, lists, conjunctions, and speech-recognition errors without over-stripping meaningful task words.
4. **Clarification interaction.** Let the assistant ask for missing/unclear details (date, time, priority, or which task), retain context for the answer, then update the proposal before saving. This follow-up conversational loop is not complete.
5. **Broader evaluation set.** Build a maintained corpus of representative transcripts and expected structured operations, including hard negatives and ambiguous examples. Track exact title/date/time/priority/action accuracy and regression results rather than relying on a few examples.
6. **Device testing.** Re-test the whole flow on physical Android devices: microphone permission, long pauses, recognition segment boundaries, repeated listening, service errors, review edits, Apply/Undo, reminders, and date/time behavior. Unit tests cannot verify OEM speech-service behavior.
7. **Graceful failure and user trust.** Show the heard transcript, identify what is uncertain, never fabricate metadata, and ensure no operation is persisted before confirmation.
8. **Decide later whether to train or ship a model.** There is no custom-trained model now. First collect consented, privacy-conscious examples and establish an accuracy baseline; only then compare improved rules with an optional on-device model. Any model must meet offline, device compatibility, size, privacy, and accuracy needs before replacing the rules.

## 5. Broader product goals and sequencing

### Near-term focus

- Finish and polish the Android app's personal task manager and project section.
- Improve the voice MVP until it reliably creates multiple tasks and safely updates existing ones, with confirmation and correction.
- Keep voice interpretation local and available without a subscription or server inference requirement.

### Later planned work

- Project collaboration: shared project tasks, team membership/permissions, and synchronization for project data.
- Account and cross-device behavior, once the product decides which personal data should sync. The prior direction was to prioritize project/team data for initial collaboration and consider personal-task sync in a later release; this is a product plan, not implemented behavior.
- Revisit model-based understanding only after evaluation and device feasibility have been measured.

No Supabase project schema, auth configuration, or included setup guide alone means collaboration is operational. Verify actual app integration and end-to-end sync before marking that milestone complete.

## 6. Data model and local persistence

Room entities currently include:

- `TaskEntity`: task owner marker, optional project link, title/description, status, due date/time, reminder flag, priority, assignee field, recurrence rule, and timestamps.
- `ProjectEntity`: name, description, optional deadline, and creation time.
- `SubtaskEntity`: task link, title, completion state, sort order, and creation time.

The local database is `tasksync_database`, currently schema version 4, with migrations through version 4. The repository uses a local user marker (`local_user`). The task and project data currently remain on the device; do not promise backup, cross-device transfer, or collaboration sync without verifying a later implementation.

## 7. Important limitations and cautions for new contributors

- The rule parser is not general intelligence. It will have edge cases; add regression tests for every real failure before changing heuristics.
- Android speech recognition is separate from the local interpreter. A transcript that is already wrong can affect the result, but the supplied recent failure was reproduced from the exact transcript and its parser errors were in TaskSync's own code.
- Avoid overlapping regex rules that steal dates from another task or create extra tasks from one sentence. Preserve dates/times with their intended clause.
- Missing values are meaningful. Never turn an absent date, time, or priority into a guessed value. Priority 4 is the existing database/UI default representing “none”; the proposal's nullable priority is the signal that the user did not state one.
- Keep parsing pure: no Room writes, reminder scheduling, or destructive operations from interpretation. Only the confirmed action execution path may mutate data.
- Preserve the existing Compose review and manual-edit flow unless a change is necessary for a clearly specified interaction.
- Avoid external AI APIs, paid services, or new dependencies unless the product direction explicitly changes.
- The repository currently contains local development changes. Check `git status` before editing and do not discard another contributor's work.

## 8. Useful entry points

| Concern | Main files |
|---|---|
| App navigation and main shell | `ui/main/MainScreen.kt`, `ui/navigation/AppNavigation.kt`, `ui/navigation/Screen.kt` |
| Task and calendar UI | `ui/tasks/TasksScreen.kt`, `ui/tasks/InboxScreen.kt`, `ui/tasks/CalendarScreen.kt`, `ui/tasks/TaskDetailBottomSheet.kt` |
| Task/project Kanban | `ui/projects/ProjectsScreen.kt`, `ui/projects/ProjectKanbanBoard.kt`, `ui/projects/ProjectCreationDialog.kt` |
| Task entities/database/DAO | `data/local/entity/TaskEntity.kt`, `ProjectEntity.kt`, `SubtaskEntity.kt`, `data/local/AppDatabase.kt`, `data/local/dao/` |
| Persistence and confirmed voice execution | `data/repository/TaskRepository.kt`, `ProjectRepository.kt`, `ui/tasks/TaskListViewModel.kt` |
| Voice capture and interpretation | `ui/assistant/SpeechCaptureController.kt`, `VoiceIntentClassifier.kt`, `VoiceTaskBrain.kt`, `VoiceTaskInterpreter.kt`, `VoiceTaskAssistantDialog.kt` |
| Reminders | `data/reminder/TaskReminderScheduler.kt`, `TaskReminderReceiver.kt` |
| Interpreter tests | `app/src/test/java/com/example/tasksync/VoiceTaskInterpreterTest.kt` |
| Supabase setup artifact | `SUPABASE_SETUP.md`, `supabase/` (setup/schema artifacts only; not proof of active sync) |

## 9. Current one-sentence status

TaskSync is a runnable local-first Android to-do/project app with a rule-based, review-before-save voice assistant and reminder support; the main unfinished product work is making multi-task voice interpretation and existing-task updates trustworthy across varied real speech, then adding the clarification loop and later project collaboration/sync.
