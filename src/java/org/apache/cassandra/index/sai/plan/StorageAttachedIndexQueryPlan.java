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
package org.apache.cassandra.index.sai.plan;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.index.sai.StorageAttachedIndex;
import org.apache.cassandra.index.sai.metrics.TableQueryMetrics;
import org.apache.cassandra.schema.TableMetadata;

public class StorageAttachedIndexQueryPlan implements Index.QueryPlan
{
    public static final String UNSUPPORTED_NON_STRICT_OPERATOR =
            "Operator %s is only supported in intersections for reads that do not require replica reconciliation.";

    private final ColumnFamilyStore cfs;
    private final TableQueryMetrics queryMetrics;
    private final RowFilter postIndexFilter;
    private final RowFilter indexFilter;
    private final Set<Index> indexes;
    private final boolean isTopK;

    private StorageAttachedIndexQueryPlan(ColumnFamilyStore cfs,
                                          TableQueryMetrics queryMetrics,
                                          RowFilter postIndexFilter,
                                          RowFilter indexFilter,
                                          ImmutableSet<Index> indexes)
    {
        this.cfs = cfs;
        this.queryMetrics = queryMetrics;
        this.postIndexFilter = postIndexFilter;
        this.indexFilter = indexFilter;
        this.indexes = indexes;
        this.isTopK = indexes.stream().anyMatch(i -> i instanceof StorageAttachedIndex && ((StorageAttachedIndex) i).termType().isVector());
    }

    @Nullable
    public static StorageAttachedIndexQueryPlan create(ColumnFamilyStore cfs,
                                                       TableQueryMetrics queryMetrics,
                                                       Set<StorageAttachedIndex> indexes,
                                                       RowFilter filter)
    {
        ImmutableSet.Builder<Index> selectedIndexesBuilder = ImmutableSet.builder();

        RowFilter preIndexFilter = filter;
        RowFilter postIndexFilter = filter;

        for (RowFilter.Expression expression : filter)
        {
            // We ignore any expressions here (currently IN and user-defined expressions) where we don't have a way to
            // translate their #isSatifiedBy method, they will be included in the filter returned by 
            // QueryPlan#postIndexQueryFilter(). If strict filtering is not allowed, we must reject the query until the
            // expression(s) in question are compatible with #isSatifiedBy.
            //
            // Note: For both the pre- and post-filters we need to check that the expression exists before removing it
            // because the without method assert if the expression doesn't exist. This can be the case if we are given
            // a duplicate expression - a = 1 and a = 1. The without method removes all instances of the expression.
            if (expression.operator().isIN() || expression.isUserDefined())
            {
                if (!filter.isStrict())
                    throw new InvalidRequestException(String.format(UNSUPPORTED_NON_STRICT_OPERATOR, expression.operator()));

                if (preIndexFilter.getExpressions().contains(expression))
                    preIndexFilter = preIndexFilter.without(expression);
                continue;
            }

            if (postIndexFilter.getExpressions().contains(expression))
                postIndexFilter = postIndexFilter.without(expression);

            for (StorageAttachedIndex index : indexes)
            {
                if (index.supportsExpression(expression.column(), expression.operator()))
                {
                    selectedIndexesBuilder.add(index);
                }
            }
        }

        ImmutableSet<Index> selectedIndexes = selectedIndexesBuilder.build();
        if (selectedIndexes.isEmpty())
            return null;

        return new StorageAttachedIndexQueryPlan(cfs, queryMetrics, postIndexFilter, preIndexFilter, selectedIndexes);
    }

    @Override
    public Set<Index> getIndexes()
    {
        return indexes;
    }

    @Override
    public long getEstimatedResultRows()
    {
        return DatabaseDescriptor.getPrioritizeSAIOverLegacyIndex() ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    @Override
    public boolean shouldEstimateInitialConcurrency()
    {
        return false;
    }

    @Override
    public Index.Searcher searcherFor(ReadCommand command)
    {
        return new StorageAttachedIndexSearcher(cfs,
                                                queryMetrics,
                                                command,
                                                indexFilter,
                                                DatabaseDescriptor.getRangeRpcTimeout(TimeUnit.MILLISECONDS));
    }

    /**
     * Called on coordinator after merging replica responses before returning to client
     */
    @Override
    public Function<PartitionIterator, PartitionIterator> postProcessor(ReadCommand command)
    {
        if (!isTopK())
            return partitions -> partitions;

        // in case of top-k query, filter out rows that are not actually global top-K
        return partitions -> (PartitionIterator) new VectorTopKProcessor(command).filter(partitions);
    }

    /**
     * @return a filter with all the expressions that are user-defined or for a non-indexed partition key column
     * <p>
     * (currently index on partition columns is not supported, see {@link StorageAttachedIndex#validateOptions(Map, TableMetadata)})
     */
    @Override
    public RowFilter postIndexQueryFilter()
    {
        return postIndexFilter;
    }

    @Override
    public boolean isTopK()
    {
        return isTopK;
    }
}
