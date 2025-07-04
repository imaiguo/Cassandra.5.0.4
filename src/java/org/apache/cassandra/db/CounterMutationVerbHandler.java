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
package org.apache.cassandra.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.transport.Dispatcher;

public class CounterMutationVerbHandler extends AbstractMutationVerbHandler<CounterMutation>
{
    public static final CounterMutationVerbHandler instance = new CounterMutationVerbHandler();

    private static final Logger logger = LoggerFactory.getLogger(CounterMutationVerbHandler.class);

    @Override
    protected void applyMutation(final Message<CounterMutation> message, InetAddressAndPort respondToAddress)
    {
        final CounterMutation cm = message.payload;
        logger.trace("Applying forwarded {}", cm);

        String localDataCenter = DatabaseDescriptor.getEndpointSnitch().getLocalDatacenter();
        // We should not wait for the result of the write in this thread,
        // otherwise we could have a distributed deadlock between replicas
        // running this VerbHandler (see #4578).
        // Instead, we use a callback to send the response. Note that the callback
        // will not be called if the request timeout, but this is ok
        // because the coordinator of the counter mutation will timeout on
        // it's own in that case.
        StorageProxy.applyCounterMutationOnLeader(cm,
                                                  localDataCenter,
                                                  () -> MessagingService.instance().send(message.emptyResponse(), respondToAddress),
                                                  Dispatcher.RequestTime.forImmediateExecution());
    }
}
