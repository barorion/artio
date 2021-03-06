/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.replication;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.engine.logger.ArchiveReader;
import uk.co.real_logic.artio.engine.logger.Archiver;

import java.util.function.Supplier;

import static uk.co.real_logic.artio.CommonConfiguration.*;

public class ClusterConfiguration
{
    public static final int DEFAULT_MAX_CLAIM_ATTEMPTS = 100_000;
    public static final int DEFAULT_CONTROL_STREAM_ID = 1;
    public static final int DEFAULT_DATA_STREAM_ID = 2;
    public static final int DEFAULT_ACKNOWLEDGEMENT_STREAM_ID = 3;
    public static final RoleHandler DEFAULT_NODE_HANDLER = new RoleHandler()
    {
        public void onTransitionToLeader(final int leadershipTerm)
        {
        }

        public void onTransitionToFollower(final int leadershipTerm)
        {
        }

        public void onTransitionToCandidate(final int leadershipTerm)
        {
        }
    };

    private short nodeId;
    private IntHashSet otherNodes;
    private long timeoutIntervalInMs;

    private Aeron aeron;
    private StreamIdentifier controlStream;
    private StreamIdentifier dataStream;
    private StreamIdentifier acknowledgementStream;
    private IdleStrategy idleStrategy;
    private AcknowledgementStrategy acknowledgementStrategy;
    private int maxClaimAttempts = DEFAULT_MAX_CLAIM_ATTEMPTS;
    private AtomicCounter failCounter;
    private Supplier<ArchiveReader> archiveReaderSupplier;
    private Archiver archiver;
    private RaftTransport raftTransport = new RaftTransport(this);
    private ExclusivePublication copyToPublication;
    private DirectBuffer nodeState;
    private NodeStateHandler nodeStateHandler;
    private RoleHandler roleHandler = DEFAULT_NODE_HANDLER;
    private String agentNamePrefix = DEFAULT_NAME_PREFIX;
    private boolean printAeronStreamIdentifiers = DEFAULT_PRINT_AERON_STREAM_IDENTIFIERS;

    /**
     * Sets the control, data and acknowledge streams to all this aeron
     * channel with their default ids.
     *
     * @param channel the aeron channel to use for all the streams
     * @return this
     */
    public ClusterConfiguration aeronChannel(final String channel)
    {
        controlStream(new StreamIdentifier(channel, DEFAULT_CONTROL_STREAM_ID));
        dataStream(new StreamIdentifier(channel, DEFAULT_DATA_STREAM_ID));
        acknowledgementStream(new StreamIdentifier(channel, DEFAULT_ACKNOWLEDGEMENT_STREAM_ID));
        return this;
    }

    public ClusterConfiguration controlStream(final StreamIdentifier controlStream)
    {
        this.controlStream = controlStream;
        return this;
    }

    public ClusterConfiguration dataStream(final StreamIdentifier dataStream)
    {
        this.dataStream = dataStream;
        return this;
    }

    public ClusterConfiguration acknowledgementStream(final StreamIdentifier acknowledgementStream)
    {
        this.acknowledgementStream = acknowledgementStream;
        return this;
    }

    public ClusterConfiguration nodeId(final short nodeId)
    {
        this.nodeId = nodeId;
        return this;
    }

    public ClusterConfiguration otherNodes(final IntHashSet otherNodes)
    {
        this.otherNodes = otherNodes;
        return this;
    }

    public ClusterConfiguration timeoutIntervalInMs(final long timeoutIntervalInMs)
    {
        this.timeoutIntervalInMs = timeoutIntervalInMs;
        return this;
    }

    public ClusterConfiguration acknowledgementStrategy(final AcknowledgementStrategy acknowledgementStrategy)
    {
        this.acknowledgementStrategy = acknowledgementStrategy;
        return this;
    }

    public ClusterConfiguration aeron(final Aeron aeron)
    {
        this.aeron = aeron;
        return this;
    }

