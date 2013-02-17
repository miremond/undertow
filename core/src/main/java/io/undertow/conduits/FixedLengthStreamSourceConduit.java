/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2012 Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.conduits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

import static java.lang.Math.min;
import static org.xnio.Bits.allAreClear;
import static org.xnio.Bits.allAreSet;
import static org.xnio.Bits.anyAreSet;
import static org.xnio.Bits.longBitMask;

/**
 * A channel which reads data of a fixed length and calls a finish listener.  When the finish listener is called,
 * it should examine the result of {@link #getRemaining()} to see if more bytes were pending when the channel was
 * closed.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
/*
 * Implementation notes
 * --------------------
 * The {@code exhausted} flag is set once a method returns -1 and signifies that the read listener should no longer be
 * called.  The {@code finishListener} is called when remaining is reduced to 0 or when the channel is closed explicitly.
 * If there are 0 remaining bytes but {@code FLAG_FINISHED} has not yet been set, the channel is considered "ready" until
 * the EOF -1 value is read or the channel is closed.  Since this is a half-duplex channel, shutting down reads is
 * identical to closing the channel.
 */
public final class FixedLengthStreamSourceConduit  extends AbstractStreamSourceConduit<StreamSourceConduit> {

    private final ConduitListener<? super FixedLengthStreamSourceConduit> finishListener;

    @SuppressWarnings("unused")
    private long state;

    private static final long FLAG_CLOSED = 1L << 63L;
    private static final long FLAG_FINISHED = 1L << 62L;
    private static final long MASK_COUNT = longBitMask(0, 61);

    /**
     * Construct a new instance.  The given listener is called once all the bytes are read from the stream
     * <b>or</b> the stream is closed.  This listener should cause the remaining data to be drained from the
     * underlying stream via the {@link #drain()} method if the underlying stream is to be reused.
     * <p/>
     * Calling this constructor will replace the read listener of the underlying channel.  The listener should be
     * restored from the {@code finishListener} object.  The underlying stream should not be closed while this wrapper
     * stream is active.
     *
     * @param next       the stream source channel to read from
     * @param contentLength  the amount of content to read
     * @param finishListener the listener to call once the stream is exhausted or closed
     */
    public FixedLengthStreamSourceConduit(final StreamSourceConduit next, final long contentLength, final ConduitListener<? super FixedLengthStreamSourceConduit> finishListener) {
        super(next);
        this.finishListener = finishListener;
        if (contentLength < 0L) {
            throw new IllegalArgumentException("Content length must be greater than or equal to zero");
        } else if (contentLength > MASK_COUNT) {
            throw new IllegalArgumentException("Content length is too long");
        }
        state = contentLength;
    }

