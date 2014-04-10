
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb;

import java.util.NavigableMap;
import java.util.NavigableSet;

import org.jsimpledb.kv.KVPair;
import org.jsimpledb.util.Bounds;
import org.jsimpledb.util.ByteReader;
import org.jsimpledb.util.ByteUtil;
import org.jsimpledb.util.UnsignedIntEncoder;

/**
 * Implements the {@link NavigableMap} view of a simple field index.
 *
 * @param <V> type of the simple field's values being indexed
 * @param <E> type of the index entries associated with each indexed value
 */
class IndexMap<V, E> extends FieldTypeMap<V, NavigableSet<E>> {

    private final int storageId;
    private final FieldType<V> valueType;
    private final FieldType<E> entryType;

    /**
     * Primary constructor.
     */
    IndexMap(Transaction tx, int storageId, FieldType<V> valueType, FieldType<E> entryType) {
        super(tx, valueType, true, UnsignedIntEncoder.encode(storageId));
        this.storageId = storageId;
        this.valueType = valueType;
        this.entryType = entryType;
    }

    /**
     * Internal constructor.
     */
    IndexMap(Transaction tx, int storageId, FieldType<V> valueType, FieldType<E> entryType,
      boolean reversed, byte[] minKey, byte[] maxKey, Bounds<V> bounds) {
        super(tx, valueType, true, reversed, UnsignedIntEncoder.encode(storageId), minKey, maxKey, bounds);
        this.storageId = storageId;
        this.valueType = valueType;
        this.entryType = entryType;
    }

    @Override
    protected NavigableMap<V, NavigableSet<E>> createSubMap(boolean newReversed,
      byte[] newMinKey, byte[] newMaxKey, Bounds<V> newBounds) {
        return new IndexMap<V, E>(this.tx, this.storageId,
          this.valueType, this.entryType, newReversed, newMinKey, newMaxKey, newBounds);
    }

    @Override
    protected NavigableSet<E> decodeValue(KVPair pair) {
        final ByteReader reader = new ByteReader(pair.getKey());
        assert ByteUtil.isPrefixOf(this.prefix, reader.getBytes());
        reader.skip(this.prefix.length);
        this.valueType.skip(reader);
        return new IndexSet(this.tx, reader.getBytes(0, reader.getOffset()));
    }

// IndexSet

    /**
     * Implements the {@link NavigableSet} view of all entries associated with a specific value of an indexed simple field.
     */
    private class IndexSet extends FieldTypeSet<E> {

        /**
         * Primary constructor.
         *
         * @param tx transaction
         * @param prefix index entries prefix (includes field value)
         */
        IndexSet(Transaction tx, byte[] prefix) {
            super(tx, IndexMap.this.entryType, true, prefix);
        }

        /**
         * Internal constructor.
         *
         * @param tx transaction
         * @param reversed whether ordering is reversed (implies {@code bounds} are also inverted)
         * @param minKey minimum visible key (inclusive), or null for none
         * @param maxKey maximum visible key (exclusive), or null for none
         * @param bounds range restriction
         */
        private IndexSet(Transaction tx, boolean reversed, byte[] prefix, byte[] minKey, byte[] maxKey, Bounds<E> bounds) {
            super(tx, IndexMap.this.entryType, true, reversed, prefix, minKey, maxKey, bounds);
        }

        @Override
        protected NavigableSet<E> createSubSet(boolean newReversed, byte[] newMinKey, byte[] newMaxKey, Bounds<E> newBounds) {
            return new IndexSet(this.tx, newReversed, this.prefix, newMinKey, newMaxKey, newBounds);
        }
    }
}

