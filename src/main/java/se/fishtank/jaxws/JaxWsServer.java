/**
 * Copyright (c) 2012, Christer Sandberg
 */
package se.fishtank.jaxws;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * A JAX-WS only server.
 *
 * @author Christer Sandberg
 * @author honwhy.wang
 */
public final class JaxWsServer {

    /** Whether the server is started or not. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Channel group for all channels. */
    private ChannelGroup channels;

    /** Bootstrap instance for this server. */
    private ServerBootstrap bootstrap;

    private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	
    /**
     * Start the server.
     *
     * @param address Hostname and port.
     * @param mappings {@linkplain JaxwsHandler#JaxwsHandler(java.util.Map) Endpoint mappings.}
     * @return {@code false} if the server is already started, {@code true} otherwise.
     * @throws Exception 
     */
    public void start(final InetSocketAddress address, final Map<String, Object> mappings) throws Exception {
    	
        bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();

		bootstrap = new ServerBootstrap();
		bootstrap.option(ChannelOption.TCP_NODELAY, true);
		bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
		bootstrap.option(ChannelOption.SO_REUSEADDR, true);
		bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>(){

					@Override
					protected void initChannel(SocketChannel ch) throws Exception {
						ChannelPipeline p = ch.pipeline();
						p.addLast(new HttpRequestDecoder());
						p.addLast(new HttpResponseEncoder());
						p.addLast(new HttpObjectAggregator(65536));
						p.addLast(new ChunkedWriteHandler());
						JaxwsHandler handler = new JaxwsHandler(channels, mappings);
						p.addLast(handler);
						
					}
					
				});

		Channel ch = bootstrap.bind(address).sync().channel();
		ch.closeFuture().sync();
    }

    /**
     * Stop the server.
     *
     * @return {@code false} if the server is already stopped, {@code true} otherwise.
     */
    public boolean stop() {
        if (running.compareAndSet(true, false)) {
            channels.close().awaitUninterruptibly();
            //bootstrap.releaseExternalResources();
        }

        return false;
    }


}
