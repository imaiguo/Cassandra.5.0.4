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

package org.apache.cassandra.service.paxos.v1;


import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Iterables;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.DecoratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.net.Message;
import org.apache.cassandra.service.paxos.Commit;
import org.apache.cassandra.service.paxos.PrepareResponse;
import org.apache.cassandra.transport.Dispatcher;
import org.apache.cassandra.utils.FBUtilities;

public class PrepareCallback extends AbstractPaxosCallback<PrepareResponse>
{
    private static final Logger logger = LoggerFactory.getLogger(PrepareCallback.class);

    public boolean promised = true;
    public Commit mostRecentCommit;
    public Commit mostRecentInProgressCommit;

    private final Map<InetAddressAndPort, Commit> commitsByReplica = new ConcurrentHashMap<>();

    public PrepareCallback(DecoratedKey key, TableMetadata metadata, int targets, ConsistencyLevel consistency, Dispatcher.RequestTime requestTime)
    {
        super(targets, consistency, requestTime);
        // need to inject the right key in the empty commit so comparing with empty commits in the response works as expected
        mostRecentCommit = Commit.emptyCommit(key, metadata);
        mostRecentInProgressCommit = Commit.emptyCommit(key, metadata);
    }

    public synchronized void onResponse(Message<PrepareResponse> message)
    {
        PrepareResponse response = message.payload;
        logger.trace("Prepare response {} from {}", response, message.from());

        // We set the mostRecentInProgressCommit even if we're not promised as, in that case, the ballot of that commit
        // will be used to avoid generating a ballot that has not chance to win on retry (think clock skew).
        if (response.inProgressCommit.isAfter(mostRecentInProgressCommit))
            mostRecentInProgressCommit = response.inProgressCommit;

        if (!response.promised)
        {
            promised = false;
            while (latch.count() > 0)
                latch.decrement();
            return;
        }

        commitsByReplica.put(message.from(), response.mostRecentCommit);
        if (response.mostRecentCommit.isAfter(mostRecentCommit))
            mostRecentCommit = response.mostRecentCommit;

        latch.decrement();
    }

    public Iterable<InetAddressAndPort> replicasMissingMostRecentCommit(TableMetadata metadata)
    {
        /**
         * this check is only needed for mixed mode operation with 4.0 and can be removed once upgrade support dropped
         * see the comment in {@link org.apache.cassandra.distributed.upgrade.MixedModePaxosTTLTest} for a full explanation.
         */
        if (DatabaseDescriptor.paxosStatePurging() == Config.PaxosStatePurging.legacy)
        {
            // In general, we need every replicas that have answered to the prepare (a quorum) to agree on the MRC (see
            // coment in StorageProxy.beginAndRepairPaxos(), but basically we need to make sure at least a quorum of nodes
            // have learn a commit before commit a new one otherwise that previous commit is not guaranteed to have reach a
            // quorum and further commit may proceed on incomplete information).
            // However, if that commit is too hold, it may have been expired from some of the replicas paxos table (we don't
            // keep the paxos state forever or that could grow unchecked), and we could end up in some infinite loop as
            // explained on CASSANDRA-12043. To avoid that, we ignore an MRC that is too old, i.e. older than the TTL we set
            // on paxos tables. For such an old commit, we rely on hints and repair to ensure the commit has indeed been
            // propagated to all nodes.
            long paxosTtlSec = SystemKeyspace.legacyPaxosTtlSec(metadata);
            if (TimeUnit.MICROSECONDS.toSeconds(mostRecentCommit.ballot.unixMicros()) + paxosTtlSec < FBUtilities.nowInSeconds())
                return Collections.emptySet();
        }

        return Iterables.filter(commitsByReplica.keySet(), inetAddress -> (!commitsByReplica.get(inetAddress).ballot.equals(mostRecentCommit.ballot)));
    }
}
