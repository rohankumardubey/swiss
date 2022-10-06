/*
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
package org.weakref.swiss;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.weakref.swiss.HashFunction.hash;

public class SwissPseudoVector
{
    private static final int VECTOR_LENGTH = Long.BYTES;
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN);
    private static final int VALUE_WIDTH = Long.BYTES;

    private final byte[] control;
    private final byte[] values;

    private final int capacity;
    private final int mask;

    private int size;
    private final int maxSize;

    public SwissPseudoVector(int maxSize)
    {
        checkArgument(maxSize > 0, "maxSize must be greater than 0");
        long expandedSize = maxSize * 16L / 15L;
        expandedSize = Math.max(VECTOR_LENGTH, 1L << (64 - Long.numberOfLeadingZeros(expandedSize - 1)));
        checkArgument(expandedSize < (1L << 30), "Too large (" + maxSize + " expected elements with load factor 7/8)");
        capacity = (int) expandedSize;

        this.maxSize = maxSize;
        mask = capacity - 1;
        control = new byte[capacity + VECTOR_LENGTH];
        values = new byte[toIntExact(((long) VALUE_WIDTH * capacity))];
    }

    public boolean put(long value)
    {
        checkState(size < maxSize, "Table is full");

        long hash = hash(value);
        byte hashPrefix = (byte) (hash & 0x7F | 0x80);
        int bucket = bucket((int) (hash >> 7));

        int step = 1;
        long repeated = repeat(hashPrefix);

        while (true) {
            final long controlVector = (long) LONG_HANDLE.get(control, bucket);

            if (matchInBucket(value, bucket, repeated, controlVector)) {
                return false;
            }

            int empty = findEmpty(controlVector);
            if (empty != VECTOR_LENGTH) {
                int emptyIndex = bucket(bucket + empty);
                insert(emptyIndex, value, hashPrefix);
                size++;
                
                return true;
            }

            bucket = bucket(bucket + step);
            step += VECTOR_LENGTH;
        }
    }

    private int findEmpty(long vector)
    {
        long controlMatches = match(vector, 0x00_00_00_00_00_00_00_00L);
        return controlMatches != 0 ? Long.numberOfTrailingZeros(controlMatches) >>> 3 : VECTOR_LENGTH;
    }

    private boolean matchInBucket(long value, int bucket, long repeated, long controlVector)
    {
        long controlMatches = match(controlVector, repeated);
        while (controlMatches != 0) {
            int index = bucket(bucket + (Long.numberOfTrailingZeros(controlMatches) >>> 3)) * VALUE_WIDTH;

            if ((long) LONG_HANDLE.get(values, index) == value) {
                return true;
            }

            controlMatches = controlMatches & (controlMatches - 1);
        }
        return false;
    }

    private void insert(int index, long value, byte hashPrefix)
    {
        control[index] = hashPrefix;
        if (index < VECTOR_LENGTH) {
            control[index + capacity] = hashPrefix;
        }

        LONG_HANDLE.set(values, index * VALUE_WIDTH, value);
    }

    private static long repeat(byte value)
    {
        long repeated = value & 0xFFL;
        repeated = (repeated << 8) | repeated;
        repeated = (repeated << 16) | repeated;
        repeated = (repeated << 32) | repeated;
        return repeated;
    }

    private static long match(long vector, long repeatedValue)
    {
        // HD 6-1
        long comparison = vector ^ repeatedValue;
        return (comparison - 0x01_01_01_01_01_01_01_01L) & ~comparison & 0x80_80_80_80_80_80_80_80L;
    }

    public boolean find(long value)
    {
        throw new UnsupportedOperationException("not yet implemented");
    }

    private int bucket(int hash)
    {
        return hash & mask;
    }
}
