/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.impl.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import org.apache.hc.core5.annotation.NotThreadSafe;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http2.H2ConnectionException;
import org.apache.hc.core5.http2.H2CorruptFrameException;
import org.apache.hc.core5.http2.H2Error;
import org.apache.hc.core5.http2.frame.ByteBufferFrame;
import org.apache.hc.core5.http2.frame.Frame;
import org.apache.hc.core5.http2.frame.FrameConsts;
import org.apache.hc.core5.http2.frame.FrameFlag;
import org.apache.hc.core5.http2.impl.BasicHttp2TransportMetrics;
import org.apache.hc.core5.http2.io.Http2TransportMetrics;
import org.apache.hc.core5.util.Args;

/**
 * Frame input buffer for HTTP/2 non-blocking connections.
 *
 * @since 5.0
 */
@NotThreadSafe
public final class FrameInputBuffer {

    enum State { HEAD_EXPECTED, PAYLOAD_EXPECTED }

    private final BasicHttp2TransportMetrics metrics;
    private final int maxFramePayloadSize;
    private final byte[] bytes;
    private final ByteBuffer buffer;

    private State state;
    private int payloadLen;
    private int type;
    private int flags;
    private int streamId;

    FrameInputBuffer(final BasicHttp2TransportMetrics metrics, final int bufferLen, final int maxFramePayloadSize) {
        Args.notNull(metrics, "HTTP2 transport metrcis");
        Args.positive(maxFramePayloadSize, "Maximum payload size");
        this.metrics = metrics;
        this.maxFramePayloadSize = maxFramePayloadSize;
        this.bytes = new byte[bufferLen];
        this.buffer = ByteBuffer.wrap(bytes);
        this.buffer.flip();
        this.state = State.HEAD_EXPECTED;
    }

    public FrameInputBuffer(final BasicHttp2TransportMetrics metrics, final int maxFramePayloadSize) {
        this(metrics, FrameConsts.HEAD_LEN + maxFramePayloadSize, maxFramePayloadSize);
    }

    public FrameInputBuffer(final int maxFramePayloadSize) {
        this(new BasicHttp2TransportMetrics(), maxFramePayloadSize);
    }

    public Frame<ByteBuffer> read(final ReadableByteChannel channel) throws IOException {
        for (;;) {
            switch (state) {
                case HEAD_EXPECTED:
                    if (buffer.remaining() >= FrameConsts.HEAD_LEN) {
                        final int lengthAndType = buffer.getInt();
                        payloadLen = lengthAndType >> 8;
                        if (payloadLen > maxFramePayloadSize) {
                            throw new H2ConnectionException(H2Error.FRAME_SIZE_ERROR, "Frame size exceeds maximum");
                        }
                        type = lengthAndType & 0xff;
                        flags = buffer.get();
                        streamId = Math.abs(buffer.getInt());
                        state = State.PAYLOAD_EXPECTED;
                    } else {
                        break;
                    }
                case PAYLOAD_EXPECTED:
                    if (buffer.remaining() >= payloadLen) {
                        final ByteBuffer payload;
                        if ((flags & FrameFlag.PADDED.getValue()) == 0) {
                            if (payloadLen > 0) {
                                payload = ByteBuffer.wrap(bytes, buffer.position(), payloadLen);
                                buffer.position(buffer.position() + payloadLen);
                            } else {
                                payload = null;
                            }
                        } else {
                            if (payloadLen == 0) {
                                throw new H2ConnectionException(H2Error.PROTOCOL_ERROR, "Inconsistent padding");
                            }
                            final int padding = buffer.get();
                            if (payloadLen < padding + 1) {
                                throw new H2ConnectionException(H2Error.PROTOCOL_ERROR, "Inconsistent padding");
                            }
                            final int off = buffer.position();
                            final int len = payloadLen - padding - 1;
                            payload = ByteBuffer.wrap(bytes, off, len);
                            buffer.position(buffer.position() - 1 + payloadLen);
                        }
                        state = State.HEAD_EXPECTED;
                        metrics.incrementFramesTransferred();
                        return new ByteBufferFrame(type, flags & ~FrameFlag.PADDED.getValue(), streamId,
                                payload != null ? payload.asReadOnlyBuffer() : null);
                    }
            }
            if (buffer.hasRemaining()) {
                buffer.compact();
            } else {
                buffer.clear();
            }
            final int bytesRead = channel.read(buffer);
            buffer.flip();
            if (bytesRead > 0) {
                metrics.incrementBytesTransferred(bytesRead);
            }
            if (bytesRead == 0) {
                break;
            } else if (bytesRead < 0) {
                if (state != State.HEAD_EXPECTED || buffer.hasRemaining()) {
                    throw new H2CorruptFrameException("Corrupt or incomplete HTTP2 frame");
                } else {
                    throw new ConnectionClosedException("Connection closed");
                }
            }
        }
        return null;
    }

    public void reset() {
        buffer.compact();
        state = State.HEAD_EXPECTED;
    }

    public Http2TransportMetrics getMetrics() {
        return metrics;
    }

}