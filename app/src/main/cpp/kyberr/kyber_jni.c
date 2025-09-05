#include <jni.h>
#include <string.h>
#include <android/log.h>
#include "kem.h"
#include "api.h"

#define LOG_TAG "KYBER_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JNI 함수: 공개키 기반으로 캡슐화 수행
JNIEXPORT jobject JNICALL Java_com_example_blecommunicator_KyberJNI_encapsulate(
        JNIEnv *env,
        jobject thiz,
        jbyteArray public_key_input) {

    // 1. Java → C: 공개키 byte 배열 가져오기
    jsize pk_len = (*env)->GetArrayLength(env, public_key_input);
    jbyte *pk = (*env)->GetByteArrayElements(env, public_key_input, NULL);

    if (pk_len != CRYPTO_PUBLICKEYBYTES) {
        LOGE("🚨 공개키 길이 오류: %d (예상=%d)", pk_len, CRYPTO_PUBLICKEYBYTES);
        return NULL;
    }

    // 2. 캡슐화 실행
    uint8_t ct[CRYPTO_CIPHERTEXTBYTES];
    uint8_t ss[CRYPTO_BYTES];

    int result = crypto_kem_enc(ct, ss, (const uint8_t *)pk);

    if (result != 0) {
        LOGE("❌ Kyber 캡슐화 실패");
        return NULL;
    }

    // 3. Java byte[]로 변환
    jbyteArray ciphertext_array = (*env)->NewByteArray(env, CRYPTO_CIPHERTEXTBYTES);
    jbyteArray sharedkey_array = (*env)->NewByteArray(env, CRYPTO_BYTES);
    (*env)->SetByteArrayRegion(env, ciphertext_array, 0, CRYPTO_CIPHERTEXTBYTES, (jbyte *)ct);
    (*env)->SetByteArrayRegion(env, sharedkey_array, 0, CRYPTO_BYTES, (jbyte *)ss);

    // 4. Pair<byte[], byte[]> 형태로 반환 (Java의 KyberResult 클래스 필요)
    jclass resultClass = (*env)->FindClass(env, "com/example/Obsqura/KyberResult");
    if (resultClass == NULL) {
        LOGE("❌ KyberResult 클래스 찾기 실패");
        return NULL;
    }

    jmethodID constructor = (*env)->GetMethodID(env, resultClass, "<init>", "([B[B)V");
    if (constructor == NULL) {
        LOGE("❌ KyberResult 생성자 찾기 실패");
        return NULL;
    }

    jobject resultObject = (*env)->NewObject(env, resultClass, constructor, ciphertext_array, sharedkey_array);
    LOGI("✅ 캡슐화 성공!");

    // 5. 메모리 정리
    (*env)->ReleaseByteArrayElements(env, public_key_input, pk, 0);

    return resultObject;
}
