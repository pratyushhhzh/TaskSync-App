# Archived TaskSync legacy voice assistant

This directory preserves the retired local ONNX interpretation and review workflow, outside Android source/build paths. Android SpeechRecognizer remains active in the app source separately.

Contents:
- app-src-main/assets-model: bundled ONNX encoder/decoder and tokenizer assets
- app-src-main/java-ai: TaskSyncModel, TaskSyncTokenizer, ModelAssetStore, TaskSyncInterpreter, VoiceActionParser
- app-src-main/VoiceTaskAssistantDialog.kt: old speech, interpretation and action-review UI
- app-src-main: SentencePiece JNI bridge, CMake build definition, and vendored SentencePiece source
- app-src-test and app-src-androidTest: parser/model tests and fixtures

The retired code depended on ONNX Runtime Android, Android NDK/CMake, and SentencePiece compiled through JNI. It also used a voice-only batch application method in TaskListViewModel; that method is removed from active code and not archived as an execution path.
