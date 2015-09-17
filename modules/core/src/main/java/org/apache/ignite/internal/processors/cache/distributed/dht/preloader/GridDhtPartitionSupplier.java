/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.preloader;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.CacheRebalancingEvent;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.managers.deployment.GridDeploymentInfo;
import org.apache.ignite.internal.managers.eventstorage.GridLocalEventListener;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheEntryInfo;
import org.apache.ignite.internal.processors.cache.GridCacheEntryInfoCollectSwapListener;
import org.apache.ignite.internal.processors.cache.GridCacheSwapEntry;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCacheEntry;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.util.lang.GridCloseableIterator;
import org.apache.ignite.internal.util.typedef.CI2;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgnitePredicate;
import org.jsr166.ConcurrentHashMap8;

import static org.apache.ignite.events.EventType.EVT_CACHE_REBALANCE_STOPPED;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState.OWNING;

/**
 * Thread pool for supplying partitions to demanding nodes.
 */
class GridDhtPartitionSupplier {
    /** */
    private final GridCacheContext<?, ?> cctx;

    /** */
    private final IgniteLogger log;

    /** */
    private GridDhtPartitionTopology top;

    /** */
    private final boolean depEnabled;

    /** Preload predicate. */
    private IgnitePredicate<GridCacheEntryInfo> preloadPred;

    /** Supply context map. */
    private final ConcurrentHashMap8<T2, SupplyContext> scMap = new ConcurrentHashMap8<>();

    /** Rebalancing listener. */
    private GridLocalEventListener lsnr;

    /**
     * @param cctx Cache context.
     */
    GridDhtPartitionSupplier(GridCacheContext<?, ?> cctx) {
        assert cctx != null;

        this.cctx = cctx;

        log = cctx.logger(getClass());

        top = cctx.dht().topology();

        depEnabled = cctx.gridDeploy().enabled();
    }

    /**
     *
     */
    void start() {
        lsnr = new GridLocalEventListener() {
            @Override public void onEvent(Event evt) {
                int lsnrCnt = cctx.gridConfig().getRebalanceThreadPoolSize();

                for (int idx = 0; idx < lsnrCnt; idx++) {
                    ClusterNode node;
                    if (evt instanceof CacheRebalancingEvent)
                        node = ((CacheRebalancingEvent)evt).discoveryNode();
                    else if (evt instanceof DiscoveryEvent)
                        node = ((DiscoveryEvent)evt).eventNode();
                    else {
                        assert false;

                        return;
                    }

                    T2<UUID, Integer> scId = new T2<>(node.id(), idx);

                    tryClearContext(scMap, scId, log);
                }
            }
        };

        cctx.events().addListener(lsnr, EVT_NODE_LEFT, EVT_NODE_FAILED, EVT_CACHE_REBALANCE_STOPPED);

        startOldListeners();
    }

    /**
     *
     */
    void stop() {
        cctx.events().removeListener(lsnr);

        stopOldListeners();
    }

    /**
     * Clear context by id.
     *
     * @param map Context map.
     * @param scId Context id.
     * @param log Logger.
     */
    private static void tryClearContext(
        ConcurrentHashMap8<T2, SupplyContext> map,
        T2<UUID, Integer> scId,
        IgniteLogger log) {
        SupplyContext sc = map.get(scId);

        if (sc != null) {
            Iterator it = sc.entryIt;

            if (it != null && it instanceof GridCloseableIterator && !((GridCloseableIterator)it).isClosed()) {
                try {
                    ((GridCloseableIterator)it).close();//todo: is it ok to close twice?
                }
                catch (IgniteCheckedException e) {
                    log.error("Iterator close failed.", e);
                }
            }
        }

        map.remove(scId, sc);
    }

    /**
     * Sets preload predicate for supply pool.
     *
     * @param preloadPred Preload predicate.
     */
    void preloadPredicate(IgnitePredicate<GridCacheEntryInfo> preloadPred) {
        this.preloadPred = preloadPred;
    }

