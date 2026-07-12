package com.mallocgfw.app.tailscale

/** Native JNI facade. The Go bridge owns no Android VpnService. */
object TailscaleNative {
    init {
        System.loadLibrary("tailscalebridgejni")
    }

    external fun start(config: String): String
    external fun status(): String
    external fun stop(): String
    external fun setExitNode(nodeId: String): String
    external fun logout(stateDir: String): String
}
