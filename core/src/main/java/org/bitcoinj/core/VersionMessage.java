/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import com.google.common.net.InetAddresses;
import org.bitcoinj.base.VarInt;
import org.bitcoinj.base.internal.Buffers;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.base.internal.ByteUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * <p>A VersionMessage holds information exchanged during connection setup with another peer. Most of the fields are not
 * particularly interesting. The subVer field, since BIP 14, acts as a User-Agent string would. You can and should 
 * append to or change the subVer for your own software so other implementations can identify it, and you can look at
 * the subVer field received from other nodes to see what they are running.</p>
 *
 * <p>After creating yourself a VersionMessage, you can pass it to {@link PeerGroup#setVersionMessage(VersionMessage)}
 * to ensure it will be used for each new connection.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class VersionMessage extends Message {

    /** The version of this library release, as a string. */
    public static final String BITCOINJ_VERSION = "0.17-SNAPSHOT";
    /** The value that is prepended to the subVer field of this application. */
    public static final String LIBRARY_SUBVER = "/bitcoinj:" + BITCOINJ_VERSION + "/";

    /**
     * The version number of the protocol spoken.
     */
    public int clientVersion;
    /**
     * Flags defining what optional services are supported.
     */
    public Services localServices;
    /**
     * What the other side believes the current time to be.
     */
    public Instant time;
    /**
     * The network address of the node receiving this message.
     */
    public PeerAddress receivingAddr;
    /**
     * The network address of the node emitting this message. Not used.
     */
    public PeerAddress fromAddr;
    /**
     * User-Agent as defined in <a href="https://github.com/bitcoin/bips/blob/master/bip-0014.mediawiki">BIP 14</a>.
     * Bitcoin Core sets it to something like "/Satoshi:0.9.1/".
     */
    public String subVer;
    /**
     * How many blocks are in the chain, according to the other side.
     */
    public long bestHeight;
    /**
     * Whether or not to relay tx invs before a filter is received.
     * See <a href="https://github.com/bitcoin/bips/blob/master/bip-0037.mediawiki#extensions-to-existing-messages">BIP 37</a>.
     */
    public boolean relayTxesBeforeFilter;

    public VersionMessage(NetworkParameters params, ByteBuffer payload) throws ProtocolException {
        super(params, payload);
    }

    // It doesn't really make sense to ever lazily parse a version message or to retain the backing bytes.
    // If you're receiving this on the wire you need to check the protocol version and it will never need to be sent
    // back down the wire.
    
    public VersionMessage(NetworkParameters params, int newBestHeight) {
        super(params);
        clientVersion = serializer.getProtocolVersion();
        localServices = Services.none();
        time = TimeUtils.currentTime().truncatedTo(ChronoUnit.SECONDS);
        // Note that the Bitcoin Core doesn't do anything with these, and finding out your own external IP address
        // is kind of tricky anyway, so we just put nonsense here for now.
        InetAddress localhost = InetAddresses.forString("127.0.0.1");
        MessageSerializer serializer = this.serializer.withProtocolVersion(0);
        receivingAddr = new PeerAddress(params, localhost, params.getPort(), Services.none(), serializer);
        fromAddr = new PeerAddress(params, localhost, params.getPort(), Services.none(), serializer);
        subVer = LIBRARY_SUBVER;
        bestHeight = newBestHeight;
        relayTxesBeforeFilter = true;
    }

    @Override
    protected void parse() throws BufferUnderflowException, ProtocolException {
        clientVersion = (int) ByteUtils.readUint32(payload);
        localServices = Services.read(payload);
        time = Instant.ofEpochSecond(ByteUtils.readInt64(payload));
        receivingAddr = new PeerAddress(params, payload, serializer.withProtocolVersion(0));
        if (clientVersion >= 106) {
            fromAddr = new PeerAddress(params, payload, serializer.withProtocolVersion(0));
            // uint64 localHostNonce (random data)
            // We don't care about the localhost nonce. It's used to detect connecting back to yourself in cases where
            // there are NATs and proxies in the way. However we don't listen for inbound connections so it's
            // irrelevant.
            Buffers.skipBytes(payload, 8);
            // string subVer (currently "")
            subVer = Buffers.readLengthPrefixedString(payload);
            // int bestHeight (size of known block chain).
            bestHeight = ByteUtils.readUint32(payload);
            if (clientVersion >= params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER)) {
                relayTxesBeforeFilter = payload.get() != 0;
            } else {
                relayTxesBeforeFilter = true;
            }
        } else {
            // Default values for flags which may not be sent by old nodes
            fromAddr = null;
            subVer = "";
            bestHeight = 0;
            relayTxesBeforeFilter = true;
        }
    }

    @Override
    public void bitcoinSerializeToStream(OutputStream buf) throws IOException {
        ByteUtils.writeInt32LE(clientVersion, buf);
        buf.write(localServices.buffer().array());
        ByteUtils.writeInt64LE(time.getEpochSecond(), buf);
        receivingAddr.bitcoinSerializeToStream(buf);
        fromAddr.bitcoinSerializeToStream(buf);
        // Next up is the "local host nonce", this is to detect the case of connecting
        // back to yourself. We don't care about this as we won't be accepting inbound
        // connections.
        ByteUtils.writeInt32LE(0, buf);
        ByteUtils.writeInt32LE(0, buf);
        // Now comes subVer.
        byte[] subVerBytes = subVer.getBytes(StandardCharsets.UTF_8);
        buf.write(VarInt.of(subVerBytes.length).encode());
        buf.write(subVerBytes);
        // Size of known block chain.
        ByteUtils.writeInt32LE(bestHeight, buf);
        if (clientVersion >= params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER)) {
            buf.write(relayTxesBeforeFilter ? 1 : 0);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VersionMessage other = (VersionMessage) o;
        return other.bestHeight == bestHeight &&
                other.clientVersion == clientVersion &&
                other.localServices == localServices &&
                other.time.equals(time) &&
                other.subVer.equals(subVer) &&
                other.receivingAddr.equals(receivingAddr) &&
                other.fromAddr.equals(fromAddr) &&
                other.relayTxesBeforeFilter == relayTxesBeforeFilter;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bestHeight, clientVersion, localServices,
            time, subVer, receivingAddr, fromAddr, relayTxesBeforeFilter);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("\n");
        builder.append("client version: ").append(clientVersion).append("\n");
        if (localServices.hasAny())
            builder.append("local services: ").append(localServices);
        builder.append("\n");
        builder.append("time:           ").append(TimeUtils.dateTimeFormat(time)).append("\n");
        builder.append("receiving addr: ").append(receivingAddr).append("\n");
        builder.append("from addr:      ").append(fromAddr).append("\n");
        builder.append("sub version:    ").append(subVer).append("\n");
        builder.append("best height:    ").append(bestHeight).append("\n");
        builder.append("delay tx relay: ").append(!relayTxesBeforeFilter).append("\n");
        return builder.toString();
    }

    public VersionMessage duplicate() {
        VersionMessage v = new VersionMessage(params, (int) bestHeight);
        v.clientVersion = clientVersion;
        v.localServices = localServices;
        v.time = time;
        v.receivingAddr = receivingAddr;
        v.fromAddr = fromAddr;
        v.subVer = subVer;
        v.relayTxesBeforeFilter = relayTxesBeforeFilter;
        return v;
    }

    /**
     * <p>Appends the given user-agent information to the subVer field. The subVer is composed of a series of
     * name:version pairs separated by slashes in the form of a path. For example a typical subVer field for bitcoinj
     * users might look like "/bitcoinj:0.13/MultiBit:1.2/" where libraries come further to the left.</p>
     *
     * <p>There can be as many components as you feel a need for, and the version string can be anything, but it is
     * recommended to use A.B.C where A = major, B = minor and C = revision for software releases, and dates for
     * auto-generated source repository snapshots. A valid subVer begins and ends with a slash, therefore name
     * and version are not allowed to contain such characters.</p>
     *
     * <p>Anything put in the "comments" field will appear in brackets and may be used for platform info, or anything
     * else. For example, calling {@code appendToSubVer("MultiBit", "1.0", "Windows")} will result in a subVer being
     * set of "/bitcoinj:1.0/MultiBit:1.0(Windows)/". Therefore the / ( and ) characters are reserved in all these
     * components. If you don't want to add a comment (recommended), pass null.</p>
     *
     * <p>See <a href="https://github.com/bitcoin/bips/blob/master/bip-0014.mediawiki">BIP 14</a> for more information.</p>
     *
     * @param comments Optional (can be null) platform or other node specific information.
     * @throws IllegalArgumentException if name, version or comments contains invalid characters.
     */
    public void appendToSubVer(String name, String version, @Nullable String comments) {
        checkSubVerComponent(name);
        checkSubVerComponent(version);
        if (comments != null) {
            checkSubVerComponent(comments);
            subVer = subVer.concat(String.format(Locale.US, "%s:%s(%s)/", name, version, comments));
        } else {
            subVer = subVer.concat(String.format(Locale.US, "%s:%s/", name, version));
        }
    }

    private static void checkSubVerComponent(String component) {
        if (component.contains("/") || component.contains("(") || component.contains(")"))
            throw new IllegalArgumentException("name contains invalid characters");
    }

    /**
     * Returns true if the clientVersion field is {@link NetworkParameters.ProtocolVersion#PONG} or higher.
     * If it is then {@link Peer#sendPing()} is usable.
     */
    public boolean isPingPongSupported() {
        return clientVersion >= params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.PONG);
    }

    /**
     * Returns true if the peer supports bloom filtering according to BIP37 and BIP111.
     */
    public boolean isBloomFilteringSupported() {
        if (clientVersion >= params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER)
                && clientVersion < params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER_BIP111))
            return true;
        if (localServices.has(Services.NODE_BLOOM))
            return true;
        return false;
    }

    /** Returns true if a peer can be asked for blocks and transactions including witness data. */
    public boolean isWitnessSupported() {
        return localServices.has(Services.NODE_WITNESS);
    }

    /**
     * Returns true if the version message indicates the sender has a full copy of the block chain, or false if it's
     * running in client mode (only has the headers).
     */
    public boolean hasBlockChain() {
        return localServices.has(Services.NODE_NETWORK);
    }

    /** Returns true if the peer has at least the last two days worth of blockchain (BIP159). */
    public boolean hasLimitedBlockChain() {
        return hasBlockChain() || localServices.has(Services.NODE_NETWORK_LIMITED);
    }
}
