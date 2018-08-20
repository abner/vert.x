/*
 * Copyright (c) 2011-2017 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.http.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.ReferenceCountUtil;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.impl.ws.WebSocketFrameInternal;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.impl.NetSocketImpl;
import io.vertx.core.net.impl.SSLHelper;
import io.vertx.core.net.impl.VertxNetHandler;
import io.vertx.core.spi.metrics.HttpServerMetrics;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.vertx.core.spi.metrics.Metrics.METRICS_ENABLED;

/**
 *
 * This class is optimised for performance when used on the same event loop. However it can be used safely from other threads.
 * </p>
 * The internal state is protected using the synchronized keyword. If always used on the same event loop, then
 * we benefit from biased locking which makes the overhead of synchronized near zero.
 * </p>
 * The connection maintains two fields for tracking requests:
 * <ul>
 *   <li>{@link #requestInProgress} is the request currently receiving messages, the field is set when
 *   a {@link HttpRequest} message is received and unset when {@link LastHttpContent} is received. Intermediate
 *   {@link HttpContent} messages are processed by the request.</li>
 *   <li>{@link #responseInProgress} is the request for which the response is currently being sent. This field
 *   is set when it is {@code null} and the {@link #requestInProgress} field if set, or when there is a pipelined
 *   request waiting its turn for writing the response</li>
 * </ul>
 * <p/>
 * When a request is received, it is also the current response if there is no response in progress, otherwise it is
 * queued and will become the response in progress when the current response in progress ends.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Http1xServerConnection extends Http1xConnectionBase implements HttpConnection {

  private static final Logger log = LoggerFactory.getLogger(Http1xServerConnection.class);

  private final String serverOrigin;
  private final SSLHelper sslHelper;
  private WebSocketServerHandshaker handshaker;
  private boolean requestFailed;
  private Handler<ServerWebSocket> wsHandler;
  private ServerWebSocketImpl ws;
  private long bytesRead;
  private long bytesWritten;

  private HttpServerRequestImpl requestInProgress;
  private HttpServerRequestImpl responseInProgress;
  private boolean channelPaused;

  final Handler<HttpServerRequest> requestHandler;
  final HttpServerMetrics metrics;
  final boolean handle100ContinueAutomatically;
  final HttpServerOptions options;

  Http1xServerConnection(VertxInternal vertx,
                                SSLHelper sslHelper,
                                HttpServerOptions options,
                                ChannelHandlerContext channel,
                                ContextInternal context,
                                String serverOrigin,
                                HttpHandlers handlers,
                                HttpServerMetrics metrics) {
    super(vertx, channel, context);
    this.requestHandler = requestHandler(handlers);
    this.serverOrigin = serverOrigin;
    this.options = options;
    this.sslHelper = sslHelper;
    this.metrics = metrics;
    this.handle100ContinueAutomatically = options.isHandle100ContinueAutomatically();
    exceptionHandler(handlers.exceptionHandler);
  }

  @Override
  public HttpServerMetrics metrics() {
    return metrics;
  }

  synchronized void handleMessage(Object msg) {
    if (msg instanceof HttpRequest) {
      DefaultHttpRequest request = (DefaultHttpRequest) msg;
      if (request.decoderResult() != DecoderResult.SUCCESS) {
        handleError(request);
        return;
      }
      HttpServerRequestImpl req = new HttpServerRequestImpl(this, request);
      requestInProgress = req;
      if (responseInProgress == null) {
        responseInProgress = requestInProgress;
        req.handleBegin();
      } else {
        // Deferred until the current response completion
        req.pause();
        responseInProgress.appendRequest(req);
      }
    } else if (msg == LastHttpContent.EMPTY_LAST_CONTENT) {
      handleEnd();
    } else if (msg instanceof HttpContent) {
      handleContent(msg);
    } else {
      handleOther(msg);
    }
  }

  private void handleContent(Object msg) {
    HttpContent content = (HttpContent) msg;
    if (content.decoderResult() != DecoderResult.SUCCESS) {
      handleError(content);
      return;
    }
    Buffer buffer = Buffer.buffer(content.content());
    if (METRICS_ENABLED) {
      reportBytesRead(buffer);
    }
    requestInProgress.handleContent(buffer);
    //TODO chunk trailers
    if (content instanceof LastHttpContent) {
      handleEnd();
    }
  }

  private void handleEnd() {
    if (METRICS_ENABLED) {
      reportRequestComplete();
    }
    HttpServerRequestImpl request = requestInProgress;
    requestInProgress = null;
    request.handleEnd();
  }

  synchronized void responseComplete() {
    if (METRICS_ENABLED) {
      reportResponseComplete();
    }
    HttpServerRequestImpl request = responseInProgress;
    responseInProgress = null;
    HttpServerRequestImpl next = request.nextRequest();
    if (next != null) {
      // Handle pipelined request
      handleNext(next);
    }
  }

  private void handleNext(HttpServerRequestImpl request) {
    responseInProgress = request;
    getContext().runOnContext(v -> responseInProgress.handlePipelined());
  }

  private void handleOther(Object msg) {
    if (msg instanceof WebSocketFrameInternal) {
      WebSocketFrameInternal frame = (WebSocketFrameInternal) msg;
      if (ws != null) {
        ws.handleFrame(frame);
      }
    }
  }

  @Override
  public void doPause() {
    if (!channelPaused) {
      channelPaused = true;
      super.doPause();
    }
  }

  @Override
  public void doResume() {
    if (channelPaused) {
      channelPaused = false;
      super.doResume();
    }
  }

  private void reportBytesRead(Buffer buffer) {
    if (metrics != null) {
      bytesRead += buffer.length();
    }
  }

  private void reportBytesWritten(Object msg) {
    if (metrics != null) {
      long bytes = getBytes(msg);
      if (bytes == -1) {
        log.warn("Metrics could not be updated to include bytes written because of unknown object " + msg.getClass() + " being written.");
      } else {
        bytesWritten += bytes;
      }
    }
  }

  private void reportRequestComplete() {
    if (metrics != null) {
      reportBytesRead(bytesRead);
      bytesRead = 0;
    }
  }

  private void reportResponseComplete() {
    if (metrics != null) {
      reportBytesWritten(bytesWritten);
      if (requestFailed) {
        metrics.requestReset(responseInProgress.metric());
        requestFailed = false;
      } else {
        metrics.responseEnd(responseInProgress.metric(), responseInProgress.response());
      }
      bytesWritten = 0;
    }
  }

  synchronized void wsHandler(WebSocketServerHandshaker handshaker, HttpHandlers handlers) {
    this.handshaker = handshaker;
    this.wsHandler = handlers.wsHandler;
    exceptionHandler(handlers.exceptionHandler);
  }

  String getServerOrigin() {
    return serverOrigin;
  }

  Vertx vertx() {
    return vertx;
  }

  @Override
  public void writeToChannel(Object msg, ChannelPromise promise) {
    if (METRICS_ENABLED) {
      reportBytesWritten(msg);
    }
    super.writeToChannel(msg, promise);
  }

  ServerWebSocket upgrade(HttpServerRequestImpl request, HttpRequest nettyReq) {
    if (ws != null) {
      return ws;
    }
    Http1xServerHandler serverHandler = (Http1xServerHandler) chctx.pipeline().get("handler");
    handshaker = serverHandler.createHandshaker(this, nettyReq);
    if (handshaker == null) {
      throw new IllegalStateException("Can't upgrade this request");
    }

    ws = new ServerWebSocketImpl(vertx, request.uri(), request.path(),
      request.query(), request.headers(), this, handshaker.version() != WebSocketVersion.V00,
      null, options.getMaxWebsocketFrameSize(), options.getMaxWebsocketMessageSize());
    try {
      handshaker.handshake(chctx.channel(), nettyReq);
    } catch (WebSocketHandshakeException e) {
      handleException(e);
    } catch (Exception e) {
      log.error("Failed to generate shake response", e);
    }
    ChannelHandler handler = chctx.pipeline().get(HttpChunkContentCompressor.class);
    if (handler != null) {
      // remove compressor as its not needed anymore once connection was upgraded to websockets
      chctx.pipeline().remove(handler);
    }
    if (METRICS_ENABLED && metrics != null) {
      ws.setMetric(metrics.upgrade(request.metric(), ws));
    }
    ws.registerHandler(vertx.eventBus());
    return ws;
  }

  NetSocket createNetSocket() {
    NetSocketImpl socket = new NetSocketImpl(vertx, chctx, context, sslHelper, metrics);
    socket.metric(metric());
    Map<Channel, NetSocketImpl> connectionMap = new HashMap<>(1);
    connectionMap.put(chctx.channel(), socket);

    // Flush out all pending data
    endReadAndFlush();

    // remove old http handlers and replace the old handler with one that handle plain sockets
    ChannelPipeline pipeline = chctx.pipeline();
    ChannelHandler compressor = pipeline.get(HttpChunkContentCompressor.class);
    if (compressor != null) {
      pipeline.remove(compressor);
    }

    pipeline.remove("httpDecoder");
    if (pipeline.get("chunkedWriter") != null) {
      pipeline.remove("chunkedWriter");
    }

    chctx.pipeline().replace("handler", "handler", new VertxNetHandler(socket) {
      @Override
      public void channelRead(ChannelHandlerContext chctx, Object msg) throws Exception {
        if (msg instanceof HttpContent) {
          ReferenceCountUtil.release(msg);
          return;
        }
        super.channelRead(chctx, msg);
      }

      @Override
      protected void handleMessage(NetSocketImpl connection, Object msg) {
        connection.handleMessageReceived(msg);
      }
    }.removeHandler(sock -> {
      if (metrics != null) {
        metrics.responseEnd(responseInProgress.metric(), responseInProgress.response());
      }
      connectionMap.remove(chctx.channel());
    }));

    // check if the encoder can be removed yet or not.
    chctx.pipeline().remove("httpEncoder");
    return socket;
  }

  @Override
  public synchronized void handleInterestedOpsChanged() {
    if (!isNotWritable()) {
      if (responseInProgress != null) {
        responseInProgress.response().handleDrained();
      } else if (ws != null) {
        ws.writable();
      }
    }
  }

  @Override
  public void close() {
    if (handshaker == null) {
      super.close();
    } else {
      endReadAndFlush();
      handshaker.close(chctx.channel(), new CloseWebSocketFrame(true, 0, 1000, null));
    }
  }

  @Override
  public void closeWithPayload(ByteBuf byteBuf) {
    if (handshaker == null) {
      super.close();
    } else {
      endReadAndFlush();
      handshaker.close(chctx.channel(), new CloseWebSocketFrame(true, 0, byteBuf));
    }
  }


  synchronized void handleWebsocketConnect(ServerWebSocketImpl ws) {
    if (wsHandler != null) {
      wsHandler.handle(ws);
      this.ws = ws;
    }
  }

  void write100Continue() {
    chctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
  }

  synchronized protected void handleClosed() {
    if (METRICS_ENABLED && metrics != null && ws != null) {
      metrics.disconnected(ws.getMetric());
      ws.setMetric(null);
    }
    if (ws != null) {
      ws.handleClosed();
    }
    if (responseInProgress != null) {
      responseInProgress.handleException(CLOSED_EXCEPTION);
    }
    super.handleClosed();
  }

  @Override
  protected synchronized void handleException(Throwable t) {
    super.handleException(t);
    if (METRICS_ENABLED && metrics != null) {
      requestFailed = true;
    }
    if (responseInProgress != null) {
      responseInProgress.handleException(t);
    }
    if (ws != null) {
      ws.handleException(t);
    }
  }

  protected void addFuture(Handler<AsyncResult<Void>> completionHandler, ChannelFuture future) {
    super.addFuture(completionHandler, future);
  }

  @Override
  protected boolean supportsFileRegion() {
    return super.supportsFileRegion() && chctx.pipeline().get(HttpChunkContentCompressor.class) == null;
  }

  protected ChannelFuture sendFile(RandomAccessFile file, long offset, long length) throws IOException {
    return super.sendFile(file, offset, length);
  }

  private void handleError(HttpObject obj) {
    DecoderResult result = obj.decoderResult();
    Throwable cause = result.cause();
    if (cause instanceof TooLongFrameException) {
      String causeMsg = cause.getMessage();
      HttpVersion version;
      if (obj instanceof HttpRequest) {
        version = ((HttpRequest) obj).protocolVersion();
      } else if (requestInProgress != null) {
        version = requestInProgress.version() == io.vertx.core.http.HttpVersion.HTTP_1_0 ? HttpVersion.HTTP_1_0 : HttpVersion.HTTP_1_1;
      } else {
        version = HttpVersion.HTTP_1_1;
      }
      HttpResponseStatus status = causeMsg.startsWith("An HTTP line is larger than") ? HttpResponseStatus.REQUEST_URI_TOO_LONG : HttpResponseStatus.BAD_REQUEST;
      DefaultFullHttpResponse resp = new DefaultFullHttpResponse(version, status);
      ChannelPromise fut = chctx.newPromise();
      writeToChannel(resp, fut);
      fut.addListener(res -> {
        handler().fail(result.cause());
      });
    } else {
      handler().fail(result.cause());
    }
  }

  private long getBytes(Object obj) {
    if (obj == null) return 0;

    if (obj instanceof Buffer) {
      return ((Buffer) obj).length();
    } else if (obj instanceof ByteBuf) {
      return ((ByteBuf) obj).readableBytes();
    } else if (obj instanceof HttpContent) {
      return ((HttpContent) obj).content().readableBytes();
    } else if (obj instanceof WebSocketFrame) {
      return ((WebSocketFrameInternal) obj).length();
    } else if (obj instanceof FileRegion) {
      return ((FileRegion) obj).count();
    } else if (obj instanceof ChunkedFile) {
      ChunkedFile file = (ChunkedFile) obj;
      return file.endOffset() - file.startOffset();
    } else {
      return -1;
    }
  }

  private static Handler<HttpServerRequest> requestHandler(HttpHandlers handler) {
    if (handler.connectionHandler != null) {
      class Adapter implements Handler<HttpServerRequest> {
        private boolean isFirst = true;
        @Override
        public void handle(HttpServerRequest request) {
          if (isFirst) {
            isFirst = false;
            handler.connectionHandler.handle(request.connection());
          }
          handler.requestHandler.handle(request);
        }
      }
      return new Adapter();
    } else {
      return handler.requestHandler;
    }
  }
}
