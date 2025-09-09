package com.example.obsqura
object KyberJNI {
    init { System.loadLibrary("kyberjni") }
    @JvmStatic external fun encapsulate(pubKey: ByteArray): KyberResult
}
