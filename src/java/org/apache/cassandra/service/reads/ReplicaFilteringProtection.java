/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service.reads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.TimeUnit;
import java.util.Queue;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Columns;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.RegularAndStaticColumns;
import org.apache.cassandra.db.SinglePartitionReadCommand;
import org.apache.cassandra.db.filter.ClusteringIndexFilter;
import org.apache.cassandra.db.filter.ClusteringIndexNamesFilter;
import org.apache.cassandra.db.filter.DataLimits;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.PartitionIterators;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.RangeTombstoneMarker;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Rows;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.db.rows.UnfilteredRowIterators;
import org.apache.cassandra.exceptions.OverloadedException;
import org.apache.cassandra.exceptions.ReadTimeoutException;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.locator.Endpoints;
import org.apache.cassandra.locator.EndpointsForToken;
import org.apache.cassandra.locator.Replica;
import org.apache.cassandra.locator.ReplicaPlan;
import org.apache.cassandra.locator.ReplicaPlans;
import org.apache.cassandra.metrics.TableMetrics;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.ClientWarn;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.reads.repair.NoopReadRepair;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.transport.Dispatcher;
import org.apache.cassandra.utils.NoSpamLogger;
import org.apache.cassandra.utils.btree.BTreeSet;

/**
 * Helper in charge of collecting additional queries to be done on the coordinator to protect against invalid results
 * being included due to replica-side filtering (secondary indexes or {@code ALLOW * FILTERING}).
 * <p>
 * When using replica-side filtering with CL > ONE, a replica can send a stale result satisfying the filter, while 
 * updated replicas won't send a corresponding tombstone to discard that result during reconciliation. This helper 
 * identifies the rows in a replica response that don't have a corresponding row in other replica responses (or don't
 * have corresponding cell values), and requests them by primary key on the "silent" replicas in a second fetch round.
 * 
 * @see <a href="https://issues.apache.org/jira/browse/CASSANDRA-8272">CASSANDRA-8272</a>
 * @see <a href="https://issues.apache.org/jira/browse/CASSANDRA-8273">CASSANDRA-8273</a>
 * @see <a href="https://issues.apache.org/jira/browse/CASSANDRA-15907">CASSANDRA-15907</a>
 * @see <a href="https://issues.apache.org/jira/browse/CASSANDRA-19018">CASSANDRA-19018</a>
 */
public class ReplicaFilteringProtection<E extends Endpoints<E>>
{
    private static final Logger logger = LoggerFactory.getLogger(ReplicaFilteringProtection.class);
    private static final NoSpamLogger oneMinuteLogger = NoSpamLogger.getLogger(logger, 1, TimeUnit.MINUTES);

    private static final Function<UnfilteredRowIterator, EncodingStats> NULL_TO_NO_STATS =
        rowIterator -> rowIterator == null ? EncodingStats.NO_STATS : rowIterator.stats();

    private final Keyspace keyspace;
    private final ReadCommand command;
    private final ConsistencyLevel consistency;
    private final Dispatcher.RequestTime requestTime;
    private final E sources;
    private final TableMetrics tableMetrics;

    private final int cachedRowsWarnThreshold;
    private final int cachedRowsFailThreshold;

    /** Tracks whether or not we've already hit the warning threshold while evaluating a partition. */
    private boolean hitWarningThreshold = false;

    private int currentRowsCached = 0; // tracks the current number of cached rows
    private int maxRowsCached = 0; // tracks the high watermark for the number of cached rows

    /**
     * Per-source list of the pending partitions seen by the merge listener, to be merged with the extra fetched rows.
     */
    private final List<Queue<PartitionBuilder>> originalPartitions;

    ReplicaFilteringProtection(Keyspace keyspace,
                               ReadCommand command,
                               ConsistencyLevel consistency,
                               Dispatcher.RequestTime requestTime,
                               E sources,
                               int cachedRowsWarnThreshold,
                               int cachedRowsFailThreshold)
    {
        this.keyspace = keyspace;
        this.command = command;
        this.consistency = consistency;
        this.requestTime = requestTime;
        this.sources = sources;
        this.originalPartitions = new ArrayList<>(sources.size());

        for (int i = 0; i < sources.size(); i++)
        {
            originalPartitions.add(new ArrayDeque<>());
        }

        tableMetrics = ColumnFamilyStore.metricsFor(command.metadata().id);

        this.cachedRowsWarnThreshold = cachedRowsWarnThreshold;
        this.cachedRowsFailThreshold = cachedRowsFailThreshold;
    }

