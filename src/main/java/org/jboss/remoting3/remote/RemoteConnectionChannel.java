/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remoting3.remote;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.Executor;
import org.jboss.remoting3.Attachments;
import org.jboss.remoting3.BasicAttachments;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.ChannelBusyException;
import org.jboss.remoting3.MessageOutputStream;
import org.jboss.remoting3.spi.AbstractHandleableCloseable;
import org.xnio.Pooled;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class RemoteConnectionChannel extends AbstractHandleableCloseable<Channel> implements Channel {

    static final IntIndexer<RemoteConnectionChannel> INDEXER = new IntIndexer<RemoteConnectionChannel>() {
        public int indexOf(final RemoteConnectionChannel argument) {
            return argument.channelId;
        }

        public boolean equals(final RemoteConnectionChannel argument, final int index) {
            return argument.channelId == index;
        }
    };

    private final RemoteConnection connection;
    private final int channelId;
    private final UnlockedReadIntIndexHashMap<OutboundMessage> outboundMessages = new UnlockedReadIntIndexHashMap<OutboundMessage>(OutboundMessage.INDEXER);
    private final UnlockedReadIntIndexHashMap<InboundMessage> inboundMessages = new UnlockedReadIntIndexHashMap<InboundMessage>(InboundMessage.INDEXER);
    private final Random random;
    private final int outboundWindow;
    private final int inboundWindow;
    private final Attachments attachments = new BasicAttachments();
    private Receiver nextMessageHandler;
    private int messageCount;

    RemoteConnectionChannel(final Executor executor, final RemoteConnection connection, final int channelId, final Random random, final int outboundWindow, final int inboundWindow) {
        super(executor);
        this.connection = connection;
        this.channelId = channelId;
        this.random = random;
        this.outboundWindow = outboundWindow;
        this.inboundWindow = inboundWindow;
    }

    public MessageOutputStream writeMessage() throws IOException {
        int tries = 50;
        UnlockedReadIntIndexHashMap<OutboundMessage> outboundMessages = this.outboundMessages;
        synchronized (this) {
            int messageCount;
            while ((messageCount = this.messageCount) == 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting to write message");
                }
            }
            this.messageCount = messageCount - 1;
            final Random random = this.random;
            while (tries > 0) {
                final int id = random.nextInt() & 0xfffe;
                if (! outboundMessages.containsKey(id)) {
                    OutboundMessage message = new OutboundMessage((short) id, this, outboundWindow);
                    outboundMessages.put(id, message);
                    return message.getOutputStream();
                }
                tries --;
            }
            throw new ChannelBusyException("Failed to send a message (channel is busy)");
        }
    }

    public void writeShutdown() throws IOException {
    }

    public void receiveMessage(final Receiver handler) {
        synchronized (this) {
            if (nextMessageHandler != null) {
                throw new IllegalStateException("Message handler already queued");
            }
            nextMessageHandler = handler;
        }
    }

    void handleMessageData(final Pooled<ByteBuffer> message) {
        ByteBuffer buffer = message.getResource();
        int id = buffer.getShort() & 0xffff;
        int flags = buffer.get() & 0xff;
        final InboundMessage inboundMessage;
        if ((flags & Protocol.MSG_FLAG_NEW) != 0) {
            inboundMessage = new InboundMessage((short) id, this);
            if (inboundMessages.putIfAbsent(id, inboundMessage) != null) {
                connection.handleException(new IOException("Protocol error: incoming message with duplicate ID received"));
                return;
            }
        } else {
            inboundMessage = inboundMessages.get(id);
            if (inboundMessage == null) {
                connection.handleException(new IOException("Protocol error: incoming message with unknown ID received"));
                return;
            }
        }
        inboundMessage.handleIncoming(message);
    }

    void handleWindowOpen(final Pooled<ByteBuffer> pooled) {
        ByteBuffer buffer = pooled.getResource();
        int id = buffer.getShort() & 0xffff;
        final OutboundMessage outboundMessage = outboundMessages.get(id);
        if (outboundMessage == null) {
            // ignore; probably harmless...?
            return;
        }
        outboundMessage.acknowledge(buffer.getInt() & 0x7FFFFFFF);
    }

    public Attachments getAttachments() {
        return attachments;
    }

    RemoteConnection getConnection() {
        return connection;
    }

    int getChannelId() {
        return channelId;
    }

    void freeOutboundMessage(final short id) {
        outboundMessages.remove(id & 0xffff);
    }

    void freeInboundMessage(final short id) {
        inboundMessages.remove(id & 0xffff);
    }

    Pooled<ByteBuffer> allocate(final byte protoId) {
        final Pooled<ByteBuffer> pooled = connection.allocate();
        final ByteBuffer buffer = pooled.getResource();
        buffer.put(protoId);
        buffer.putInt(channelId);
        return pooled;
    }
}