/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket.server;

import static org.jboss.web.WebsocketsMessages.MESSAGES;

import java.io.EOFException;
import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;

import org.apache.coyote.http11.upgrade.AbstractServletInputStream;
import org.apache.coyote.http11.upgrade.AbstractServletOutputStream;
import org.apache.coyote.http11.upgrade.servlet31.HttpUpgradeHandler;
import org.apache.coyote.http11.upgrade.servlet31.ReadListener;
import org.apache.coyote.http11.upgrade.servlet31.WebConnection;
import org.apache.coyote.http11.upgrade.servlet31.WriteListener;
import org.apache.tomcat.websocket.WsIOException;
import org.apache.tomcat.websocket.WsSession;
import org.jboss.web.WebsocketsLogger;

/**
 * Servlet 3.1 HTTP upgrade handler for WebSocket connections.
 */
public class WsHttpUpgradeHandler implements HttpUpgradeHandler {

    private Endpoint ep;
    private EndpointConfig endpointConfig;
    private WsServerContainer webSocketContainer;
    private WsHandshakeRequest handshakeRequest;
    private String subProtocol;
    private Map<String,String> pathParameters;
    private boolean secure;
    private WebConnection connection;

    private WsSession wsSession;


    public WsHttpUpgradeHandler() {
    }


    public void preInit(Endpoint ep, EndpointConfig endpointConfig,
            WsServerContainer wsc, WsHandshakeRequest handshakeRequest,
            String subProtocol, Map<String,String> pathParameters,
            boolean secure) {
        this.ep = ep;
        this.endpointConfig = endpointConfig;
        this.webSocketContainer = wsc;
        this.handshakeRequest = handshakeRequest;
        this.subProtocol = subProtocol;
        this.pathParameters = pathParameters;
        this.secure = secure;
    }


    @Override
    public void init(WebConnection connection) {
        if (ep == null) {
            throw MESSAGES.noPreInit();
        }

        this.connection = connection;

        AbstractServletInputStream sis;
        AbstractServletOutputStream sos;
        try {
            sis = connection.getInputStream();
            sos = connection.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        String httpSessionId = null;
        Object session = handshakeRequest.getHttpSession();
        if (session != null ) {
            httpSessionId = ((HttpSession) session).getId();
        }

        try {
            WsRemoteEndpointImplServer wsRemoteEndpointServer =
                    new WsRemoteEndpointImplServer(sos, webSocketContainer);
            wsSession = new WsSession(ep, wsRemoteEndpointServer,
                    webSocketContainer, handshakeRequest.getRequestURI(),
                    handshakeRequest.getParameterMap(),
                    handshakeRequest.getQueryString(),
                    handshakeRequest.getUserPrincipal(), httpSessionId,
                    subProtocol, pathParameters, secure, endpointConfig);
            WsFrameServer wsFrame = new WsFrameServer(
                    sis,
                    wsSession);
            sos.setWriteListener(
                    new WsWriteListener(this, wsRemoteEndpointServer));
            ep.onOpen(wsSession, endpointConfig);
            webSocketContainer.registerSession(ep, wsSession);
            sis.setReadListener(new WsReadListener(this, wsFrame));
        } catch (DeploymentException e) {
            throw new IllegalArgumentException(e);
        }
    }


    @Override
    public void destroy() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                WebsocketsLogger.ROOT_LOGGER.destroyFailed(e);
            }
        }
    }


    private void onError(Throwable throwable) {
        ep.onError(wsSession, throwable);
    }


    private void close(CloseReason cr) {
        /*
         * Any call to this method is a result of a problem reading from the
         * client. At this point that state of the connection is unknown.
         * Attempt to send a close frame to the client and then close the socket
         * immediately. There is no point in waiting for a close frame from the
         * client because there is no guarantee that we can recover from
         * whatever messed up state the client put the connection into.
         */
        wsSession.onClose(cr);
    }


    private static class WsReadListener implements ReadListener {

        private final WsHttpUpgradeHandler wsProtocolHandler;
        private final WsFrameServer wsFrame;


        private WsReadListener(WsHttpUpgradeHandler wsProtocolHandler,
                WsFrameServer wsFrame) {
            this.wsProtocolHandler = wsProtocolHandler;
            this.wsFrame = wsFrame;
        }


        @Override
        public void onDataAvailable() {
            try {
                wsFrame.onDataAvailable();
            } catch (WsIOException ws) {
                wsProtocolHandler.close(ws.getCloseReason());
            } catch (EOFException eof) {
                CloseReason cr = new CloseReason(
                        CloseCodes.CLOSED_ABNORMALLY, eof.getMessage());
                wsProtocolHandler.close(cr);
            } catch (IOException ioe) {
                onError(ioe);
                CloseReason cr = new CloseReason(
                        CloseCodes.CLOSED_ABNORMALLY, ioe.getMessage());
                wsProtocolHandler.close(cr);
            }
        }


        @Override
        public void onAllDataRead() {
            // Will never happen with WebSocket
            throw new IllegalStateException();
        }


        @Override
        public void onError(Throwable throwable) {
            wsProtocolHandler.onError(throwable);
        }
    }


    private static class WsWriteListener implements WriteListener {

        private final WsHttpUpgradeHandler wsProtocolHandler;
        private final WsRemoteEndpointImplServer wsRemoteEndpointServer;

        private WsWriteListener(WsHttpUpgradeHandler wsProtocolHandler,
                WsRemoteEndpointImplServer wsRemoteEndpointServer) {
            this.wsProtocolHandler = wsProtocolHandler;
            this.wsRemoteEndpointServer = wsRemoteEndpointServer;
        }


        @Override
        public void onWritePossible() {
            // Triggered by the poller so this isn't the same thread that
            // triggered the write so no need for a dispatch
            wsRemoteEndpointServer.onWritePossible(false, false);
        }


        @Override
        public void onError(Throwable throwable) {
            wsProtocolHandler.onError(throwable);
            wsRemoteEndpointServer.close();
        }
    }
}