    private UnfilteredPartitionIterator executeReadCommand(ReadCommand cmd, Replica source, ReplicaPlan.Shared<EndpointsForToken, ReplicaPlan.ForTokenRead> replicaPlan)
    {
        @SuppressWarnings("unchecked")
        DataResolver<EndpointsForToken, ReplicaPlan.ForTokenRead> resolver =
            new DataResolver<>(cmd, replicaPlan, (NoopReadRepair<EndpointsForToken, ReplicaPlan.ForTokenRead>) NoopReadRepair.instance, requestTime);

        ReadCallback<EndpointsForToken, ReplicaPlan.ForTokenRead> handler = new ReadCallback<>(resolver, cmd, replicaPlan, requestTime);

        if (source.isSelf())
        {
            Stage.READ.maybeExecuteImmediately(new StorageProxy.LocalReadRunnable(cmd, handler, requestTime));
        }
        else
        {
            if (source.isTransient())
                cmd = cmd.copyAsTransientQuery(source);
            MessagingService.instance().sendWithCallback(cmd.createMessage(false, requestTime), source.endpoint(), handler);
        }

        // We don't call handler.get() because we want to preserve tombstones since we're still in the middle of merging node results.
        handler.awaitResults();
        assert resolver.getMessages().size() == 1;
        return resolver.getMessages().get(0).payload.makeIterator(command);
    }

    /**
     * This listener tracks both the accepted data and the primary keys of the rows that may be incomplete.
     * That way, once the query results are merged using this listener, subsequent calls to
     * {@link #queryProtectedPartitions(PartitionIterator, int)} will use the collected data to return a copy of the
     * data originally collected from the specified replica, completed with the potentially outdated rows.
     */
    UnfilteredPartitionIterators.MergeListener mergeController()
    {
        return new UnfilteredPartitionIterators.MergeListener()
        {
            @Override
            public void close()
            {
                // If we hit the failure threshold before consuming a single partition, record the current rows cached.
                tableMetrics.rfpRowsCachedPerQuery.update(Math.max(currentRowsCached, maxRowsCached));
            }

            @Override
            public UnfilteredRowIterators.MergeListener getRowMergeListener(DecoratedKey partitionKey, List<UnfilteredRowIterator> versions)
            {
                List<PartitionBuilder> builders = new ArrayList<>(sources.size());
                RegularAndStaticColumns columns = columns(versions);
                EncodingStats stats = EncodingStats.merge(versions, NULL_TO_NO_STATS);

                for (int i = 0; i < sources.size(); i++)
                    builders.add(i, new PartitionBuilder(partitionKey, sources.get(i), columns, stats));

                boolean[] silentRowAt = new boolean[builders.size()];
                boolean[] silentColumnAt = new boolean[builders.size()];

                return new UnfilteredRowIterators.MergeListener()
                {
                    @Override
                    public void onMergedPartitionLevelDeletion(DeletionTime mergedDeletion, DeletionTime[] versions)
                    {
                        // cache the deletion time versions to be able to regenerate the original row iterator
                        for (int i = 0; i < versions.length; i++)
                            builders.get(i).setDeletionTime(versions[i]);
                    }

                    @Override
                    public void onMergedRows(Row merged, Row[] versions)
                    {
                        // Cache the row versions to be able to regenerate the original row iterator:
                        for (int i = 0; i < versions.length; i++)
                            builders.get(i).addRow(versions[i]);

                        // If all versions are empty, there's no divergence to resolve:
                        if (merged.isEmpty())
                            return;

                        Arrays.fill(silentRowAt, false);

                        // Mark replicas silent if they provide no data for the row:
                        for (int i = 0; i < versions.length; i++)
                            if (versions[i] == null || (merged.isStatic() && versions[i].isEmpty()))
                                silentRowAt[i] = true;

                        // Even if there are no completely missing rows, replicas may still be silent about individual
                        // columns, so we need to check for divergence at the column level:
                        for (ColumnMetadata column : merged.isStatic() ? columns.statics : columns.regulars)
                        {
                            Arrays.fill(silentColumnAt, false);
                            boolean allSilent = true;

                            for (int i = 0; i < versions.length; i++)
                            {
                                // If the version at this replica is null, we've already marked it as silent:
                                if (versions[i] != null && versions[i].getColumnData(column) == null)
                                    silentColumnAt[i] = true;
                                else
                                    allSilent = false;
                            }

                            for (int i = 0; i < versions.length; i++)
                                // Mark the replica silent if it is silent about this column and there is actually 
                                // divergence between the replicas. (i.e. If all replicas are silent for this 
                                // column, there is nothing to fetch to complete the row anyway.)
                                silentRowAt[i] |= silentColumnAt[i] && !allSilent;
                        }

                        for (int i = 0; i < silentRowAt.length; i++)
                            if (silentRowAt[i])
                                builders.get(i).addToFetch(merged);
                    }

                    @Override
                    public void onMergedRangeTombstoneMarkers(RangeTombstoneMarker merged, RangeTombstoneMarker[] versions)
                    {
                        // cache the marker versions to be able to regenerate the original row iterator
                        for (int i = 0; i < versions.length; i++)
                            builders.get(i).addRangeTombstoneMarker(versions[i]);
                    }

                    @Override
                    public void close()
                    {
                        for (int i = 0; i < sources.size(); i++)
                            originalPartitions.get(i).add(builders.get(i));
                    }
                };
            }
        };
    }

