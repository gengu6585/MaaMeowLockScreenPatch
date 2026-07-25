package com.tinkerlab.maameowpatch.http;

import java.io.IOException;
import java.io.OutputStream;

/** 向客户端持续写 SSE 帧。 */
public interface SseProducer {
    void writeTo(OutputStream out) throws IOException;
}
