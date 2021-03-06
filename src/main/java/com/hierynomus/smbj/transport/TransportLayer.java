/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.transport;

import com.hierynomus.protocol.Packet;

import java.io.InputStream;
import java.io.OutputStream;

public interface TransportLayer<P extends Packet<P, ?>> {

    /**
     * Initialize the Transport layer.
     * <p>
     * This is called directly after a connection has been established.
     */
    void init(InputStream in, OutputStream out, PacketSerializer<P> packetSerializer);

    /**
     * The default port for the specified SMB transport layer.
     *
     * @return the default port
     */
    int getDefaultPort();

    /**
     * Write the packet to the transport.
     *
     * @param packet The packet to write.
     * @return The sequence number of the packet.
     */
    void write(P packet) throws TransportException;
}
