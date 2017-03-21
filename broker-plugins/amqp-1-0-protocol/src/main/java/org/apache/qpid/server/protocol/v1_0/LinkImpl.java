/*
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
 */

package org.apache.qpid.server.protocol.v1_0;

import java.util.concurrent.ExecutionException;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.protocol.v1_0.type.AmqpErrorException;
import org.apache.qpid.server.protocol.v1_0.type.BaseSource;
import org.apache.qpid.server.protocol.v1_0.type.BaseTarget;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Target;
import org.apache.qpid.server.protocol.v1_0.type.messaging.TerminusDurability;
import org.apache.qpid.server.protocol.v1_0.type.transaction.Coordinator;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Error;
import org.apache.qpid.server.protocol.v1_0.type.transport.LinkError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;

public class LinkImpl implements Link_1_0
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkImpl.class);

    private final String _remoteContainerId;
    private final String _linkName;
    private final Role _role;
    private final LinkRegistry _linkRegistry;

    private volatile LinkEndpoint _linkEndpoint;
    private volatile BaseSource _source;
    private volatile BaseTarget _target;

    public LinkImpl(final String remoteContainerId, final String linkName, final Role role, final LinkRegistry linkRegistry)
    {
        _remoteContainerId = remoteContainerId;
        _linkName = linkName;
        _role = role;
        _linkRegistry = linkRegistry;
    }

    public LinkImpl(final LinkDefinition linkDefinition, final LinkRegistry linkRegistry)
    {
        this(linkDefinition.getRemoteContainerId(), linkDefinition.getName(), linkDefinition.getRole(), linkRegistry);
        setTermini(linkDefinition.getSource(), linkDefinition.getTarget());
    }

    @Override
    public final ListenableFuture<? extends LinkEndpoint> attach(final Session_1_0 session, final Attach attach)
    {
        try
        {
            if (_role == attach.getRole())
            {
                throw new AmqpErrorException(new Error(AmqpError.ILLEGAL_STATE, "Cannot switch SendingLink to ReceivingLink and vice versa"));
            }


            if (_linkEndpoint != null && !session.equals(_linkEndpoint.getSession()))
            {
                return stealLink(session, attach);
            }
            else
            {
                if (_linkEndpoint == null)
                {
                    _linkEndpoint = createLinkEndpoint(session, attach);
                    if (_linkEndpoint == null)
                    {
                        throw new ConnectionScopedRuntimeException(String.format(
                                "LinkEndpoint creation failed for attach: %s",
                                attach));
                    }
                }

                _linkEndpoint.receiveAttach(attach);
                _linkRegistry.linkChanged(this);
                return Futures.immediateFuture((LinkEndpoint) _linkEndpoint);
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Error attaching link", e);
            return rejectLink(session, e);
        }
    }

    private synchronized ListenableFuture<LinkEndpoint> stealLink(final Session_1_0 session, final Attach attach)
    {
        final SettableFuture<LinkEndpoint> returnFuture = SettableFuture.create();
        _linkEndpoint.getSession().doOnIOThreadAsync(new Runnable()
        {
            @Override
            public void run()
            {
                _linkEndpoint.close(new Error(LinkError.STOLEN,
                                              String.format("Link is being stolen by connection '%s'",
                                                            session.getConnection())));
                try
                {
                    returnFuture.set(attach(session, attach).get());
                }
                catch (InterruptedException e)
                {
                    returnFuture.setException(e);
                    Thread.currentThread().interrupt();
                }
                catch (ExecutionException e)
                {
                    returnFuture.setException(e.getCause());
                }
            }
        });
        return returnFuture;
    }

    private LinkEndpoint createLinkEndpoint(final Session_1_0 session, final Attach attach)
    {
        LinkEndpoint linkEndpoint = null;
        if (_role == Role.SENDER)
        {
            linkEndpoint = new SendingLinkEndpoint(session, this);
        }
        else if (_role == Role.RECEIVER && attach.getTarget() != null)
        {

            if (attach.getTarget() instanceof Target)
            {
                linkEndpoint = new StandardReceivingLinkEndpoint(session, this);
            }
            else if (attach.getTarget() instanceof Coordinator)
            {
                linkEndpoint = new TxnCoordinatorReceivingLinkEndpoint(session, this);
            }
        }
        return linkEndpoint;
    }

    private ListenableFuture<? extends LinkEndpoint> rejectLink(final Session_1_0 session, Throwable t)
    {
        if (t instanceof AmqpErrorException)
        {
            _linkEndpoint = new ErrantLinkEndpoint(this, session, ((AmqpErrorException) t).getError());
        }
        else
        {
            _linkEndpoint = new ErrantLinkEndpoint(this, session, new Error(AmqpError.INTERNAL_ERROR, t.getMessage()));
        }
        return Futures.immediateFuture(_linkEndpoint);
    }


    @Override
    public void linkClosed()
    {
        discardEndpoint();
        _linkRegistry.linkClosed(this);
    }

    @Override
    public void discardEndpoint()
    {
        _linkEndpoint = null;
    }

    @Override
    public final String getName()
    {
        return _linkName;
    }

    @Override
    public Role getRole()
    {
        return _role;
    }

    @Override
    public BaseSource getSource()
    {
        return _source;
    }

    @Override
    public void setSource(BaseSource source)
    {
        setTermini(source, _target);
    }

    @Override
    public BaseTarget getTarget()
    {
        return _target;
    }

    @Override
    public void setTarget(BaseTarget target)
    {
        setTermini(_source, target);
    }

    @Override
    public void setTermini(BaseSource source, BaseTarget target)
    {
        _source = source;
        _target = target;
    }

    @Override
    public TerminusDurability getHighestSupportedTerminusDurability()
    {
        return _linkRegistry.getHighestSupportedTerminusDurability();
    }

    @Override
    public String getRemoteContainerId()
    {
        return _remoteContainerId;
    }
}