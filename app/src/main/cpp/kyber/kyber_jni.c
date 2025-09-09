#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include "api.h"  // PQClean ML-KEM Kyber512 API

#define LOG_TAG "KYBER_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Kotlin:
 *   package com.example.obsqura
 *   object KyberJNI { @JvmStatic external fun encapsulate(pubKey: ByteArray): KyberResult }
 *
 * → JNI 심볼은 정확히 아래여야 합니다.
 *   Java_<package_underscored>_<Class>_<method>
 */
JNIEXPORT jobject JNICALL
Java_com_example_obsqura_KyberJNI_encapsulate(
        JNIEnv *env,
        jclass /* clazz: @JvmStatic 이므로 jclass 입니다 */,
        jbyteArray public_key_input) {

    // 1) 공개키 가져오기
    jsize pk_len = (*env)->GetArrayLength(env, public_key_input);
    jbyte *pk    = (*env)->GetByteArrayElements(env, public_key_input, NULL);
    if (pk == NULL) {
        LOGE("❌ GetByteArrayElements 실패");
        return NULL;
    }

    // Kyber512 공개키 길이 체크 (PQClean 상수)
    if (pk_len != PQCLEAN_MLKEM512_CLEAN_CRYPTO_PUBLICKEYBYTES) {
        LOGE("🚨 공개키 길이 오류: %d (예상=%d)",
             pk_len, PQCLEAN_MLKEM512_CLEAN_CRYPTO_PUBLICKEYBYTES);
        (*env)->ReleaseByteArrayElements(env, public_key_input, pk, JNI_ABORT);
        return NULL;
    }

    // 2) 캡슐화 수행
    uint8_t ct[PQCLEAN_MLKEM512_CLEAN_CRYPTO_CIPHERTEXTBYTES];
    uint8_t ss[PQCLEAN_MLKEM512_CLEAN_CRYPTO_BYTES];

    int rc = PQCLEAN_MLKEM512_CLEAN_crypto_kem_enc(ct, ss, (const uint8_t *)pk);
    (*env)->ReleaseByteArrayElements(env, public_key_input, pk, JNI_ABORT);

    if (rc != 0) {
        LOGE("❌ Kyber 캡슐화 실패 rc=%d", rc);
        return NULL;
    }

    // ✅ 성공 로그(공유키 첫 바이트로 sanity check)
    LOGI("✅ 캡슐화 성공! ssLen=%d, ctLen=%d, sharedKey[0]=0x%02X",
         (int)PQCLEAN_MLKEM512_CLEAN_CRYPTO_BYTES,
         (int)PQCLEAN_MLKEM512_CLEAN_CRYPTO_CIPHERTEXTBYTES,
         (unsigned)ss[0]);

    // 3) Java byte[] 만들기
    jbyteArray ciphertext_array = (*env)->NewByteArray(env, PQCLEAN_MLKEM512_CLEAN_CRYPTO_CIPHERTEXTBYTES);
    jbyteArray sharedkey_array  = (*env)->NewByteArray(env, PQCLEAN_MLKEM512_CLEAN_CRYPTO_BYTES);
    if (!ciphertext_array || !sharedkey_array) {
        LOGE("❌ byte[] 생성 실패");
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, ciphertext_array, 0,
                               PQCLEAN_MLKEM512_CLEAN_CRYPTO_CIPHERTEXTBYTES, (const jbyte*)ct);
    (*env)->SetByteArrayRegion(env, sharedkey_array, 0,
                               PQCLEAN_MLKEM512_CLEAN_CRYPTO_BYTES, (const jbyte*)ss);

    // 4) Kotlin의 KyberResult([B,[B) 객체 생성
    jclass resultClass = (*env)->FindClass(env, "com/example/obsqura/KyberResult"); // ← 정확히!
    if (!resultClass) {
        LOGE("❌ KyberResult 클래스 찾기 실패");
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, resultClass, "<init>", "([B[B)V");
    if (!ctor) {
        LOGE("❌ KyberResult 생성자 찾기 실패");
        return NULL;
    }

    jobject resultObject = (*env)->NewObject(env, resultClass, ctor, ciphertext_array, sharedkey_array);
    return resultObject;
}

#ifdef __cplusplus
} // extern "C"
#endif
