package com.mallocgfw.app.tailscale;

/** IPC boundary that keeps Tailscale's Go runtime out of the Xray process. */
interface ITailscaleBridge {
    String start(String config);
    String status();
    String stop();
    String setExitNode(String nodeId);
    String logout(String stateDir);
}
