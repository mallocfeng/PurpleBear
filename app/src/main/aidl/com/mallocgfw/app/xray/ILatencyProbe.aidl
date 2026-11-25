package com.mallocgfw.app.xray;

/**
 * IPC to the :probe process, which runs libXray on its own so latency probes
 * work while the VPN's Xray instance runs in the main process.
 */
interface ILatencyProbe {
    /** A libXray invoke request (JSON) and its response (JSON). */
    String invoke(String requestJson);
}
