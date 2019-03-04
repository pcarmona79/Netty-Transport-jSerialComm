/*
 * Copyright 2018 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package cl.sictel.scada.netty.channel.jserialcomm;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.BAUD_RATE;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.DATA_BITS;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.DTR;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.PARITY_BIT;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.READ_TIMEOUT;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.RTS;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.STOP_BITS;
import static cl.sictel.scada.netty.channel.jserialcomm.JSerialCommChannelOption.WAIT_TIME;
import io.netty.channel.oio.OioByteStreamChannel;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * A channel to a serial device using the jSerialComm library.
 */
public class JSerialCommChannel extends OioByteStreamChannel {

    private static final JSerialCommDeviceAddress LOCAL_ADDRESS = new JSerialCommDeviceAddress("localhost");

    private final JSerialCommChannelConfig config;

    private boolean open = true;
    private JSerialCommDeviceAddress deviceAddress;
    private SerialPort serialPort;

    public JSerialCommChannel() {
        super(null);

        config = new DefaultJSerialCommChannelConfig(this);
    }

    @Override
    public JSerialCommChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new JSerialCommUnsafe();
    }

    @Override
    protected void doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        JSerialCommDeviceAddress remote = (JSerialCommDeviceAddress) remoteAddress;
        SerialPort commPort = SerialPort.getCommPort(remote.value());

        if (!commPort.openPort()) {
            throw new IllegalArgumentException("Unable to open \"" + remote.value() + "\" port");
        }
        commPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, config().getOption(READ_TIMEOUT), 0);

        deviceAddress = remote;
        serialPort = (SerialPort) commPort;
    }

    protected void doInit() throws Exception {
        serialPort.setComPortParameters(
                config().getOption(BAUD_RATE),
                config().getOption(DATA_BITS),
                config().getOption(STOP_BITS).value(),
                config().getOption(PARITY_BIT).value()
        );

        if (config().getOption(DTR)) {
            serialPort.setDTR();
        }
        if (config().getOption(RTS)) {
            serialPort.setRTS();
        }

        activate(serialPort.getInputStream(), serialPort.getOutputStream());
    }

    @Override
    public JSerialCommDeviceAddress localAddress() {
        return (JSerialCommDeviceAddress) super.localAddress();
    }

    @Override
    public JSerialCommDeviceAddress remoteAddress() {
        return (JSerialCommDeviceAddress) super.remoteAddress();
    }

    @Override
    protected JSerialCommDeviceAddress localAddress0() {
        return LOCAL_ADDRESS;
    }

    @Override
    protected JSerialCommDeviceAddress remoteAddress0() {
        return deviceAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected int doReadBytes(ByteBuf buf) throws Exception {
        try {
            return super.doReadBytes(buf);
        } catch (SerialPortTimeoutException e) {
            // timeout is not an error
            return 0;
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        open = false;
        try {
           super.doClose();
        } finally {
            if (serialPort != null) {
                serialPort.removeDataListener();
                serialPort.closePort();
                serialPort = null;
            }
        }
    }

    @Override
    protected boolean isInputShutdown() {
        return !open;
    }

    @Override
    protected ChannelFuture shutdownInput() {
        return newFailedFuture(new UnsupportedOperationException("shutdownInput"));
    }

    private final class JSerialCommUnsafe extends AbstractUnsafe {
        @Override
        public void connect(
                final SocketAddress remoteAddress,
                final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                final boolean wasActive = isActive();
                doConnect(remoteAddress, localAddress);

                int waitTime = config().getOption(WAIT_TIME);
                if (waitTime > 0) {
                    eventLoop().schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                doInit();
                                safeSetSuccess(promise);
                                if (!wasActive && isActive()) {
                                    pipeline().fireChannelActive();
                                }
                            } catch (Throwable t) {
                                safeSetFailure(promise, t);
                                closeIfClosed();
                            }
                        }
                   }, waitTime, TimeUnit.MILLISECONDS);
                } else {
                    doInit();
                    safeSetSuccess(promise);
                    if (!wasActive && isActive()) {
                        pipeline().fireChannelActive();
                    }
                }
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
            }
        }
    }
}