    private void incrementCachedRows()
    {
        currentRowsCached++;

        if (currentRowsCached == cachedRowsFailThreshold + 1)
        {
            String message = String.format("Replica filtering protection has cached over %d rows during query %s. " +
                                           "(See 'cached_replica_rows_fail_threshold' in cassandra.yaml.)",
                                           cachedRowsFailThreshold, command.toCQLString());

            logger.error(message);
            Tracing.trace(message);
            throw new OverloadedException(message);
        }
        else if (currentRowsCached == cachedRowsWarnThreshold + 1 && !hitWarningThreshold)
        {
            hitWarningThreshold = true;

            String message = String.format("Replica filtering protection has cached over %d rows during query %s. " +
                                           "(See 'cached_replica_rows_warn_threshold' in cassandra.yaml.)",
                                           cachedRowsWarnThreshold, command.toCQLString());

            ClientWarn.instance.warn(message);
            oneMinuteLogger.warn(message);
            Tracing.trace(message);
        }
    }

    private void releaseCachedRows(int count)
    {
        maxRowsCached = Math.max(maxRowsCached, currentRowsCached);
        currentRowsCached -= count;
    }

    private static RegularAndStaticColumns columns(List<UnfilteredRowIterator> versions)
    {
        Columns statics = Columns.NONE;
        Columns regulars = Columns.NONE;
        for (UnfilteredRowIterator iter : versions)
        {
            if (iter == null)
                continue;

            RegularAndStaticColumns cols = iter.columns();
            statics = statics.mergeTo(cols.statics);
            regulars = regulars.mergeTo(cols.regulars);
        }
        return new RegularAndStaticColumns(statics, regulars);
    }

    /**
     * Returns the protected results for the specified replica. These are generated fetching the extra rows and merging
     * them with the cached original filtered results for that replica.
     *
     * @param merged the first iteration partitions, that should have been read used with the {@link #mergeController()}
     * @param source the source
     * @return the protected results for the specified replica
     */
    UnfilteredPartitionIterator queryProtectedPartitions(PartitionIterator merged, int source)
    {
        return new UnfilteredPartitionIterator()
        {
            final Queue<PartitionBuilder> partitions = originalPartitions.get(source);

            @Override
            public TableMetadata metadata()
            {
                return command.metadata();
            }

            @Override
            public void close() { }

            @Override
            public boolean hasNext()
            {
                // If there are no cached partition builders for this source, advance the first phase iterator, which
                // will force the RFP merge listener to load at least the next protected partition.
                if (partitions.isEmpty())
                {
                    PartitionIterators.consumeNext(merged);
                }

                return !partitions.isEmpty();
            }

            @Override
            public UnfilteredRowIterator next()
            {
                PartitionBuilder builder = partitions.poll();
                assert builder != null;
                return builder.protectedPartition();
            }
        };
    }

    private class PartitionBuilder
    {
        private final DecoratedKey key;
        private final Replica source;
        private final RegularAndStaticColumns columns;
        private final EncodingStats stats;

        private DeletionTime deletionTime;
        private Row staticRow = Rows.EMPTY_STATIC_ROW;
        private final Queue<Unfiltered> contents = new ArrayDeque<>();
        private BTreeSet.Builder<Clustering<?>> toFetch;
        private int partitionRowsCached;

        private boolean unresolvedStatic = false;

        private PartitionBuilder(DecoratedKey key, Replica source, RegularAndStaticColumns columns, EncodingStats stats)
        {
            this.key = key;
            this.source = source;
            this.columns = columns;
            this.stats = stats;
        }

        private void setDeletionTime(DeletionTime deletionTime)
        {
            this.deletionTime = deletionTime;
        }

