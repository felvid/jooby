/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal.netty;

import static io.jooby.ServerOptions._4KB;
import static io.jooby.ServerOptions._8KB;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import io.jooby.Router;
import io.jooby.internal.netty.http2.NettyHttp2Configurer;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.ssl.SslContext;

public class NettyPipeline extends ChannelInitializer<SocketChannel> {
  private static final String H2_HANDSHAKE = "h2-handshake";

  private final Router router;
  private final HttpDataFactory factory;
  private final Integer compressionLevel;
  private final int bufferSize;
  private final long maxRequestSize;
  private final boolean defaultHeaders;
  private final ScheduledExecutorService service;
  private final SslContext sslContext;
  private final boolean http2;
  private final boolean is100ContinueExpected;

  public NettyPipeline(
      ScheduledExecutorService service,
      Router router,
      HttpDataFactory factory,
      SslContext sslContext,
      boolean http2,
      boolean defaultHeaders,
      Integer compressionLevel,
      int bufferSize,
      long maxRequestSize,
      boolean is100ContinueExpected) {
    this.service = service;
    this.router = router;
    this.factory = factory;
    this.sslContext = sslContext;
    this.http2 = http2;
    this.defaultHeaders = defaultHeaders;
    this.compressionLevel = compressionLevel;
    this.bufferSize = bufferSize;
    this.maxRequestSize = maxRequestSize;
    this.is100ContinueExpected = is100ContinueExpected;
  }

  @Override
  public void initChannel(SocketChannel ch) {
    ChannelPipeline p = ch.pipeline();
    if (sslContext != null) {
      p.addLast("ssl", sslContext.newHandler(ch.alloc()));
    }
    if (!http2) {
      http11(p);
    } else {
      Http2Settings settings = new Http2Settings(maxRequestSize, sslContext != null);
      Http2Extension extension =
          new Http2Extension(
              settings, this::http11, this::http11Upgrade, this::http2, this::http2c);
      NettyHttp2Configurer configurer = new NettyHttp2Configurer();
      ChannelInboundHandler handshake = configurer.configure(extension);

      p.addLast(H2_HANDSHAKE, handshake);

      setupCompression(p);

      setupExpectContinue(p);

      p.addLast("handler", createHandler(true));
    }
  }

  private void setupExpectContinue(ChannelPipeline p) {
    if (is100ContinueExpected) {
      p.addLast("expect-continue", new HttpServerExpectContinueHandler());
    }
  }

  private void setupCompression(ChannelPipeline p) {
    if (compressionLevel != null) {
      p.addLast("compressor", new HttpChunkContentCompressor(compressionLevel));
      p.addLast("ws-compressor", new NettyWebSocketCompressor(compressionLevel));
    }
  }

  private void http2(ChannelPipeline pipeline, Supplier<ChannelOutboundHandler> factory) {
    pipeline.addAfter(H2_HANDSHAKE, "http2", factory.get());
  }

  private void http2c(ChannelPipeline pipeline, Supplier<ChannelOutboundHandler> factory) {
    pipeline.addAfter(H2_HANDSHAKE, "http2", factory.get());
  }

  private void http11Upgrade(
      ChannelPipeline pipeline, Supplier<HttpServerUpgradeHandler.UpgradeCodec> factory) {
    // direct http1 to h2c
    HttpServerCodec serverCodec = createServerCodec();
    pipeline.addAfter(H2_HANDSHAKE, "codec", serverCodec);
    pipeline.addAfter(
        "codec",
        "h2upgrade",
        new HttpServerUpgradeHandler(
            serverCodec,
            protocol -> protocol.toString().equals("h2c") ? factory.get() : null,
            (int) maxRequestSize));
  }

  private void http11(ChannelPipeline p) {
    HttpServerCodec codec = createServerCodec();
    p.addLast("codec", codec);
    setupCompression(p);
    setupExpectContinue(p);
    p.addLast("handler", createHandler(false));
  }

  HttpServerCodec createServerCodec() {
    return new HttpServerCodec(_4KB, _8KB, bufferSize, false);
  }

  private NettyHandler createHandler(boolean http2) {
    return new NettyHandler(
        service, router, maxRequestSize, bufferSize, factory, defaultHeaders, http2);
  }
}