    public long transferTo(final long position, final long count, final FileChannel target) throws IOException {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSED | FLAG_FINISHED) || allAreClear(val, MASK_COUNT)) {
            return 0L;
        }
        long res = 0L;
        try {
            return res = next.transferTo(position, min(count, val), target);
        } finally {
            exitRead(res);
        }
    }

    public long transferTo(final long count, final ByteBuffer throughBuffer, final StreamSinkChannel target) throws IOException {
        if (count == 0L) {
            return 0L;
        }
        long val = state;
        if (anyAreSet(val, FLAG_CLOSED | FLAG_FINISHED) || allAreClear(val, MASK_COUNT)) {
            return -1;
        }
        long res = 0L;
        try {
            if (allAreSet(val, FLAG_CLOSED) || val == 0L) {
                return -1L;
            }
            return res = next.transferTo(min(count, val), throughBuffer, target);
        } finally {
            exitRead(res == -1L ? val & MASK_COUNT : res);
        }
    }

    public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
        if (length == 0) {
            return 0L;
        } else if (length == 1) {
            return read(dsts[offset]);
        }
        long val = state;
        if (allAreSet(val, FLAG_CLOSED) || allAreClear(val, MASK_COUNT)) {
            return -1;
        }
        long res = 0L;
        try {
            if ((val & MASK_COUNT) == 0L) {
                return -1L;
            }
            int lim;
            // The total amount of buffer space discovered so far.
            long t = 0L;
            for (int i = 0; i < length; i++) {
                final ByteBuffer buffer = dsts[i + offset];
                // Grow the discovered buffer space by the remaining size of the current buffer.
                // We want to capture the limit so we calculate "remaining" ourselves.
                t += (lim = buffer.limit()) - buffer.position();
                if (t > (val & MASK_COUNT)) {
                    // only read up to this point, and trim the last buffer by the number of extra bytes
                    buffer.limit(lim - (int) (t - (val & MASK_COUNT)));
                    try {
                        return res = next.read(dsts, offset, i + 1);
                    } finally {
                        // restore the original limit
                        buffer.limit(lim);
                    }
                }
            }
            // the total buffer space is less than the remaining count.
            return res = next.read(dsts, offset, length);
        } finally {
            exitRead(res == -1L ? val & MASK_COUNT : res);
        }
    }

    public long read(final ByteBuffer[] dsts) throws IOException {
        return read(dsts, 0, dsts.length);
    }

    public int read(final ByteBuffer dst) throws IOException {
        long val = state;
        if (allAreSet(val, FLAG_CLOSED) || allAreClear(val, MASK_COUNT)) {
            return -1;
        }
        int res = 0;
        final long remaining = val & MASK_COUNT;
        try {
            final int lim = dst.limit();
            final int pos = dst.position();
            if (lim - pos > remaining) {
                dst.limit((int) (remaining + (long) pos));
                try {
                    return res = next.read(dst);
                } finally {
                    dst.limit(lim);
                }
            } else {
                return res = next.read(dst);
            }
        } finally {
            exitRead(res == -1 ? remaining : (long) res);
        }
    }

    public void suspendReads() {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSED | FLAG_FINISHED) || allAreClear(val, MASK_COUNT)) {
            return;
        }
        next.suspendReads();
    }

    public void resumeReads() {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSED | FLAG_FINISHED) || allAreClear(val, MASK_COUNT)) {
            return;
        }
        if (val == 0L) {
            next.wakeupReads();
        } else {
            next.resumeReads();
        }
    }

    public boolean isReadResumed() {
        return allAreClear(state, FLAG_CLOSED) && next.isReadResumed();
    }

    public void wakeupReads() {
        long val = state;
        if (anyAreSet(val, FLAG_CLOSED | FLAG_FINISHED) || allAreClear(val, MASK_COUNT)) {
            return;
        }
        next.wakeupReads();
    }

    @Override
    public void terminateReads() throws IOException {
        long val = enterShutdownReads();
        if (allAreSet(val, FLAG_CLOSED)) {
            return;
        }
        exitShutdownReads(val);
    }

    public void awaitReadable() throws IOException {
        final long val = state;
        if (allAreSet(val, FLAG_CLOSED) || val == 0L) {
            return;
        }
        next.awaitReadable();
    }

    public void awaitReadable(final long time, final TimeUnit timeUnit) throws IOException {
        final long val = state;
        if (allAreSet(val, FLAG_CLOSED) || val == 0L) {
            return;
        }
        next.awaitReadable(time, timeUnit);
    }

    /**
     * Get the number of remaining bytes.
     *
     * @return the number of remaining bytes
     */
    public long getRemaining() {
        return state & MASK_COUNT;
    }

    private long enterShutdownReads() {
        long oldVal, newVal;
        oldVal = state;
        if (anyAreSet(oldVal, FLAG_CLOSED)) {
            return oldVal;
        }
        newVal = oldVal | FLAG_CLOSED;
        state = newVal;
        return oldVal;
    }

    private void exitShutdownReads(long oldVal) {
        if (!allAreClear(oldVal, MASK_COUNT)) {
            finishListener.handleEvent(this);
        }
    }

    /**
     * Exit a read method.
     *
     * @param consumed the number of bytes consumed by this call (may be 0)
     */
    private void exitRead(long consumed) {
        long oldVal = state;
        long newVal = oldVal - consumed;
        state = newVal;
        if (anyAreSet(oldVal, MASK_COUNT) && allAreClear(newVal, MASK_COUNT)) {
            finishListener.handleEvent(this);
        }
    }

}