    /**
     * @param d Demand message.
     * @param idx Index.
     * @param id Node uuid.
     */
    public void handleDemandMessage(int idx, UUID id, GridDhtPartitionDemandMessage d) {
        assert d != null;
        assert id != null;

        if (!cctx.affinity().affinityTopologyVersion().equals(d.topologyVersion()))
            return;

        GridDhtPartitionSupplyMessageV2 s = new GridDhtPartitionSupplyMessageV2(d.workerId(),
            d.updateSequence(), cctx.cacheId(), d.topologyVersion());

        ClusterNode node = cctx.discovery().node(id);

        T2<UUID, Integer> scId = new T2<>(id, idx);

        try {
            SupplyContext sctx = scMap.get(scId);

            if (sctx == null) {
                if (d.partitions().isEmpty())
                    return;
            }
            else {
                if (!sctx.top.equals(d.topologyVersion())) {
                    tryClearContext(scMap, scId, log);

                    sctx = scMap.get(scId);
                }
            }

            long bCnt = 0;

            int phase = 0;

            boolean newReq = true;

            long maxBatchesCnt = cctx.config().getRebalanceBatchesCount();

            if (sctx != null) {
                phase = sctx.phase;

                maxBatchesCnt = 1;
            }

            Iterator<Integer> partIt = sctx != null ? sctx.partIt : d.partitions().iterator();

            while ((sctx != null && newReq) || partIt.hasNext()) {
                int part = sctx != null && newReq ? sctx.part : partIt.next();

                newReq = false;

                GridDhtLocalPartition loc = top.localPartition(part, d.topologyVersion(), false);

                if (loc == null || loc.state() != OWNING || !loc.reserve()) {
                    // Reply with partition of "-1" to let sender know that
                    // this node is no longer an owner.
                    s.missed(part);

                    if (log.isDebugEnabled())
                        log.debug("Requested partition is not owned by local node [part=" + part +
                            ", demander=" + id + ']');

                    continue;
                }

                GridCacheEntryInfoCollectSwapListener swapLsnr = null;

                try {
                    if (phase == 0 && cctx.isSwapOrOffheapEnabled()) {
                        swapLsnr = new GridCacheEntryInfoCollectSwapListener(log);

                        cctx.swap().addOffHeapListener(part, swapLsnr);
                        cctx.swap().addSwapListener(part, swapLsnr);
                    }

                    boolean partMissing = false;

                    if (phase == 0)
                        phase = 1;

                    if (phase == 1) {
                        Iterator<GridDhtCacheEntry> entIt = sctx != null ?
                            (Iterator<GridDhtCacheEntry>)sctx.entryIt : loc.entries().iterator();

                        while (entIt.hasNext()) {
                            if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                // Demander no longer needs this partition, so we send '-1' partition and move on.
                                s.missed(part);

                                if (log.isDebugEnabled())
                                    log.debug("Demanding node does not need requested partition [part=" + part +
                                        ", nodeId=" + id + ']');

                                partMissing = true;

                                break;
                            }

                            if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                if (++bCnt >= maxBatchesCnt) {
                                    saveSupplyContext(scId, phase, partIt, part, entIt, swapLsnr, d.topologyVersion());

                                    swapLsnr = null;

                                    reply(node, d, s);

                                    return;
                                }
                                else {
                                    if (!reply(node, d, s))
                                        return;

                                    s = new GridDhtPartitionSupplyMessageV2(d.workerId(), d.updateSequence(),
                                        cctx.cacheId(), d.topologyVersion());
                                }
                            }

                            GridCacheEntryEx e = entIt.next();

                            GridCacheEntryInfo info = e.info();

                            if (info != null && !info.isNew()) {
                                if (preloadPred == null || preloadPred.apply(info))
                                    s.addEntry(part, info, cctx);
                                else if (log.isDebugEnabled())
                                    log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                        info);
                            }
                        }

                        if (partMissing)
                            continue;

                    }

                    if (phase == 1) {
                        phase = 2;

                        if (sctx != null) {
                            sctx = new SupplyContext(
                                phase,
                                partIt,
                                null,
                                swapLsnr,
                                part,
                                d.topologyVersion());
                        }
                    }

                    if (phase == 2 && cctx.isSwapOrOffheapEnabled()) {
                        GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> iter =
                            sctx != null && sctx.entryIt != null ?
                                (GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>>)sctx.entryIt :
                                cctx.swap().iterator(part);

                        // Iterator may be null if space does not exist.
                        if (iter != null) {
                            boolean prepared = false;

                            while (iter.hasNext()) {
                                if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                    // Demander no longer needs this partition,
                                    // so we send '-1' partition and move on.
                                    s.missed(part);

                                    if (log.isDebugEnabled())
                                        log.debug("Demanding node does not need requested partition " +
                                            "[part=" + part + ", nodeId=" + id + ']');

                                    partMissing = true;

                                    break; // For.
                                }

                                if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                    if (++bCnt >= maxBatchesCnt) {
                                        saveSupplyContext(scId, phase, partIt, part, iter, swapLsnr, d.topologyVersion());

                                        swapLsnr = null;

                                        reply(node, d, s);

                                        return;
                                    }
                                    else {
                                        if (!reply(node, d, s))
                                            return;

                                        s = new GridDhtPartitionSupplyMessageV2(d.workerId(), d.updateSequence(),
                                            cctx.cacheId(), d.topologyVersion());
                                    }
                                }

                                Map.Entry<byte[], GridCacheSwapEntry> e = iter.next();

                                GridCacheSwapEntry swapEntry = e.getValue();

                                GridCacheEntryInfo info = new GridCacheEntryInfo();

                                info.keyBytes(e.getKey());
                                info.ttl(swapEntry.ttl());
                                info.expireTime(swapEntry.expireTime());
                                info.version(swapEntry.version());
                                info.value(swapEntry.value());

                                if (preloadPred == null || preloadPred.apply(info))
                                    s.addEntry0(part, info, cctx);
                                else {
                                    if (log.isDebugEnabled())
                                        log.debug("Rebalance predicate evaluated to false (will not send " +
                                            "cache entry): " + info);

                                    continue;
                                }

                                // Need to manually prepare cache message.
                                if (depEnabled && !prepared) {
                                    ClassLoader ldr = swapEntry.keyClassLoaderId() != null ?
                                        cctx.deploy().getClassLoader(swapEntry.keyClassLoaderId()) :
                                        swapEntry.valueClassLoaderId() != null ?
                                            cctx.deploy().getClassLoader(swapEntry.valueClassLoaderId()) :
                                            null;

                                    if (ldr == null)
                                        continue;

                                    if (ldr instanceof GridDeploymentInfo) {
                                        s.prepare((GridDeploymentInfo)ldr);

                                        prepared = true;
                                    }
                                }
                            }

                            iter.close();

                            if (partMissing)
                                continue;
                        }
                    }

                    if (swapLsnr == null && sctx != null)
                        swapLsnr = sctx.swapLsnr;

                    // Stop receiving promote notifications.
                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }

                    if (phase == 2) {
                        phase = 3;

                        if (sctx != null) {
                            sctx = new SupplyContext(
                                phase,
                                partIt,
                                null,
                                null,
                                part,
                                d.topologyVersion());
                        }
                    }

                    if (phase == 3 && swapLsnr != null) {
                        Collection<GridCacheEntryInfo> entries = swapLsnr.entries();

                        swapLsnr = null;

                        Iterator<GridCacheEntryInfo> lsnrIt = sctx != null && sctx.entryIt != null ?
                            (Iterator<GridCacheEntryInfo>)sctx.entryIt : entries.iterator();

                        while (lsnrIt.hasNext()) {
                            if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                // Demander no longer needs this partition,
                                // so we send '-1' partition and move on.
                                s.missed(part);

                                if (log.isDebugEnabled())
                                    log.debug("Demanding node does not need requested partition " +
                                        "[part=" + part + ", nodeId=" + id + ']');

                                // No need to continue iteration over swap entries.
                                break;
                            }

                            if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                if (++bCnt >= maxBatchesCnt) {
                                    saveSupplyContext(scId, phase, partIt, part, lsnrIt, swapLsnr, d.topologyVersion());

                                    swapLsnr = null;

                                    reply(node, d, s);

                                    return;
                                }
                                else {
                                    if (!reply(node, d, s))
                                        return;

                                    s = new GridDhtPartitionSupplyMessageV2(d.workerId(), d.updateSequence(),
                                        cctx.cacheId(), d.topologyVersion());
                                }
                            }

                            GridCacheEntryInfo info = lsnrIt.next();

                            if (preloadPred == null || preloadPred.apply(info))
                                s.addEntry(part, info, cctx);
                            else if (log.isDebugEnabled())
                                log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                    info);
                        }
                    }

                    // Mark as last supply message.
                    s.last(part);

                    phase = 0;

                    sctx = null;
                }
                finally {
                    loc.release();

                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }
                }
            }

            reply(node, d, s);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send partition supply message to node: " + id, e);
        }
    }

    /**
     * @param n Node.
     * @param d DemandMessage
     * @param s Supply message.
     * @return {@code True} if message was sent, {@code false} if recipient left grid.
     * @throws IgniteCheckedException If failed.
     */
    private boolean reply(ClusterNode n, GridDhtPartitionDemandMessage d, GridDhtPartitionSupplyMessageV2 s)
        throws IgniteCheckedException {

        try {
            if (log.isDebugEnabled())
                log.debug("Replying to partition demand [node=" + n.id() + ", demand=" + d + ", supply=" + s + ']');

            cctx.io().sendOrderedMessage(n, d.topic(), s, cctx.ioPolicy(), d.timeout());

            // Throttle preloading.
            if (cctx.config().getRebalanceThrottle() > 0)
                U.sleep(cctx.config().getRebalanceThrottle());

            return true;
        }
        catch (ClusterTopologyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Failed to send partition supply message because node left grid: " + n.id());

            return false;
        }
    }

    /**
     * @param t Tuple.
     * @param phase Phase.
     * @param partIt Partition it.
     * @param part Partition.
     * @param entryIt Entry it.
     * @param swapLsnr Swap listener.
     */
    private void saveSupplyContext(
        T2 t,
        int phase,
        Iterator<Integer> partIt,
        int part,
        Iterator<?> entryIt, GridCacheEntryInfoCollectSwapListener swapLsnr,
        AffinityTopologyVersion top) {
        scMap.put(t, new SupplyContext(phase, partIt, entryIt, swapLsnr, part, top));
    }

    /**
     * Supply context.
     */
    private static class SupplyContext {
        /** Phase. */
        private final int phase;

        /** Partition iterator. */
        private final Iterator<Integer> partIt;

        /** Entry iterator. */
        private final Iterator<?> entryIt;

        /** Swap listener. */
        private final GridCacheEntryInfoCollectSwapListener swapLsnr;

        /** Partition. */
        private final int part;

        /** Topology version. */
        private final AffinityTopologyVersion top;

        /**
         * @param phase Phase.
         * @param partIt Partition iterator.
         * @param entryIt Entry iterator.
         * @param swapLsnr Swap listener.
         * @param part Partition.
         */
        public SupplyContext(int phase, Iterator<Integer> partIt, Iterator<?> entryIt,
            GridCacheEntryInfoCollectSwapListener swapLsnr, int part, AffinityTopologyVersion top) {
            this.phase = phase;
            this.partIt = partIt;
            this.entryIt = entryIt;
            this.swapLsnr = swapLsnr;
            this.part = part;
            this.top = top;
        }
    }

    @Deprecated//Backward compatibility. To be removed in future.
    public void startOldListeners() {
        if (!cctx.kernalContext().clientNode() && cctx.rebalanceEnabled()) {

            cctx.io().addHandler(cctx.cacheId(), GridDhtPartitionDemandMessage.class, new CI2<UUID, GridDhtPartitionDemandMessage>() {
                @Override public void apply(UUID id, GridDhtPartitionDemandMessage m) {
                    processOldDemandMessage(m, id);
                }
            });
        }
    }

    @Deprecated//Backward compatibility. To be removed in future.
    public void stopOldListeners() {
        if (!cctx.kernalContext().clientNode() && cctx.rebalanceEnabled()) {

            cctx.io().removeHandler(cctx.cacheId(), GridDhtPartitionDemandMessage.class);
        }
    }

    /**
     * @param d D.
     * @param id Id.
     */
    @Deprecated//Backward compatibility. To be removed in future.
    private void processOldDemandMessage(GridDhtPartitionDemandMessage d, UUID id) {
        GridDhtPartitionSupplyMessage s = new GridDhtPartitionSupplyMessage(d.workerId(),
            d.updateSequence(), cctx.cacheId());

        ClusterNode node = cctx.node(id);

        long preloadThrottle = cctx.config().getRebalanceThrottle();

        boolean ack = false;

        try {
            for (int part : d.partitions()) {
                GridDhtLocalPartition loc = top.localPartition(part, d.topologyVersion(), false);

                if (loc == null || loc.state() != OWNING || !loc.reserve()) {
                    // Reply with partition of "-1" to let sender know that
                    // this node is no longer an owner.
                    s.missed(part);

                    if (log.isDebugEnabled())
                        log.debug("Requested partition is not owned by local node [part=" + part +
                            ", demander=" + id + ']');

                    continue;
                }

                GridCacheEntryInfoCollectSwapListener swapLsnr = null;

                try {
                    if (cctx.isSwapOrOffheapEnabled()) {
                        swapLsnr = new GridCacheEntryInfoCollectSwapListener(log);

                        cctx.swap().addOffHeapListener(part, swapLsnr);
                        cctx.swap().addSwapListener(part, swapLsnr);
                    }

                    boolean partMissing = false;

                    for (GridCacheEntryEx e : loc.entries()) {
                        if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                            // Demander no longer needs this partition, so we send '-1' partition and move on.
                            s.missed(part);

                            if (log.isDebugEnabled())
                                log.debug("Demanding node does not need requested partition [part=" + part +
                                    ", nodeId=" + id + ']');

                            partMissing = true;

                            break;
                        }

                        if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                            ack = true;

                            if (!replyOld(node, d, s))
                                return;

                            // Throttle preloading.
                            if (preloadThrottle > 0)
                                U.sleep(preloadThrottle);

                            s = new GridDhtPartitionSupplyMessage(d.workerId(), d.updateSequence(),
                                cctx.cacheId());
                        }

                        GridCacheEntryInfo info = e.info();

                        if (info != null && !info.isNew()) {
                            if (preloadPred == null || preloadPred.apply(info))
                                s.addEntry(part, info, cctx);
                            else if (log.isDebugEnabled())
                                log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                    info);
                        }
                    }

                    if (partMissing)
                        continue;

                    if (cctx.isSwapOrOffheapEnabled()) {
                        GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> iter =
                            cctx.swap().iterator(part);

                        // Iterator may be null if space does not exist.
                        if (iter != null) {
                            try {
                                boolean prepared = false;

                                for (Map.Entry<byte[], GridCacheSwapEntry> e : iter) {
                                    if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                        // Demander no longer needs this partition,
                                        // so we send '-1' partition and move on.
                                        s.missed(part);

                                        if (log.isDebugEnabled())
                                            log.debug("Demanding node does not need requested partition " +
                                                "[part=" + part + ", nodeId=" + id + ']');

                                        partMissing = true;

                                        break; // For.
                                    }

                                    if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                        ack = true;

                                        if (!replyOld(node, d, s))
                                            return;

                                        // Throttle preloading.
                                        if (preloadThrottle > 0)
                                            U.sleep(preloadThrottle);

                                        s = new GridDhtPartitionSupplyMessage(d.workerId(),
                                            d.updateSequence(), cctx.cacheId());
                                    }

                                    GridCacheSwapEntry swapEntry = e.getValue();

                                    GridCacheEntryInfo info = new GridCacheEntryInfo();

                                    info.keyBytes(e.getKey());
                                    info.ttl(swapEntry.ttl());
                                    info.expireTime(swapEntry.expireTime());
                                    info.version(swapEntry.version());
                                    info.value(swapEntry.value());

                                    if (preloadPred == null || preloadPred.apply(info))
                                        s.addEntry0(part, info, cctx);
                                    else {
                                        if (log.isDebugEnabled())
                                            log.debug("Rebalance predicate evaluated to false (will not send " +
                                                "cache entry): " + info);

                                        continue;
                                    }

                                    // Need to manually prepare cache message.
                                    if (depEnabled && !prepared) {
                                        ClassLoader ldr = swapEntry.keyClassLoaderId() != null ?
                                            cctx.deploy().getClassLoader(swapEntry.keyClassLoaderId()) :
                                            swapEntry.valueClassLoaderId() != null ?
                                                cctx.deploy().getClassLoader(swapEntry.valueClassLoaderId()) :
                                                null;

                                        if (ldr == null)
                                            continue;

                                        if (ldr instanceof GridDeploymentInfo) {
                                            s.prepare((GridDeploymentInfo)ldr);

                                            prepared = true;
                                        }
                                    }
                                }

                                if (partMissing)
                                    continue;
                            }
                            finally {
                                iter.close();
                            }
                        }
                    }

                    // Stop receiving promote notifications.
                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }

                    if (swapLsnr != null) {
                        Collection<GridCacheEntryInfo> entries = swapLsnr.entries();

                        swapLsnr = null;

                        for (GridCacheEntryInfo info : entries) {
                            if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                // Demander no longer needs this partition,
                                // so we send '-1' partition and move on.
                                s.missed(part);

                                if (log.isDebugEnabled())
                                    log.debug("Demanding node does not need requested partition " +
                                        "[part=" + part + ", nodeId=" + id + ']');

                                // No need to continue iteration over swap entries.
                                break;
                            }

                            if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                ack = true;

                                if (!replyOld(node, d, s))
                                    return;

                                s = new GridDhtPartitionSupplyMessage(d.workerId(),
                                    d.updateSequence(),
                                    cctx.cacheId());
                            }

                            if (preloadPred == null || preloadPred.apply(info))
                                s.addEntry(part, info, cctx);
                            else if (log.isDebugEnabled())
                                log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                    info);
                        }
                    }

                    // Mark as last supply message.
                    s.last(part);

                    if (ack) {
                        s.markAck();

                        break; // Partition for loop.
                    }
                }
                finally {
                    loc.release();

                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }
                }
            }

            replyOld(node, d, s);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send partition supply message to node: " + node.id(), e);
        }
    }

    /**
     * @param n Node.
     * @param d Demand message.
     * @param s Supply message.
     * @return {@code True} if message was sent, {@code false} if recipient left grid.
     * @throws IgniteCheckedException If failed.
     */
    @Deprecated//Backward compatibility. To be removed in future.
    private boolean replyOld(ClusterNode n, GridDhtPartitionDemandMessage d, GridDhtPartitionSupplyMessage s)
        throws IgniteCheckedException {
        try {
            if (log.isDebugEnabled())
                log.debug("Replying to partition demand [node=" + n.id() + ", demand=" + d + ", supply=" + s + ']');

            cctx.io().sendOrderedMessage(n, d.topic(), s, cctx.ioPolicy(), d.timeout());

            return true;
        }
        catch (ClusterTopologyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Failed to send partition supply message because node left grid: " + n.id());

            return false;
        }
    }
}