    public ClusterConfiguration idleStrategy(final IdleStrategy idleStrategy)
    {
        this.idleStrategy = idleStrategy;
        return this;
    }

    public ClusterConfiguration maxClaimAttempts(final int maxClaimAttempts)
    {
        this.maxClaimAttempts = maxClaimAttempts;
        return this;
    }

    public ClusterConfiguration failCounter(final AtomicCounter failCounter)
    {
        this.failCounter = failCounter;
        return this;
    }

    public ClusterConfiguration archiveReaderSupplier(final Supplier<ArchiveReader> archiveReader)
    {
        this.archiveReaderSupplier = archiveReader;
        return this;
    }

    public ClusterConfiguration archiver(final Archiver archiver)
    {
        this.archiver = archiver;
        return this;
    }

    public ClusterConfiguration raftTransport(final RaftTransport raftTransport)
    {
        this.raftTransport = raftTransport;
        return this;
    }

    public ClusterConfiguration nodeState(final DirectBuffer nodeState)
    {
        this.nodeState = nodeState;
        return this;
    }

    public ClusterConfiguration nodeStateHandler(final NodeStateHandler nodeStateHandler)
    {
        this.nodeStateHandler = nodeStateHandler;
        return this;
    }

    public ClusterConfiguration nodeHandler(final RoleHandler roleHandler)
    {
        this.roleHandler = roleHandler;
        return this;
    }

    public ClusterConfiguration copyTo(final ExclusivePublication publication)
    {
        copyToPublication = publication;
        return this;
    }

    public ClusterConfiguration agentNamePrefix(final String agentNamePrefix)
    {
        this.agentNamePrefix = agentNamePrefix;
        return this;
    }

    public ClusterConfiguration printAeronStreamIdentifiers(final boolean printAeronStreamIdentifiers)
    {
        this.printAeronStreamIdentifiers = printAeronStreamIdentifiers;
        return this;
    }

    public StreamIdentifier controlStream()
    {
        return controlStream;
    }

    public StreamIdentifier dataStream()
    {
        return dataStream;
    }

    public StreamIdentifier acknowledgementStream()
    {
        return acknowledgementStream;
    }

    public short nodeId()
    {
        return nodeId;
    }

    public IntHashSet otherNodes()
    {
        return otherNodes;
    }

    public long timeoutIntervalInMs()
    {
        return timeoutIntervalInMs;
    }

    public AcknowledgementStrategy acknowledgementStrategy()
    {
        return acknowledgementStrategy;
    }

    public Aeron aeron()
    {
        return aeron;
    }

    public IdleStrategy idleStrategy()
    {
        return idleStrategy;
    }

    public int maxClaimAttempts()
    {
        return maxClaimAttempts;
    }

    public AtomicCounter failCounter()
    {
        return failCounter;
    }

    public Supplier<ArchiveReader> archiveReaderSupplier()
    {
        return archiveReaderSupplier;
    }

    public Archiver archiver()
    {
        return archiver;
    }

    public RaftTransport raftTransport()
    {
        return raftTransport;
    }

    public DirectBuffer nodeState()
    {
        return nodeState;
    }

    public NodeStateHandler nodeStateHandler()
    {
        return nodeStateHandler;
    }

    public RoleHandler nodeHandler()
    {
        return roleHandler;
    }

    public void conclude()
    {
        if (idleStrategy() == null)
        {
            idleStrategy(backoffIdleStrategy());
        }

        if (acknowledgementStrategy() == null)
        {
            acknowledgementStrategy(AcknowledgementStrategy.quorum());
        }
    }

    public ExclusivePublication copyToPublication()
    {
        return copyToPublication;
    }

    public String agentNamePrefix()
    {
        return agentNamePrefix;
    }

    public boolean printAeronStreamIdentifiers()
    {
        return printAeronStreamIdentifiers;
    }
}
