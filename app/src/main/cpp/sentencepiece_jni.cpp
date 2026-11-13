#include <jni.h>

#include <cstdint>
#include <exception>
#include <limits>
#include <new>
#include <string>
#include <vector>

#include "sentencepiece_processor.h"

namespace {

void Throw(JNIEnv* env, const char* class_name, const std::string& message) {
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
  }
}

void AppendUtf8(uint32_t code_point, std::string* output) {
  if (code_point <= 0x7f) {
    output->push_back(static_cast<char>(code_point));
  } else if (code_point <= 0x7ff) {
    output->push_back(static_cast<char>(0xc0 | (code_point >> 6)));
    output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else if (code_point <= 0xffff) {
    output->push_back(static_cast<char>(0xe0 | (code_point >> 12)));
    output->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else {
    output->push_back(static_cast<char>(0xf0 | (code_point >> 18)));
    output->push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
    output->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output->push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  }
}

std::string ToUtf8(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (chars == nullptr) return {};

  std::string output;
  output.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t code_point = chars[i];
    if (code_point >= 0xd800 && code_point <= 0xdbff && i + 1 < length &&
        chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
      code_point = 0x10000 + ((code_point - 0xd800) << 10) +
                   (chars[++i] - 0xdc00);
    } else if (code_point >= 0xd800 && code_point <= 0xdfff) {
      code_point = 0xfffd;
    }
    AppendUtf8(code_point, &output);
  }
  env->ReleaseStringChars(value, chars);
  return output;
}

std::vector<jchar> ToUtf16(const std::string& value) {
  std::vector<jchar> output;
  output.reserve(value.size());
  const auto* bytes = reinterpret_cast<const unsigned char*>(value.data());
  size_t i = 0;
  while (i < value.size()) {
    uint32_t code_point = 0xfffd;
    const unsigned char first = bytes[i++];
    if (first <= 0x7f) {
      code_point = first;
    } else if ((first & 0xe0) == 0xc0 && i < value.size()) {
      code_point = ((first & 0x1f) << 6) | (bytes[i++] & 0x3f);
    } else if ((first & 0xf0) == 0xe0 && i + 1 < value.size()) {
      code_point = ((first & 0x0f) << 12) | ((bytes[i] & 0x3f) << 6) |
                   (bytes[i + 1] & 0x3f);
      i += 2;
    } else if ((first & 0xf8) == 0xf0 && i + 2 < value.size()) {
      code_point = ((first & 0x07) << 18) | ((bytes[i] & 0x3f) << 12) |
                   ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f);
      i += 3;
    }
    if (code_point <= 0xffff) {
      output.push_back(static_cast<jchar>(code_point));
    } else if (code_point <= 0x10ffff) {
      code_point -= 0x10000;
      output.push_back(static_cast<jchar>(0xd800 + (code_point >> 10)));
      output.push_back(static_cast<jchar>(0xdc00 + (code_point & 0x3ff)));
    } else {
      output.push_back(static_cast<jchar>(0xfffd));
    }
  }
  return output;
}

sentencepiece::SentencePieceProcessor* Processor(jlong handle) {
  return reinterpret_cast<sentencepiece::SentencePieceProcessor*>(
      static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_tasksync_ai_TaskSyncTokenizer_nativeCreate(
    JNIEnv* env, jobject /* this */, jstring model_path) {
  try {
    auto* processor = new (std::nothrow) sentencepiece::SentencePieceProcessor();
    if (processor == nullptr) {
      Throw(env, "java/lang/OutOfMemoryError", "Could not allocate the tokenizer.");
      return 0;
    }
    const auto status = processor->Load(ToUtf8(env, model_path));
    if (!status.ok()) {
      const std::string message = status.ToString();
      delete processor;
      Throw(env, "java/lang/IllegalStateException", message);
      return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(processor));
  } catch (const std::exception& error) {
    Throw(env, "java/lang/IllegalStateException", error.what());
    return 0;
  }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_tasksync_ai_TaskSyncTokenizer_nativeEncode(
    JNIEnv* env, jobject /* this */, jlong handle, jstring text) {
  try {
    auto* processor = Processor(handle);
    if (processor == nullptr) {
      Throw(env, "java/lang/IllegalStateException", "Tokenizer is closed.");
      return nullptr;
    }
    std::vector<int> ids;
    const auto status = processor->Encode(ToUtf8(env, text), &ids);
    if (!status.ok()) {
      Throw(env, "java/lang/IllegalArgumentException", status.ToString());
      return nullptr;
    }
    if (ids.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
      Throw(env, "java/lang/IllegalArgumentException", "Input produced too many tokens.");
      return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (result != nullptr && !ids.empty()) {
      env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()), ids.data());
    }
    return result;
  } catch (const std::exception& error) {
    Throw(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_tasksync_ai_TaskSyncTokenizer_nativeDecode(
    JNIEnv* env, jobject /* this */, jlong handle, jintArray token_ids) {
  try {
    auto* processor = Processor(handle);
    if (processor == nullptr) {
      Throw(env, "java/lang/IllegalStateException", "Tokenizer is closed.");
      return nullptr;
    }
    const jsize length = env->GetArrayLength(token_ids);
    std::vector<jint> raw(static_cast<size_t>(length));
    if (length > 0) env->GetIntArrayRegion(token_ids, 0, length, raw.data());
    std::vector<int> ids(raw.begin(), raw.end());
    std::string decoded;
    const auto status = processor->Decode(ids, &decoded);
    if (!status.ok()) {
      Throw(env, "java/lang/IllegalArgumentException", status.ToString());
      return nullptr;
    }
    const auto utf16 = ToUtf16(decoded);
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
  } catch (const std::exception& error) {
    Throw(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_tasksync_ai_TaskSyncTokenizer_nativeClose(
    JNIEnv* /* env */, jobject /* this */, jlong handle) {
  delete Processor(handle);
}

