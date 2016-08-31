package com.moriatry.gossip.gms;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */


import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moriatry.gossip.io.util.FastByteArrayInputStream;
import com.moriatry.gossip.net.IVerbHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moriatry.gossip.config.GossiperDescriptor;
import com.moriatry.gossip.net.Message;
import com.moriatry.gossip.net.MessagingService;

public class GossipDigestSynVerbHandler implements IVerbHandler
{
    private static Logger logger_ = LoggerFactory.getLogger( GossipDigestSynVerbHandler.class);

    public void doVerb(Message message, String id)
    {
    	InetSocketAddress from = message.getFrom();
        if (logger_.isTraceEnabled())
            logger_.trace("Received a GossipDigestSynMessage from {}", from);
        if (!Gossiper.instance.isEnabled())
        {
            if (logger_.isTraceEnabled())
                logger_.trace("Ignoring GossipDigestSynMessage because gossip is disabled");
            return;
        }

        byte[] bytes = message.getMessageBody();
        DataInputStream dis = new DataInputStream( new FastByteArrayInputStream(bytes) );

        try
        {
            GossipDigestSynMessage gDigestMessage = GossipDigestSynMessage.serializer().deserialize(dis);
            /* If the message is from a different cluster throw it away. */
            if ( !gDigestMessage.clusterId_.equals(GossiperDescriptor.getClusterName()) )
            {
                logger_.warn("ClusterName mismatch from " + from + " " + gDigestMessage.clusterId_  + "!=" + GossiperDescriptor.getClusterName());
                return;
            }

            List<GossipDigest> gDigestList = gDigestMessage.getGossipDigests();
            if (logger_.isTraceEnabled())
            {
                StringBuilder sb = new StringBuilder();
                for ( GossipDigest gDigest : gDigestList )
                {
                    sb.append(gDigest);
                    sb.append(" ");
                }
                logger_.trace("Gossip syn digests are : " + sb.toString());
            }
            /* Notify the Failure Detector */
            Gossiper.instance.notifyFailureDetector(gDigestList);

            doSort(gDigestList);

            List<GossipDigest> deltaGossipDigestList = new ArrayList<GossipDigest>();
            Map<InetSocketAddress, EndpointState> deltaEpStateMap = new HashMap<InetSocketAddress, EndpointState>();
            Gossiper.instance.examineGossiper(gDigestList, deltaGossipDigestList, deltaEpStateMap);

            GossipDigestAckMessage gDigestAck = new GossipDigestAckMessage(deltaGossipDigestList, deltaEpStateMap);
            Message gDigestAckMessage = Gossiper.instance.makeGossipDigestAckMessage(gDigestAck);
            if (logger_.isTraceEnabled())
                logger_.trace("Sending a GossipDigestAckMessage to {}", from);
            MessagingService.instance().sendOneWay(gDigestAckMessage, from);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /*
     * First construct a map whose key is the endpoint in the GossipDigest and the value is the
     * GossipDigest itself. Then build a list of version differences i.e difference between the
     * version in the GossipDigest and the version in the local state for a given InetAddress.
     * Sort this list. Now loop through the sorted list and retrieve the GossipDigest corresponding
     * to the endpoint from the map that was initially constructed.
    */
    private void doSort(List<GossipDigest> gDigestList)
    {
        /* Construct a map of endpoint to GossipDigest. */
        Map<InetSocketAddress, GossipDigest> epToDigestMap = new HashMap<InetSocketAddress, GossipDigest>();
        for ( GossipDigest gDigest : gDigestList )
        {
            epToDigestMap.put(gDigest.getEndpoint(), gDigest);
        }

        /*
         * These digests have their maxVersion set to the difference of the version
         * of the local EndpointState and the version found in the GossipDigest.
        */
        List<GossipDigest> diffDigests = new ArrayList<GossipDigest>(gDigestList.size());
        for ( GossipDigest gDigest : gDigestList )
        {
        	InetSocketAddress ep = gDigest.getEndpoint();
            EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(ep);
            int version = (epState != null) ? Gossiper.instance.getMaxEndpointStateVersion( epState ) : 0;
            int diffVersion = Math.abs(version - gDigest.getMaxVersion() );
            diffDigests.add( new GossipDigest(ep, gDigest.getGeneration(), diffVersion) );
        }

        gDigestList.clear();
        Collections.sort(diffDigests);
        int size = diffDigests.size();
        /*
         * Report the digests in descending order. This takes care of the endpoints
         * that are far behind w.r.t this local endpoint
        */
        for ( int i = size - 1; i >= 0; --i )
        {
            gDigestList.add( epToDigestMap.get(diffDigests.get(i).getEndpoint()) );
        }
    }
}
