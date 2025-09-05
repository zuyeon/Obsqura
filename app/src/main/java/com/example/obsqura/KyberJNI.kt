package com.example.obsqura

object KyberJNI {

    init {
        System.loadLibrary("kyberjni")
    }

    external fun encapsulate(publicKey: ByteArray): KyberResult
}
