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
package org.apache.cassandra.gms;

import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GossipShutdownVerbHandler implements IVerbHandler<GossipShutdown>
{
    public static final GossipShutdownVerbHandler instance = new GossipShutdownVerbHandler();

    private static final Logger logger = LoggerFactory.getLogger(GossipShutdownVerbHandler.class);

    public void doVerb(Message<GossipShutdown> message)
    {
        if (!Gossiper.instance.isEnabled())
        {
            logger.debug("Ignoring shutdown message from {} because gossip is disabled", message.from());
            return;
        }
        HeartBeatState previous = Gossiper.instance.getEndpointStateForEndpoint(message.from()).getHeartBeatState();

        if (message.payload == null)
            Gossiper.instance.markAsShutdown(message.from());
        else if (previous.getGeneration() <= message.payload.state.getHeartBeatState().getGeneration())
            Gossiper.instance.markAsShutdown(message.from(), message.payload.state);
        else
            logger.debug("Ignoring shutdown message from {} because generation {} older than local {}",
                    message.from(), message.payload.state.getHeartBeatState().getGeneration(), previous.getGeneration());
    }

}
