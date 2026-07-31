package com.myanmar.warpvpn

object NativeUtils {

    init {
        System.loadLibrary("warpvpn")
    }
    external fun getCustomApiUrl(): String
    external fun getCfApiBase1(): String
    external fun getCfApiBase2(): String
    external fun getCfApiBase3(): String
}
