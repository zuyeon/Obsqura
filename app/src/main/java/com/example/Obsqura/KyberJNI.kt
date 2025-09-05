package com.example.Obsqura

object KyberJNI {

    init {
        System.loadLibrary("kyberjni")
    }

    external fun encapsulate(publicKey: ByteArray): KyberResult
}
