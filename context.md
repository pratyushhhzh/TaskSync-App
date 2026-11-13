# TaskSync — Project Context and Handoff

_Last reviewed: 24 October 2026_

This document is a compact handoff for someone new to TaskSync. It describes what is present in the repository, what the app is meant to become, and what remains. Treat **Implemented** as code that exists in this repository; **Planned** means a product goal that is not complete just because a screen, dependency, or placeholder exists.

## 1. What TaskSync is

TaskSync is an Android task manager intended to grow into a personal task assistant. Its current voice stage is **speech-to-text only**: capture the user's complete utterance, let them review and edit it, and confirm the transcript. A separate brain stage for summarizing and distributing tasks is future work.

The app is local-first. Tasks and projects are stored in the device's Room database. The app does not use accounts or remote task sync.

## 2. Technology and architecture verified in this repository

- Native Android application written in **Kotlin**.
- UI built with **Jetpack Compose** and Material 3.
- Navigation Compose provides the bottom-tab navigation.
- **Room** is the local persistence layer, with DAO, entity, database, and repository layers.
- Android's `SpeechRecognizer` service supplies speech-to-text through `SpeechCaptureController`; the active app does not interpret transcripts or create tasks from voice.
- Android alarm/notification components schedule task reminders.
- Gradle Kotlin DSL build files; current app configuration uses `compileSdk 35`, `targetSdk 35`, and `minSdk 26`.

Main code areas:

- `app/src/main/java/com/example/tasksync/ui/` — Compose screens, navigation, view models, and voice UI.
- `app/src/main/java/com/example/tasksync/data/local/` — Room database, DAOs, and entities.
- `app/src/main/java/com/example/tasksync/data/repository/` — task and project persistence operations.
- `app/src/main/java/com/example/tasksync/data/reminder/` — Android reminder scheduling and delivery.
- `app/src/main/java/com/example/tasksync/ui/assistant/` — speech capture and transcript review UI.
- `backup/local-voice-parser/` — archived local parser and its tests; not compiled or connected to the active voice flow.

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

Current path: Android speech recognition → accumulated transcript → editable review → explicit transcript confirmation. The flow stops there; it does not call the task parser or write tasks.

- Speech capture listens across successive recognition segments until the user explicitly stops. The transcript is editable and confirmed before the dialog ends. Speech-to-text quality and service availability depend on Android/device recognition services; this is not a custom offline speech model.
- The old local interpreter is archived in `backup/local-voice-parser/`; its sources and tests are not in `app/src` and are not active.
- Confirming a transcript does not create, update, complete, or delete tasks. Voice-created task behavior is intentionally unavailable until a separate brain stage is designed.

## 4. Voice-brain design and current phase status

The brain stage is not implemented. Keep it separate from speech capture: the current stage produces a confirmed text transcript, and a future stage may summarize it, split tasks, extract details, and propose actions.

Pipeline responsibilities to keep distinct:

1. **Speech capture/transcription (active)** — Android recognition service produces text until the user stops.
2. **Transcript review (active)** — user can edit and confirm the complete transcript.
3. **Brain and action proposal (future)** — summarize, divide tasks, extract titles/details, resolve references, and prepare proposed actions.
4. **Execution (future voice stage)** — only after the user reviews and confirms proposed task actions, call the existing repository to write to Room.

### Current implementation status

- Speech capture accumulates segments, shows partial text, and provides editable final transcript confirmation.
- The rule-based parser and its regression tests are archived as reference only.
- The task summarization/distribution model, proposal review, and voice-driven task execution stage are not implemented in the active flow.

### Still to achieve for the voice MVP

1. **Validate speech-to-text on real devices.** Check long pauses, segment stitching, service errors, permissions, and English (India) recognition.
2. **Choose the future brain approach.** Define model/provider, cost, privacy, offline expectations, and app-size limits before implementation.
3. **Design transcript-to-proposal contract.** Specify input context, output schema, uncertainty behavior, and how the user reviews proposed task actions.
4. **Build an evaluation set.** Record representative transcripts and expected summaries/task splits, including ambiguous and adversarial instructions.
5. **Connect execution only after confirmation.** Keep task writes behind the established review and confirmation boundary.

## 5. Broader product goals and sequencing

### Near-term focus

- Finish and polish the Android app's personal task manager and project section.
- Finish the speech-to-text stage and preserve the complete confirmed transcript.
- Design the separate brain stage before connecting voice to task creation or updates.

### Later planned work

- Project collaboration: shared project tasks, team membership/permissions, and synchronization for project data.
- Account and cross-device behavior, once the product decides which personal data should sync. The prior direction was to prioritize project/team data for initial collaboration and consider personal-task sync in a later release; this is a product plan, not implemented behavior.
- Revisit model-based understanding only after evaluation and device feasibility have been measured.


## 6. Data model and local persistence

Room entities currently include:

- `TaskEntity`: task owner marker, optional project link, title/description, status, due date/time, reminder flag, priority, assignee field, recurrence rule, and timestamps.
- `ProjectEntity`: name, description, optional deadline, and creation time.
- `SubtaskEntity`: task link, title, completion state, sort order, and creation time.

The local database is `tasksync_database`, currently schema version 4, with migrations through version 4. The repository uses a local user marker (`local_user`). The task and project data currently remain on the device; do not promise backup, cross-device transfer, or collaboration sync without verifying a later implementation.

## 7. Important limitations and cautions for new contributors

- Keep speech capture/transcript review separate from the future brain and task-action layers.
- The archived local parser is reference material, not an active dependency; do not restore it into `app/src` unless requested.
- No voice transcript is persisted as a task in the current stage.
- Keep Room writes, reminder scheduling, and task changes out of speech capture.
- Avoid external AI APIs, paid services, or new dependencies unless the product direction explicitly changes.
- The repository currently contains local development changes. Check `git status` before editing and do not discard another contributor's work.

## 8. Useful entry points

| Concern | Main files |
|---|---|
| App navigation and main shell | `ui/main/MainScreen.kt`, `ui/navigation/AppNavigation.kt`, `ui/navigation/Screen.kt` |
| Task and calendar UI | `ui/tasks/TasksScreen.kt`, `ui/tasks/InboxScreen.kt`, `ui/tasks/CalendarScreen.kt`, `ui/tasks/TaskDetailBottomSheet.kt` |
| Task/project Kanban | `ui/projects/ProjectsScreen.kt`, `ui/projects/ProjectKanbanBoard.kt`, `ui/projects/ProjectCreationDialog.kt` |
| Task entities/database/DAO | `data/local/entity/TaskEntity.kt`, `ProjectEntity.kt`, `SubtaskEntity.kt`, `data/local/AppDatabase.kt`, `data/local/dao/` |
| Persistence | `data/repository/TaskRepository.kt`, `ProjectRepository.kt`, `ui/tasks/TaskListViewModel.kt` |
| Active voice capture/transcript review | `ui/assistant/SpeechCaptureController.kt`, `VoiceTaskAssistantDialog.kt` |
| Archived local parser | `backup/local-voice-parser/` |
| Reminders | `data/reminder/TaskReminderScheduler.kt`, `TaskReminderReceiver.kt` |

## 9. Current one-sentence status

TaskSync is a local-first Android to-do/project app whose active voice flow captures and confirms editable text only; the task-understanding brain and voice-driven task actions are future work.