        private void addRow(Row row)
        {
            partitionRowsCached++;

            incrementCachedRows();

            // Note that even null rows are counted against the row caching limit. The assumption is that
            // a subsequent protection query will later fetch the row onto the heap anyway.
            if (row == null)
                return;

            if (row.isStatic())
                staticRow = row;
            else
                contents.add(row);
        }

        private void addRangeTombstoneMarker(RangeTombstoneMarker marker)
        {
            if (marker != null)
                contents.add(marker);
        }

        private void addToFetch(Row row)
        {
            if (toFetch == null)
                toFetch = BTreeSet.builder(command.metadata().comparator);

            if (row.isStatic())
                // If there is an expression on a static column, the static row must be marked unresolved and the 
                // partition fetched, as completing the static row could produce matches across the entire partition.
                // The static row itself will still be retrieved and completed if there is any unresolved non-static 
                // row, however, ensuring the latest static values are returned from the query.
                unresolvedStatic = command.rowFilter().hasStaticExpression();
            else
                toFetch.add(row.clustering());
        }

        private UnfilteredRowIterator originalPartition()
        {
            return new UnfilteredRowIterator()
            {
                @Override
                public DeletionTime partitionLevelDeletion()
                {
                    return deletionTime;
                }

                @Override
                public EncodingStats stats()
                {
                    return stats;
                }

                @Override
                public TableMetadata metadata()
                {
                    return command.metadata();
                }

                @Override
                public boolean isReverseOrder()
                {
                    return command.isReversed();
                }

                @Override
                public RegularAndStaticColumns columns()
                {
                    return columns;
                }

                @Override
                public DecoratedKey partitionKey()
                {
                    return key;
                }

                @Override
                public Row staticRow()
                {
                    return staticRow;
                }

                @Override
                public void close()
                {
                    releaseCachedRows(partitionRowsCached);
                }

                @Override
                public boolean hasNext()
                {
                    return !contents.isEmpty();
                }

                @Override
                public Unfiltered next()
                {
                    return contents.poll();
                }
            };
        }

        private UnfilteredRowIterator protectedPartition()
        {
            UnfilteredRowIterator original = originalPartition();

            if (toFetch != null)
            {
                try (UnfilteredPartitionIterator partitions = fetchFromSource())
                {
                    if (partitions.hasNext())
                    {
                        try (UnfilteredRowIterator fetchedRows = partitions.next())
                        {
                            return UnfilteredRowIterators.merge(Arrays.asList(original, fetchedRows));
                        }
                    }
                }
            }

            return original;
        }

        private UnfilteredPartitionIterator fetchFromSource()
        {
            assert toFetch != null;

            NavigableSet<Clustering<?>> clusterings = toFetch.build();
            tableMetrics.replicaFilteringProtectionRequests.mark();

            if (logger.isTraceEnabled())
                logger.trace("Requesting rows {} in partition {} from {} for replica filtering protection",
                             clusterings, key, source);

            Tracing.trace("Requesting {} rows in partition {} from {} for replica filtering protection",
                          clusterings.size(), key, source);

            // If there is an unresolved static column, we must fetch the entire partition, as static column predicates
            // may produce row matches across the entire partition. If there are only non-static rows to complete, we
            // query the partition specifically for the corresponding cluterings by name. In either case, we do not
            // provide a limit. (In the unresolved static case, we have no way of knowing how many stale rows we might
            // read on a silent replica before finding a live one.)
            ClusteringIndexFilter filter = unresolvedStatic ? command.clusteringIndexFilter(key)
                                                            : new ClusteringIndexNamesFilter(clusterings, command.isReversed());

            SinglePartitionReadCommand cmd = SinglePartitionReadCommand.create(command.metadata(),
                                                                               command.nowInSec(),
                                                                               command.columnFilter(),
                                                                               RowFilter.none(),
                                                                               DataLimits.NONE,
                                                                               key,
                                                                               filter);

            ReplicaPlan.ForTokenRead replicaPlan = ReplicaPlans.forSingleReplicaRead(keyspace, key.getToken(), source);
            ReplicaPlan.SharedForTokenRead sharedReplicaPlan = ReplicaPlan.shared(replicaPlan);

            try
            {
                return executeReadCommand(cmd, source, sharedReplicaPlan);
            }
            catch (ReadTimeoutException e)
            {
                int blockFor = consistency.blockFor(replicaPlan.replicationStrategy());
                throw new ReadTimeoutException(consistency, blockFor - 1, blockFor, true);
            }
            catch (UnavailableException e)
            {
                int blockFor = consistency.blockFor(replicaPlan.replicationStrategy());
                throw UnavailableException.create(consistency, blockFor, blockFor - 1);
            }
        }
    }
}
