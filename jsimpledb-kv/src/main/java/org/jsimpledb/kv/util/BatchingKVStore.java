
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.kv.util;

import com.google.common.base.Preconditions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jsimpledb.kv.CloseableKVStore;
import org.jsimpledb.kv.KVException;
import org.jsimpledb.kv.KVPair;
import org.jsimpledb.kv.KVPairIterator;
import org.jsimpledb.kv.KVStore;
import org.jsimpledb.kv.KeyRange;
import org.jsimpledb.util.ByteUtil;
import org.jsimpledb.util.CloseableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A caching layer for read-only {@link KVStore}'s that have high latency for individual reads but low
 * latency for consecutive key/value pairs in a {@link KVStore#getRange KVStore.getRange()} range read.
 *
 * <p>
 * An example is a {@link KVStore} backed by some service provided over the network. Reading one key/value pair
 * costs a network round trip, whereas when reading a whole range of keys the round trip time is amortized over
 * the whole range because many key/value pairs can be streamed in a single response.
 *
 * <p>
 * When a read occurs, an instance triggers an asynchronous batch query with "read-ahead" and caches the result
 * in anticipation of further queries in the same region of the key space. Instances attempt to optimize behavior
 * based on estimations of round-trip time and range query key arrival rates.
 *
 * <p>
 * Internally, instances store contiguous key ranges and the associated values. Queries contained within a
 * range can be answered immediately; other queries trigger the creation of a new range or the extension of
 * an existing range.
 *
 * <p>
 * This class assumes the underlying key/value store is read-only and unchanging, so that cached values are
 * always up-to-date.
 *
 * <p><b>Configuration</b></p>
 *
 * <p>
 * Instances are configured with limits on {@linkplain #setMaxRanges the maximum number of contiguous key/value ranges},
 * {@linkplain #setMaxRangeBytes the maximum amount of data to preload into a single contiguous key/value range},
 * and {@linkplain #setMaxTotalBytes the maximum total amount of data to cache}. When these limits are exceeded,
 * stored ranges are discarded on a least-recently-used basis.
 */
public class BatchingKVStore extends CloseableForwardingKVStore {

/*

    Algorithm Design
    ================

    Data Model
    ----------

    We keep an ordered list of "KVRange" objects. Each KVRange has a "range" defined by a minimum key (inclusive,
    non-null) and maximum key (exclusive, may be null for positive infinity). The KVRange represents some contiguous
    portion of the keyspace for which all key/value pairs are known, and it contains all of those key/value pairs
    (if there are any). The "size" of a KVRange is the number of contained key/value pairs; a KVRange with size
    zero is "empty". A KVRange with minKey == maxKey is called "primordial" (and necessarily empty).

    A KVRange may have zero, one, or two associated "Loader" tasks. The purpose of a Loader is to extend the KVRange
    either downward or upward, using a getRange() iteration on the underlying KVStore (for downward, using a reverse
    getRange() starting at minKey; for upward, using a forward getRange() starting at maxKey).

    Each Loader also has a "limit", which is the second parameter it gave to getRange().

    Each Loader has an associated Future<?> representing the completion of the retrieval of the next key/value pair.
    If the Loader is no longer active, this is set to null, and setting it to null effectively cancels the Loader
    (it should also be explicitly cancel()'d).

    Primoridal KVRanges always have at least one Loader; any Loader(s) associated with a primoridal KVRange has yet
    to receive its first result from the getRange() query.

    Invariants
    ----------

    o KVRanges are non-overlapping and non-contiguous.
    o All KVRange key/value pairs are contained within the KVRange's range.
    o Primordial KVRanges have at least one associated Loader.

    Note that a Loader's limit may extend into or past the adjacent KVRange; this means two Loaders can potentially
    be trying to load data from intersecting ranges, though always from different starting points.

    Query Logic
    -----------

    When getAtLeast() or getAtMost() is invoked, we first check whether the target key is contained in any KVRange;
    if so, we find the key in the range; if it exists, we return it, otherwise, we shift the target to the end of
    the range and start over.

    At this point, the target key is not contained in any range. If the target is equal to the minKey or maxKey
    of some KVRange, then we check whether there exists a corresponding Loader for that range, we create a new one
    if necessary, and then wait on its Future<?>.

    If the target is not adjacent to any KVRange, we check whether a neighboring KVRange has a Loader which is loading
    in the direction of the target and for which the target is within the Loader's limit. If so, we decide whether it's
    loading fast enough for us (note, the Loader might still be primordial so no rate information is available yet),
    and if so we wait for it. Otherwise, we create a new primordial KVRange, and then wait for it.

    Upon waking up, we start over.

    Loader Logic
    ------------

    When a Loader retrieves the next new key/value pair from the iterator (or reaches the limit of the queried range),
    it first checks whether it's still active (non-null Future<?>), and if not, exits immediately. Otherwise, it triggers
    its Future<?>. The key retrieved (or end of iteration in conjunction with the limit) defines a new "extent" ranging
    from the previously returned key (or getRange() starting point) and the newly retrieved key (or limit if the iteration
    ended) in which we now know all the key/value pairs.

    The start of the extent is always adjacent to the associated KVRange, and starting from there it intersects one or more
    alternating KVRanges and interstitial ranges. The end of the extent intersects one final KVRange or interstitial range.
    All but the final KVRange are discarded (they must be empty), and any associated Loaders are canceled.

    If the end of the extent intersects a KVRange (the key must be the first key in that KVRange) then the extent is
    truncated at the start of that KVRange and the key (if any) is discarded as if the iteration had ended instead.
    The extent is then appended to the Loader's associated KVRange.

    Next, if the KVRange is now adjacent to the next KVRange, the two ranges are merged, and any eliminated Loaders are
    canceled.

    The Loader stops if:
        - The key/value pair iteration ended;
        - The KVRange reached its maximum byte count; or,
        - The extent touched or intersected another KVRange

    Otherwise, the Loader replaces its Future<?> with a new one.

*/

    /**
     * Default maximum number of contiguous ranges of key/value pairs to allow before we start purging the
     * least recently used ones ({@value #DEFAULT_MAX_RANGES}).
     */
    public static final int DEFAULT_MAX_RANGES = 256;

    /**
     * Default maximum number of bytes to cache in a single contiguous range of key/value pairs
     * ({@value #DEFAULT_MAX_RANGE_BYTES}).
     */
    public static final long DEFAULT_MAX_RANGE_BYTES = 10 * 1024 * 1024;

    /**
     * Default maximum total number of bytes to cache including all ranges ({@value #DEFAULT_MAX_TOTAL_BYTES}).
     */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 100 * 1024 * 1024;

    private static final byte[][] EMPTY_BYTES = new byte[0][];

    private static final double RTT_DECAY_FACTOR = 0.025;
    private static final double WAIT_VS_RTT_FACTOR = 1.5;

    private static final int INITIAL_ARRAY_CAPACITY = 32;
    private static final float ARRAY_GROWTH_FACTOR = 1.5f;

    private static final Comparator<KVRange> SORT_BY_MIN = Comparator.comparing(KVRange::getMin, ByteUtil.COMPARATOR);

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final MovingAverage rtt;                                            // estimation of time to load first key in range
    private final ExecutorService executor;                                     // executor for async loading tasks
    private final TreeSet<KVRange> ranges = new TreeSet<>(SORT_BY_MIN);         // ranges ordered by key range minimum
    private final RingEntry<KVRange> lru = new RingEntry<>(null);               // ranges ordered by recency (MRU first, LRU last)

    private int maxRanges = DEFAULT_MAX_RANGES;
    private long maxRangeBytes = DEFAULT_MAX_RANGE_BYTES;
    private long maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES;
    private long totalBytes;
    private KVException error;

// Constructors

    /**
     * Constructor.
     *
     * @param kvstore underlying key/value store
     * @param executor executor for performing asynchronous batch queries
     * @param rttEstimate estimated round trip time in milliseconds
     * @throws IllegalArgumentException if either parameter is null
     * @throws IllegalArgumentException if {@code rttEstimate} is negative
     */
    public BatchingKVStore(KVStore kvstore, ExecutorService executor, int rttEstimate) {
        this(kvstore, null, executor, rttEstimate);
    }

    /**
     * Constructor for when the underlying {@link KVStore} should be closed when this instance is closed.
     *
     * @param kvstore underlying key/value store; will be closed on {@link #close}
     * @param executor executor for performing asynchronous batch queries
     * @param rttEstimate estimated round trip time in milliseconds
     * @throws IllegalArgumentException if either parameter is null
     * @throws IllegalArgumentException if {@code rttEstimate} is negative
     */
    public BatchingKVStore(CloseableKVStore kvstore, ExecutorService executor, int rttEstimate) {
        this(kvstore, kvstore, executor, rttEstimate);
    }

    private BatchingKVStore(KVStore kvstore, Closeable closeable, ExecutorService executor, int rttEstimate) {
        super(kvstore, closeable);
        Preconditions.checkArgument(executor != null, "null executor");
        Preconditions.checkArgument(rttEstimate >= 0, "rttEstimate < 0");
        this.executor = executor;
        this.rtt = new MovingAverage(RTT_DECAY_FACTOR, TimeUnit.MILLISECONDS.toNanos(rttEstimate));
    }

// Accessors

    /**
     * Configure the maximum number of bytes to cache in a single contiguous range of key/value pairs.
     *
     * <p>
     * Default is {@value #DEFAULT_MAX_RANGE_BYTES}.
     *
     * @param maxRangeBytes maximum bytes in any one range
     * @throws IllegalArgumentException if {@code maxRangeBytes <= 0}
     */
    public synchronized void setMaxRangeBytes(int maxRangeBytes) {
        Preconditions.checkArgument(maxRanges > 0, "maxRangeBytes <= 0");
        this.maxRangeBytes = maxRangeBytes;
    }

    /**
     * Configure thmum total number of bytes to cache. This is an overal maximum incluging all ranges.
     *
     * <p>
     * Default is {@value #DEFAULT_MAX_TOTAL_BYTES}.
     *
     * @param maxTotalBytes maximum cached ranges
     * @throws IllegalArgumentException if {@code maxTotalBytes <= 0}
     */
    public synchronized void setMaxTotalBytes(int maxTotalBytes) {
        Preconditions.checkArgument(maxTotalBytes > 0, "maxTotalBytes <= 0");
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Configure the maximum number of contiguous ranges of key/value pairs to allow before we start purging the
     * least recently used ones.
     *
     * <p>
     * Default is {@value #DEFAULT_MAX_RANGES}.
     *
     * @param maxRanges maximum cached ranges
     * @throws IllegalArgumentException if {@code maxRanges <= 0}
     */
    public synchronized void setMaxRanges(int maxRanges) {
        Preconditions.checkArgument(maxRanges > 0, "maxRanges <= 0");
        this.maxRanges = maxRanges;
    }

// KVStore

    @Override
    public byte[] get(byte[] key) {
        final KVPair pair = this.getAtLeast(key, ByteUtil.getNextKey(key));
        return pair != null ? pair.getValue() : null;
    }

    @Override
    public CloseableIterator<KVPair> getRange(byte[] minKey, byte[] maxKey, boolean reverse) {
        if (minKey == null)
            minKey = ByteUtil.EMPTY;
        return new KVPairIterator(this, new KeyRange(minKey, maxKey), null, reverse);
    }

    @Override
    public KVPair getAtLeast(byte[] minKey, byte[] maxKey) {
        if (minKey == null)
            minKey = ByteUtil.EMPTY;
        if (KeyRange.compare(minKey, maxKey) >= 0)
            return null;
        return this.find(minKey, maxKey, false);
    }

    @Override
    public KVPair getAtMost(byte[] maxKey, byte[] minKey) {
        if (minKey == null)
            minKey = ByteUtil.EMPTY;
        if (KeyRange.compare(minKey, maxKey) >= 0)
            return null;
        return this.find(maxKey, minKey, true);
    }

// Closeable

    /**
     * Close this instance and release any resources associated with it.
     *
     * <p>
     * This does <em>not</em> shutdown the {@link ExecutorService} provided to the constructor.
     *
     * <p>
     * If this instance is already closed, then nothing happens.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (this.log.isTraceEnabled())
                this.trace("closing with ranges={}", this.ranges);
            for (KVRange range : this.ranges)
                this.discard(range, false);
            this.ranges.clear();
        }
        super.close();
    }

// Internal methods

    private KVPair find(byte[] start, final byte[] limit, final boolean reverse) {

        // Sanity check
        assert start != null || reverse;
        assert limit != null || !reverse;

        // Determine limit on how long we'll wait for some existing load operation before starting our own
        long waitTimeRemain;
        synchronized (this) {
            assert this.sanityCheck();
            waitTimeRemain = (long)(WAIT_VS_RTT_FACTOR * this.rtt.get());
        }
        assert waitTimeRemain >= 0;

        // Loop until we have an answer
        long lastLoopTime = System.nanoTime();
        boolean interrupted = false;
        while (true) {
            Future<?> future;
            synchronized (this) {

                // Check for error
                if (this.error != null)
                    this.error.rethrow();

                // Sanity check
                assert this.sanityCheck();

                // Update wait time remaining
                final long now = System.nanoTime();
                final long elapsed = now - lastLoopTime;
                waitTimeRemain = Math.max(0, waitTimeRemain - elapsed);
                lastLoopTime = now;

                // Debug
                if (this.log.isTraceEnabled()) {
                    this.trace("find: start={} limit={} {} remain={}ms ranges={}", ByteUtil.toString(start),
                      ByteUtil.toString(limit), reverse ? "reverse" : "forward", waitTimeRemain / 1000000L, this.ranges);
                }

                // Find the closest range whose minimum is <= start (if forward), or whose maximum is >= start (if reverse).
                // We may have to do an extra step in the reverse case because ranges are sorted by minimum, not maximum.
                KVRange range = this.last(start != null ? this.ranges.headSet(this.key(start), true) : this.ranges);
                if (reverse && range != null) {
                    if (KeyRange.compare(range.getMax(), start) < 0) {
                        range = this.first(this.ranges.tailSet(this.key(start), true));
                        assert KeyRange.compare(range.getMax(), start) >= 0;
                    }
                }

                // If we found one, investigate to determine what to do
                Loader loader = null;
                if (range != null) {

                    // We have a candidate range
                    assert reverse ?
                      KeyRange.compare(start, range.getMax()) <= 0 :
                      KeyRange.compare(start, range.getMin()) >= 0;

                    // See if it contains the start of our search
                    final boolean rangeContainsStart = reverse ?
                      KeyRange.compare(start, range.getMin()) > 0 :
                      KeyRange.compare(start, range.getMax()) < 0;
                    if (rangeContainsStart) {

                        // Search range for key/value pair
                        KVPair pair = reverse ? range.getAtMost(start) : range.getAtLeast(start);

                        // See if range also contains the limit of our search
                        final boolean rangeContainsLimit = reverse ?
                          KeyRange.compare(limit, range.getMin()) >= 0 :
                          KeyRange.compare(limit, range.getMax()) <= 0;

                        // Ignore any pair that's past the limit
                        if (pair != null && rangeContainsLimit) {
                            final boolean pairWithinLimit = reverse ?
                              KeyRange.compare(pair.getKey(), limit) >= 0 :
                              KeyRange.compare(pair.getKey(), limit) < 0;
                            if (!pairWithinLimit)
                                pair = null;
                        }

                        // Return positive result if we know it
                        if (pair != null || rangeContainsLimit) {
                            if (this.log.isTraceEnabled()) {
                                if (pair != null) {
                                    this.trace("find: start={} limit={} found {}={} in range={}",
                                      ByteUtil.toString(start), ByteUtil.toString(limit),
                                      ByteUtil.toString(pair.getKey()), ByteUtil.toString(pair.getValue()), range);
                                } else {
                                    this.trace("find: start={} limit={} not found in range={}",
                                      ByteUtil.toString(start), ByteUtil.toString(limit), range);
                                }
                            }

                            // Keep range fresh
                            this.touch(range);
                            return pair;
                        }

                        // Nothing found in range, so shift our starting point past the empty space
                        if (this.log.isTraceEnabled()) {
                            this.trace("find: start={} limit={} not found in range={} -> new start={}",
                              ByteUtil.toString(start), ByteUtil.toString(limit), range, reverse ? range.getMin() : range.getMax());
                        }
                        start = reverse ? range.getMin() : range.getMax();
                    }

                    // Check whether our starting point exactly matches the edge of the adjacent range
                    final boolean startOnEdge = KeyRange.compare(start, reverse ? range.getMin() : range.getMax()) == 0;

                    // If range already has a loader pointing at start, determine whether we want to wait for it
                    if ((loader = range.getLoader(reverse)) == null) {

                        // No loader, so unless we're on the edge of range we want to create a new range at our start point
                        if (!startOnEdge) {
                            if (this.log.isTraceEnabled()) {
                                this.trace("find: start={} limit={} range={} not adjacent, no loader => create my own range",
                                  ByteUtil.toString(start), ByteUtil.toString(limit), range);
                            }
                            range = null;                                                   // create a new range
                        } else {
                            if (this.log.isTraceEnabled()) {
                                this.trace("find: start={} limit={} range={} adjacent but no loader => create new loader",
                                  ByteUtil.toString(start), ByteUtil.toString(limit), range);
                            }
                        }
                    } else if (!startOnEdge) {

                        // There is space between range and our start point: estimate the arrival time of our start key
                        final long eta;
                        if (range.size() < 2)
                            eta = (long)(System.nanoTime() - (loader.getStartTime() + this.rtt.get()));
                        else {
                            final double arrivalRate = loader.getArrivalRate();
                            final byte[] loaderBase = reverse ? range.getMin() : range.getMax();
                            eta = arrivalRate > 0 ?
                              (long)(Math.abs(this.measure(start) - this.measure(loaderBase)) / arrivalRate) : -1;
                        }

                        // If wait is too long, create a new range and loader (i.e., don't wait for the existing loader to reach us)
                        if (eta >= waitTimeRemain) {
                            range = null;                                                   // create a new range
                            loader = null;                                                  // ... and a new loader obviously
                            if (this.log.isTraceEnabled()) {
                                this.trace("find: start={} limit={} range={} eta={} >= waitTimeRemain={} => don't wait for it",
                                  ByteUtil.toString(start), ByteUtil.toString(limit), range, eta, waitTimeRemain);
                            }
                        } else {
                            if (this.log.isTraceEnabled()) {
                                this.trace("find: start={} limit={} range={} eta={} < waitTimeRemain={} => wait for it",
                                  ByteUtil.toString(start), ByteUtil.toString(limit), range, eta, waitTimeRemain);
                            }
                        }
                    }
                } else {
                    if (this.log.isTraceEnabled()) {
                        this.trace("find: start={} limit={} no containing or adjacent range found",
                          ByteUtil.toString(start), ByteUtil.toString(limit));
                    }
                }
                assert range != null || loader == null;

                // Create a new range if necessary
                if (range == null) {
                    range = new KVRange(start);
                    this.ranges.add(range);
                    range.getLruEntry().attachAfter(this.lru);
                    if (this.log.isTraceEnabled()) {
                        this.trace("find: start={} limit={} created new {}",
                          ByteUtil.toString(start), ByteUtil.toString(limit), range);
                    }
                } else
                    this.touch(range);                                                      // keep range fresh

                // Create a new loader if necessary
                if (loader == null) {
                    assert range.getLoader(reverse) == null : "" + range;

                    // Extend our range query all the way to the neighboring range, or inifinity if there is none
                    byte[] actualLimit = limit;
                    if (reverse && limit.length > 0) {
                        final KVRange nextRange = this.ranges.lower(range);
                        actualLimit = nextRange != null && !nextRange.isPrimordial() ? nextRange.getMax() : ByteUtil.EMPTY;
                    } else if (!reverse && limit != null) {
                        final KVRange nextRange = this.ranges.higher(range);
                        actualLimit = nextRange != null && !nextRange.isPrimordial() ? nextRange.getMin() : null;
                    }

                    // Create new loader
                    loader = new Loader(range, reverse, actualLimit);
                    if (this.log.isTraceEnabled()) {
                        this.trace("find: start={} limit={} created new {}",
                          ByteUtil.toString(start), ByteUtil.toString(limit), loader);
                    }
                }

                // We will wait for loader
                future = loader.getFuture();
                assert future != null;

                // Scrub
                this.scrub();

                // Sanity check
                assert this.sanityCheck();
                if (this.log.isTraceEnabled())
                    this.trace("going to sleep, ranges={}", this.ranges);
            }

            // Wait for loader to report progress, then try again
            try {
                future.get();
                if (this.log.isTraceEnabled())
                    this.trace("find: start={} limit={} woke up", ByteUtil.toString(start), ByteUtil.toString(limit));
            } catch (CancellationException e) {                                                 // loader was stopped
                if (this.log.isTraceEnabled()) {
                    this.trace("find: start={} limit={} woke up - {}",
                      ByteUtil.toString(start), ByteUtil.toString(limit), "canceled");
                }
            } catch (ExecutionException e) {
                if (this.log.isTraceEnabled()) {
                    this.trace("find: start={} limit={} woke up - {}",
                      ByteUtil.toString(start), ByteUtil.toString(limit), (Object)e.getCause());
                }
                synchronized (this) {
                    if (this.error == null) {                                                       // this should never happen
                        final Throwable cause = e.getCause();
                        this.error = cause instanceof KVException ? (KVException)cause : new KVException(cause);
                    }
                    throw this.error.rethrow();
                }
            } catch (InterruptedException e) {
                if (this.log.isTraceEnabled()) {
                    this.trace("find: start={} limit={} woke up - {}",
                      ByteUtil.toString(start), ByteUtil.toString(limit), "interrupted");
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    // Move range back to the front of the LRU list
    private void touch(KVRange range) {
        assert Thread.holdsLock(this);
        assert range.getLruEntry().isAttached();
        assert this.ranges.contains(range) : "range " + range + " not found in " + this.ranges;
        if (this.log.isTraceEnabled())
            this.trace("touch: renew range={}", range);
        range.getLruEntry().attachAfter(this.lru);
    }

    // Remove old ranges until we are underneath our total byte limit
    private void scrub() {
        assert Thread.holdsLock(this);
        while (this.totalBytes > this.maxTotalBytes) {

            // Get the least recently used range
            final KVRange range = this.lru.prev().getOwner();
            if (range == null)                                          // LRU ring is empty
                break;

            // Discard it
            this.discard(range, true);
        }
    }

    private void discard(KVRange range, boolean removeFromRanges) {
        assert Thread.holdsLock(this);
        if (this.log.isTraceEnabled())
            this.trace("discarding range={} age={}ms", range, (System.nanoTime() - range.getCreationTime()) / 1000000L);
        range.stopLoader(false);
        range.stopLoader(true);
        range.getLruEntry().detach();
        this.totalBytes -= range.getTotalBytes();
        if (removeFromRanges) {
            final boolean removed = this.ranges.remove(range);
            assert removed;
        }
    }

    private boolean sanityCheck() {
        assert Thread.holdsLock(this);
        for (KVRange range : this.ranges)
            assert range.sanityCheck();
        KVRange prev = null;
        for (KVRange next : this.ranges) {
            assert KeyRange.compare(next.getMin(), next.getMax()) <= 0;
            assert KeyRange.compare(next.getMin(), next.getMax()) != 0
              || next.getLoader(false) != null
              || next.getLoader(true) != null;
            assert prev == null || KeyRange.compare(prev.max, next.min) < 0 : "range overlap : " + this.ranges;
            prev = next;
        }
        final ArrayList<KVRange> rangesList = new ArrayList<>(this.ranges);
        final ArrayList<KVRange> lruList = new ArrayList<>(this.ranges.size());
        for (RingEntry<KVRange> entry = this.lru.next(); entry.getOwner() != null; entry = entry.next())
            lruList.add(entry.getOwner());
        Collections.sort(lruList, SORT_BY_MIN);
        assert lruList.equals(rangesList) : "lru=" + lruList + ", ranges=" + rangesList;
        return true;
    }

    /**
     * Get a search key for searching in an ordered list of {@link KVRange}'s.
     */
    private KVRange key(byte[] key) {
        return new KVRange(key, key, EMPTY_BYTES, EMPTY_BYTES, 0, 0, 0);
    }

    private double measure(byte[] value) {
        return value != null ? ByteUtil.toDouble(value) : 1.0;
    }

    private void debug(String msg, Object... params) {
        this.log.debug(this.getClass().getSimpleName() + ": " + msg, params);
    }

    private void trace(String msg, Object... params) {
        this.log.trace(this.getClass().getSimpleName() + ": " + msg, params);
    }

    private <T> T first(SortedSet<T> set) {
        try {
            return set.first();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private <T> T last(SortedSet<T> set) {
        try {
            return set.last();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

// Loader

    private class Loader implements Runnable {

        private static final double RATE_DECAY_FACTOR = 0.10;

        private final Logger log = BatchingKVStore.this.log;
        private final long startTime = System.nanoTime();

        private final KVRange range;                    // the range that we are extending
        private final boolean reverse;                  // the direction in which the range is being extended
        private final byte[] start;                     // the start of our range query
        private final byte[] limit;                     // the end of our range query
        private Future<?> taskFuture;                   // the future associated with the executor task
        private CompletableFuture<?> future;            // the future indicating that we have extended the range by a new extent

        // This is the exponential moving average of the rate at which key/value pair retrieval is moving through the keyspace.
        // This is measured in key-space-units/nanosecond, where "key-space-units" are what is returned by ByteUtil.toDouble().
        private MovingAverage arrivalRate = new MovingAverage(RATE_DECAY_FACTOR);

        Loader(KVRange range, boolean reverse, byte[] limit) {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert range != null;
            this.range = range;
            this.reverse = reverse;
            this.start = this.getBase();
            assert reverse ?
              KeyRange.compare(limit, this.start) < 0 :
              KeyRange.compare(limit, this.start) > 0;
            this.future = new CompletableFuture<>();
            this.taskFuture = BatchingKVStore.this.executor.submit(this);
            this.range.setLoader(this.reverse, this);
            this.limit = limit;
        }

        /**
         * Get the current base of this loader's query. This is just the min or max of the associated {@link KVRange}.
         */
        public byte[] getBase() {
            return this.reverse ? this.range.getMin() : this.range.getMax();
        }

        /**
         * Determine the direction of loading.
         */
        public boolean isReverse() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.reverse;
        }

        /**
         * Get the estimated key arrival rate in key-space-units/nanosecond.
         */
        public double getArrivalRate() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.arrivalRate.get();
        }

        public long getStartTime() {
            return this.startTime;
        }

        /**
         * Get the {@link Future} associated with the task currently loading this range.
         *
         * @return task {@link Future}, or null if no task is currently loading this range
         */
        public CompletableFuture<?> getFuture() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.future;
        }
        public boolean isStopped() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.future == null;
        }

        /**
         * Stop the task that's loading this range, if any.
         */
        public void stop() {
            assert Thread.holdsLock(BatchingKVStore.this);
            if (this.future != null) {
                this.future.cancel(true);
                this.future = null;
            }
            if (this.taskFuture != null) {
                if (this.log.isTraceEnabled())
                    this.trace("stopping task");
                this.taskFuture.cancel(true);
                this.taskFuture = null;
            }
            if (this.range.getLoader(this.reverse) == this)
                this.range.setLoader(this.reverse, null);
        }

        @Override
        public void run() {
            try {
                if (this.log.isTraceEnabled()) {
                    this.trace("run() invoked, creating iterator {}...{}",
                      ByteUtil.toString(this.start), ByteUtil.toString(this.limit));
                }

                // Get iterator, load key/value pairs, then close iterator
                try (final CloseableIterator<KVPair> iterator = this.reverse ?
                  BatchingKVStore.super.getRange(this.limit, this.start, true) :
                  BatchingKVStore.super.getRange(this.start, this.limit, false)) {
                    this.load(iterator);
                }
            } catch (Throwable t) {
                if (this.log.isTraceEnabled())
                    this.trace("exception in task", t);
                synchronized (BatchingKVStore.this) {
                    if (!this.isStopped()) {
                        if (BatchingKVStore.this.error == null) {
                            BatchingKVStore.this.error = t instanceof KVException ? (KVException)t : new KVException(t);
                            BatchingKVStore.this.close();
                        }
                        throw BatchingKVStore.this.error.rethrow();
                    }
                }
            } finally {
                synchronized (BatchingKVStore.this) {
                    this.stop();
                }
            }
        }

        private void load(final Iterator<KVPair> iterator) {
            if (this.log.isTraceEnabled())
                this.trace("starting load");
            int numKeysLoaded = 0;
            long lastMeasureTime = 0;
            double lastKeySpaceValue = 0;
            while (true) {

                // Read next key/value pair
                final KVPair pair = iterator.hasNext() ? iterator.next() : null;
                byte[] key = pair != null ? pair.getKey() : null;
                byte[] val = pair != null ? pair.getValue() : null;
                if (key != null) {
                    if (this.reverse) {
                        synchronized (BatchingKVStore.this) {
                            assert KeyRange.compare(key, this.getBase()) < 0 : "key=" + ByteUtil.toString(key);
                            assert KeyRange.compare(key, this.limit) >= 0 : "key=" + ByteUtil.toString(key);
                        }
                    } else {
                        synchronized (BatchingKVStore.this) {
                            assert KeyRange.compare(key, this.getBase()) >= 0 : "key=" + ByteUtil.toString(key);
                            assert KeyRange.compare(key, this.limit) < 0 : "key=" + ByteUtil.toString(key);
                        }
                    }
                }
                if (this.log.isTraceEnabled()) {
                    if (key != null)
                        this.trace("got next key={}", ByteUtil.toString(key));
                    else
                        this.trace("got end of iteration after {}ms", (System.nanoTime() - this.startTime) / 1000000L);
                }

                // Update our range
                synchronized (BatchingKVStore.this) {

                    // Sanity check
                    assert BatchingKVStore.this.sanityCheck();

                    // Debug
                    if (this.log.isTraceEnabled())
                        this.trace("handle key={} ranges={}", ByteUtil.toString(key), BatchingKVStore.this.ranges);

                    // Check for stoppage
                    if (this.isStopped()) {
                        if (this.log.isTraceEnabled())
                            this.trace("stopped");
                        break;
                    }
                    assert this.range.getLoader(this.reverse) == this;
                    assert this.future != null;

                    // Find ranges completely covered by our extent, and discard them
                    byte[] extentMin = !reverse ? this.getBase() : key != null ? key : this.limit;
                    byte[] extentMax = reverse ? this.getBase() : key != null ? ByteUtil.getNextKey(key) : this.limit;
                    boolean stopLoading = key == null;
                    for (Iterator<KVRange> i = BatchingKVStore.this.ranges.tailSet(
                      BatchingKVStore.this.key(extentMin), true).iterator(); i.hasNext(); ) {
                        final KVRange contained = i.next();
                        assert ByteUtil.compare(contained.getMin(), extentMin) >= 0;

                        // Is range completely contained within our extent?
                        if (KeyRange.compare(contained.getMax(), extentMax) > 0)
                            break;

                        // Don't discard our own range, which at first will appear contained
                        if (contained == this.range) {
                            assert KeyRange.compare(this.range.getMin(), this.range.getMax()) == 0;
                            continue;
                        }

                        // If range is not empty, it must only contain, and end with, the key we found
                        assert contained.containsOnlyKeyInRange(key, ByteUtil.EMPTY, null) :
                          "contained=" + contained + " key=" + ByteUtil.toString(key);

                        // Discard the range
                        if (this.log.isTraceEnabled())
                            this.trace("discarding contained range {}", contained);
                        i.remove();
                        BatchingKVStore.this.discard(contained, false);
                    }

                    // If our extent partially overlaps an existing range, truncate our extent so there's no overlap
                    if (reverse) {
                        final KVRange overlap = this.last(
                          BatchingKVStore.this.ranges.headSet(BatchingKVStore.this.key(extentMin), false));
                        assert overlap == null || overlap == this.range || KeyRange.compare(overlap.getMin(), extentMin) < 0;
                        if (overlap != null && overlap != this.range && KeyRange.compare(overlap.getMax(), extentMin) > 0) {

                            // If the range is not empty, it must contain any key we found, or else be empty up through our extent
                            if (this.log.isTraceEnabled()) {
                                this.trace("overlap range {}, truncate {} -> {}",
                                  overlap, ByteUtil.toString(extentMin), ByteUtil.toString(overlap.getMax()));
                            }
                            assert overlap.containsOnlyKeyInRange(key, extentMin, extentMax);
                            key = null;
                            extentMin = overlap.getMax();
                            stopLoading = true;
                        }
                    } else {
                        final KVRange overlap = this.last(extentMax != null ?
                          BatchingKVStore.this.ranges.headSet(BatchingKVStore.this.key(extentMax), false) :
                          BatchingKVStore.this.ranges);
                        assert overlap == null || overlap == this.range || KeyRange.compare(overlap.getMin(), extentMax) < 0;
                        if (overlap != null && overlap != this.range) {
                            assert KeyRange.compare(overlap.getMax(), extentMax) > 0;         // or else should have been discarded

                            // If the range is not empty, it must contain any key we found, or else be empty up through our extent
                            if (this.log.isTraceEnabled()) {
                                this.trace("overlap range {}, truncate {} -> {}",
                                  overlap, ByteUtil.toString(extentMax), ByteUtil.toString(overlap.getMin()));
                            }
                            assert overlap.containsOnlyKeyInRange(key, extentMin, extentMax);
                            key = null;
                            extentMax = overlap.getMin();
                            stopLoading = true;
                        }
                    }

                    // Add the key, if any, to the range and update the range to include the new extent
                    if (key != null) {
                        if (this.log.isTraceEnabled())
                            this.trace("adding key {} to {}", ByteUtil.toString(key), this.range);
                        this.range.add(key, val);
                    }
                    if (reverse) {
                        if (this.log.isTraceEnabled())
                            this.trace("setting min of {} to {}", this.range, ByteUtil.toString(extentMin));
                        this.range.setMin(extentMin);
                    } else {
                        if (this.log.isTraceEnabled())
                            this.trace("setting max of {} to {}", this.range, ByteUtil.toString(extentMax));
                        this.range.setMax(extentMax);
                    }

                    // Update key/value pair arrival rate
                    final long now = System.nanoTime();
                    final double keySpaceValue = this.reverse ? ByteUtil.toDouble(extentMin) :
                      extentMax != null ? ByteUtil.toDouble(extentMax) : 1.0;
                    if (numKeysLoaded++ == 0) {
                        lastKeySpaceValue = keySpaceValue;
                        lastMeasureTime = now;
                        if (this.log.isTraceEnabled())
                            this.trace("first key @ {}ms", (now - this.startTime) / 1000000L);
                    } else {
                        final double nanosElapsed = now - lastMeasureTime;
                        final double keySpaceDiff = this.reverse ?
                          lastKeySpaceValue - keySpaceValue : keySpaceValue - lastKeySpaceValue;
                        final double oldArrivalRate = this.arrivalRate.get();
                        this.arrivalRate.add(keySpaceDiff / nanosElapsed);
                        if (this.log.isTraceEnabled()) {
                            this.trace("key #{} dif={} elapsed={} oldRate={} newRate={}",
                              numKeysLoaded, keySpaceDiff, nanosElapsed, oldArrivalRate, this.arrivalRate.get());
                        }
                    }

                    // If we now abut the neighboring range, merge with it; if neighbor is primordial, just discard it
                    KVRange mergedRange = null;
                    if (reverse && extentMin.length > 0) {
                        final KVRange neighbor
                          = this.last(BatchingKVStore.this.ranges.headSet(BatchingKVStore.this.key(extentMin), false));
                        if (neighbor != null && ByteUtil.compare(neighbor.getMax(), extentMin) == 0) {
                            if (neighbor.isPrimordial()) {
                                assert neighbor.isEmpty();
                                if (this.log.isTraceEnabled())
                                    this.trace("merging primordial {} -> {}", neighbor, this.range);
                                BatchingKVStore.this.discard(neighbor, true);
                            } else {
                                if (this.log.isTraceEnabled())
                                    this.trace("merging {} + {}", neighbor, this.range);
                                mergedRange = neighbor.merge(this.range);
                                if (this.log.isTraceEnabled())
                                    this.trace("result of merge: {}", mergedRange);
                                BatchingKVStore.this.discard(neighbor, true);
                                BatchingKVStore.this.discard(this.range, true);
                                BatchingKVStore.this.ranges.add(mergedRange);
                                mergedRange.getLruEntry().attachAfter(BatchingKVStore.this.lru);
                                assert mergedRange.sanityCheck();
                            }
                            assert BatchingKVStore.this.sanityCheck();
                        }
                    } else if (!reverse && extentMax != null) {
                        final KVRange neighbor
                          = this.first(BatchingKVStore.this.ranges.tailSet(BatchingKVStore.this.key(extentMax), true));
                        if (neighbor != null && ByteUtil.compare(neighbor.getMin(), extentMax) == 0) {
                            if (neighbor.isPrimordial()) {
                                assert neighbor.isEmpty();
                                if (this.log.isTraceEnabled())
                                    this.trace("merging primordial {} <- {}", this.range, neighbor);
                                BatchingKVStore.this.discard(neighbor, true);
                            } else {
                                if (this.log.isTraceEnabled())
                                    this.trace("merging {} + {}", this.range, neighbor);
                                mergedRange = this.range.merge(neighbor);
                                if (this.log.isTraceEnabled())
                                    this.trace("result of merge: {}", mergedRange);
                                BatchingKVStore.this.discard(this.range, true);
                                BatchingKVStore.this.discard(neighbor, true);
                                BatchingKVStore.this.ranges.add(mergedRange);
                                mergedRange.getLruEntry().attachAfter(BatchingKVStore.this.lru);
                                assert mergedRange.sanityCheck();
                            }
                            assert BatchingKVStore.this.sanityCheck();
                        }
                    }

                    // Stop if we merged into another range, or if our range has gotten too big
                    stopLoading |= mergedRange != null || this.range.getTotalBytes() > BatchingKVStore.this.maxRangeBytes;

                    // Stop if done
                    if (stopLoading) {
                        this.stop();
                        assert BatchingKVStore.this.sanityCheck();
                        break;
                    }

                    // Otherwise, trigger future to wakup waiters and reset
                    assert this.future != null;
                    this.future.complete(null);
                    this.future = new CompletableFuture<>();
                }
            }
        }

        private void debug(String msg, Object... params) {
            this.log.debug(this + ": " + msg, params);
        }

        private void trace(String msg, Object... params) {
            this.log.trace(this + ": " + msg, params);
        }

        private <T> T first(SortedSet<T> set) {
            try {
                return set.first();
            } catch (NoSuchElementException e) {
                return null;
            }
        }

        private <T> T last(SortedSet<T> set) {
            try {
                return set.last();
            } catch (NoSuchElementException e) {
                return null;
            }
        }

    // Object

        @Override
        public String toString() {
            synchronized (BatchingKVStore.this) {
                return "Loader"
                  + "[" + (this.reverse ? "reverse" : "forward")
                  + ",base=" + ByteUtil.toString(this.getBase())
                  + ",limit=" + ByteUtil.toString(this.limit)
                  + "]";
            }
        }
    }

// KVRange

    private class KVRange {

        private final long creationTime = System.nanoTime();
        private final RingEntry<KVRange> lruEntry = new RingEntry<>(this);
        private final Loader[] loaders = new Loader[2];                 // 0 = forward, 1 = reverse

        private byte[] min;
        private byte[] max;

        private byte[][] keys;
        private byte[][] vals;
        private int minIndex;
        private int maxIndex;
        private long totalBytes;
        private int lastKnownRangesIndex;

        KVRange(byte[] start) {
            this(start, start, new byte[INITIAL_ARRAY_CAPACITY][], new byte[INITIAL_ARRAY_CAPACITY][], 0, 0, 0);
        }

        KVRange(byte[] min, byte[] max, byte[][] keys, byte[][] vals, int minIndex, int maxIndex, long totalBytes) {
            this.min = min;
            this.max = max;
            this.keys = keys;
            this.vals = vals;
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            this.totalBytes = totalBytes;
            assert this.sanityCheckInternal();
        }

        /**
         * Get the lower limit (inclusive) of the associated range of keys.
         *
         * @return lower limit of known range (inclusive)
         */
        public byte[] getMin() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.min;
        }
        public void setMin(byte[] min) {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert min != null;
            assert ByteUtil.compare(min, this.min) <= 0;                // KVRanges can only get bigger, not smaller
            this.min = min;
        }

        /**
         * Get the upper limit (exclusive) of the associated range of keys.
         *
         * @return upper limit of known range (exclusive); null means infinity
         */
        public byte[] getMax() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.max;
        }
        public void setMax(byte[] max) {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert KeyRange.compare(max, this.max) >= 0;                // KVRanges can only get bigger, not smaller
            this.max = max;
        }

        public KeyRange getKeyRange() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return new KeyRange(this.min, this.max);
        }

        /**
         * Get last known index of this entry in the 'ranges' list.
         */
        public int getLastKnownRangesIndex() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.lastKnownRangesIndex;
        }
        public void setLastKnownRangesIndex(int lastKnownRangesIndex) {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert lastKnownRangesIndex >= 0;
            this.lastKnownRangesIndex = lastKnownRangesIndex;
        }

        public Loader getLoader(boolean reverse) {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.loaders[reverse ? 1 : 0];
        }
        public void setLoader(boolean reverse, Loader loader) {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert this.loaders[reverse ? 1 : 0] == null || this.loaders[reverse ? 1 : 0].isStopped();
            this.loaders[reverse ? 1 : 0] = loader;
        }
        public void stopLoader(boolean reverse) {
            assert Thread.holdsLock(BatchingKVStore.this);
            final Loader loader = this.getLoader(reverse);
            if (loader != null)
                loader.stop();
        }

        /**
         * Find the entry with at least (inclusive) the given key.
         */
        public KVPair getAtLeast(byte[] key) {
            assert Thread.holdsLock(BatchingKVStore.this);
            int index = Arrays.binarySearch(this.keys, this.minIndex, this.maxIndex, key, ByteUtil.COMPARATOR);
            if (index < 0) {
                if ((index = ~index) >= this.maxIndex)
                    return null;
            }
            return new KVPair(this.keys[index].clone(), this.vals[index].clone());
        }

        /**
         * Find the entry with at most (exclusive) the given key.
         */
        public KVPair getAtMost(byte[] key) {
            assert Thread.holdsLock(BatchingKVStore.this);
            int index = Arrays.binarySearch(this.keys, this.minIndex, this.maxIndex, key, KeyRange::compare);
            if (index < 0)
                index = ~index;
            if (--index < this.minIndex)
                return null;
            return new KVPair(this.keys[index].clone(), this.vals[index].clone());
        }

        /**
         * Get the number of key/value pairs contained in this range.
         */
        public int size() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.maxIndex - this.minIndex;
        }

        /**
         * Get whether the set of key/value pairs contained in this range is empty.
         */
        public boolean isEmpty() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.maxIndex == this.minIndex;
        }

        /**
         * Get whether this range is primoridal (i.e., spans zero keys).
         */
        public boolean isPrimordial() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return KeyRange.compare(this.min, this.max) == 0;
        }

        /**
         * Get the total amount of key and value data stored.
         */
        public long getTotalBytes() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.totalBytes;
        }

        /**
         * Get the time of creation of this range.
         */
        public long getCreationTime() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.creationTime;
        }

        /**
         * Get LRU list entry.
         */
        public RingEntry<KVRange> getLruEntry() {
            assert Thread.holdsLock(BatchingKVStore.this);
            return this.lruEntry;
        }

        public BatchingKVStore getKVStore() {
            return BatchingKVStore.this;
        }

        /**
         * Add the given key/value pair to this range.
         */
        public void add(byte[] key, byte[] val) {

            // Sanity check
            assert Thread.holdsLock(BatchingKVStore.this);
            assert !this.getKeyRange().contains(key);
            assert key != null && val != null;

            // Add key/value pair
            if (ByteUtil.compare(key, this.min) < 0) {
                if (this.minIndex == 0)
                    this.growArrays();
                assert this.minIndex > 0;
                this.minIndex--;
                this.keys[this.minIndex] = key;
                this.vals[this.minIndex] = val;
            } else {
                assert KeyRange.compare(key, this.max) >= 0;
                if (this.maxIndex == this.keys.length)
                    this.growArrays();
                assert this.maxIndex < this.keys.length;
                this.keys[this.maxIndex] = key;
                this.vals[this.maxIndex] = val;
                this.maxIndex++;
            }
            this.totalBytes += key.length + val.length;
        }

        /**
         * Create a merged {@link KVRange} consisting of this and the given following range.
         *
         * <p>
         * The given range must immediately follow this one.
         * This returns a new instance and assumes this and the given node will be no longer used.
         */
        public KVRange merge(KVRange next) {
            assert this.getKVStore() == next.getKVStore();
            assert Thread.holdsLock(this.getKVStore());
            assert this.sanityCheck();
            assert next.sanityCheck();
            assert KeyRange.compare(this.getMax(), next.getMin()) == 0;

            // Build merged range
            final int thisSize = this.size();
            final int nextSize = next.size();
            final int newSize = thisSize + nextSize;
            final long newTotalBytes = this.totalBytes + next.totalBytes;
            final byte[][] newKeys;
            final byte[][] newVals;
            final int newMinIndex;
            if (newSize <= this.keys.length) {

                // Reuse this's arrays, appending next's data at the end
                final int shift = nextSize - (this.keys.length - this.maxIndex);
                if (shift > 0) {
                    System.arraycopy(this.keys, this.minIndex, this.keys, this.minIndex - shift, thisSize);
                    System.arraycopy(this.vals, this.minIndex, this.vals, this.minIndex - shift, thisSize);
                    this.minIndex -= shift;
                    this.maxIndex -= shift;
                }
                System.arraycopy(next.keys, next.minIndex, this.keys, this.maxIndex, nextSize);
                System.arraycopy(next.vals, next.minIndex, this.vals, this.maxIndex, nextSize);
                newKeys = this.keys;
                newVals = this.vals;
                newMinIndex = this.minIndex;
            } else if (newSize <= next.keys.length) {

                // Reuse next's arrays, prepending this's data at the beginning
                final int shift = thisSize - next.minIndex;
                if (shift > 0) {
                    System.arraycopy(next.keys, next.minIndex, next.keys, next.minIndex + shift, nextSize);
                    System.arraycopy(next.vals, next.minIndex, next.vals, next.minIndex + shift, nextSize);
                    next.minIndex += shift;
                    next.maxIndex += shift;
                }
                System.arraycopy(this.keys, this.minIndex, next.keys, next.minIndex - thisSize, thisSize);
                System.arraycopy(this.vals, this.minIndex, next.vals, next.minIndex - thisSize, thisSize);
                newKeys = next.keys;
                newVals = next.vals;
                newMinIndex = next.minIndex - thisSize;
            } else {

                // Create new arrays
                newKeys = new byte[(int)(newSize * ARRAY_GROWTH_FACTOR) + 30][];
                newVals = new byte[newKeys.length][];
                newMinIndex = (newKeys.length - newSize) / 2;
                System.arraycopy(this.keys, this.minIndex, newKeys, newMinIndex, thisSize);
                System.arraycopy(this.vals, this.minIndex, newVals, newMinIndex, thisSize);
                System.arraycopy(next.keys, next.minIndex, newKeys, newMinIndex + thisSize, nextSize);
                System.arraycopy(next.vals, next.minIndex, newVals, newMinIndex + thisSize, nextSize);
            }

            // Create merged range
            final KVRange range = this.getKVStore().new KVRange(this.min, next.max,
              newKeys, newVals, newMinIndex, newMinIndex + newSize, newTotalBytes);

            // Invalidate original ranges
            assert this.invalidate();
            assert next.invalidate();

            // Done
            return range;
        }

        private void growArrays() {
            assert Thread.holdsLock(BatchingKVStore.this);
            final int size = this.size();
            final byte[][] newKeys = new byte[(int)(size * ARRAY_GROWTH_FACTOR) + 30][];
            final byte[][] newVals = new byte[newKeys.length][];
            final int newMinIndex = (newKeys.length - size) / 2;
            System.arraycopy(this.keys, this.minIndex, newKeys, newMinIndex, size);
            System.arraycopy(this.vals, this.minIndex, newVals, newMinIndex, size);
            this.keys = newKeys;
            this.vals = newVals;
            this.minIndex = newMinIndex;
            this.maxIndex = newMinIndex + size;
            assert this.sanityCheck();
        }

        public boolean sanityCheck() {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert this.sanityCheckInternal();
            assert BatchingKVStore.this.ranges.contains(this) : this + " not in " + BatchingKVStore.this.ranges;
            assert this.lruEntry.isAttached() : this + " not in LRU";
            return true;
        }

        private boolean sanityCheckInternal() {
            assert Thread.holdsLock(BatchingKVStore.this);
            assert KeyRange.compare(this.min, this.max) <= 0;
            assert this.keys != null;
            assert this.vals != null;
            assert this.size() >= 0;
            assert this.lastKnownRangesIndex >= 0 : "range=" + this + " ranges=" + BatchingKVStore.this.ranges;
            assert this.keys.length == this.vals.length;
            assert this.size() <= this.keys.length;
            for (int i = this.minIndex; i < this.maxIndex; i++) {
                assert this.keys[i] != null;
                assert this.vals[i] != null;
                final byte[] key = this.keys[i];
                assert KeyRange.compare(key, this.getMin()) >= 0;
                assert KeyRange.compare(key, this.getMax()) < 0;
                assert i == this.minIndex || ByteUtil.compare(key, this.keys[i - 1]) > 0;
            }
            return true;
        }

        // Invalidate this range so sanityCheck() will fail
        private boolean invalidate() {
            assert Thread.holdsLock(BatchingKVStore.this);
            this.keys = null;
            this.vals = null;
            this.minIndex = -1;
            this.maxIndex = -1;
            this.totalBytes = -1;
            this.lastKnownRangesIndex = -1;
            return true;
        }

        // Verify key is the only key in the given range (if key is not null), or the given range is empty (if key is null)
        public boolean containsOnlyKeyInRange(byte[] key, byte[] min, byte[] max) {
            assert Thread.holdsLock(BatchingKVStore.this);
            if (KeyRange.compare(key, this.min) < 0 || KeyRange.compare(key, this.max) >= 0)
                key = null;
            assert KeyRange.compare(min, max) <= 0;
            int searchMin = Arrays.binarySearch(this.keys, this.minIndex, this.maxIndex, min, KeyRange::compare);
            if (searchMin < 0)
                searchMin = ~searchMin;
            int searchMax = Arrays.binarySearch(this.keys, this.minIndex, this.maxIndex, max, KeyRange::compare);
            if (searchMax < 0)
                searchMax = ~searchMax;
            if (key == null)
                return searchMin == searchMax;
            return searchMax == searchMin + 1 && KeyRange.compare(this.keys[searchMin], key) == 0;
        }

        private void debug(String msg, Object... params) {
            BatchingKVStore.this.log.debug(this + ": " + msg, params);
        }

        private void trace(String msg, Object... params) {
            BatchingKVStore.this.log.trace(this + ": " + msg, params);
        }

    // Object

        @Override
        public String toString() {
            synchronized (BatchingKVStore.this) {
                final StringBuilder keysBuf = new StringBuilder();
                if (!this.isEmpty()) {
                    for (int i = this.minIndex; i < this.maxIndex; i++) {
                        if (this.maxIndex - i > 5 && i - this.minIndex > 5) {
                            if (i - this.minIndex == 6)
                                keysBuf.append("...");
                            continue;
                        }
                        keysBuf.append(',');
                        if (i == this.minIndex)
                            keysBuf.append("keys=");
                        keysBuf.append(ByteUtil.toString(this.keys[i]));
                    }
                }
                return "KVRange"
                  + "[min=" + ByteUtil.toString(this.min)
                  + ",max=" + ByteUtil.toString(this.max)
                  + keysBuf
                  + (this.loaders[0] != null ? ",fwdLoader=" + this.loaders[0] : "")
                  + (this.loaders[1] != null ? ",revLoader=" + this.loaders[1] : "")
                  + "]";
            }
        }
    }
}
