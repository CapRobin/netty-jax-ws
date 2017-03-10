/**
 * Copyright (c) 2012, Christer Sandberg
 */
package se.fishtank.jaxws;

import java.net.URL;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.ws.WebServiceException;

import com.sun.istack.NotNull;
import com.sun.xml.ws.api.BindingID;
import com.sun.xml.ws.api.message.Packet;
import com.sun.xml.ws.api.server.*;
import com.sun.xml.ws.binding.BindingImpl;
import com.sun.xml.ws.server.EndpointFactory;
import com.sun.xml.ws.server.ServerRtException;
import com.sun.xml.ws.transport.http.HttpAdapter;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * A {@link ChannelUpstreamHandler} for JAX-WS.
 *
 * @author Christer Sandberg
 * @author honwhy.wang
 */
public class JaxwsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /** {@link ChannelGroup} associated with this handler. */
    private final ChannelGroup channels;

    /** Endpoint mappings. */
    private final HashMap<String, HttpAdapter> endpointMappings;

    /**
     * Create a new insance.
     * <p/>
     * The specified {@code mappings} maps a context path to an
     * instance that's annotated with {@link javax.jws.WebService}
     * or {@link javax.xml.ws.WebServiceProvider}.
     * <br/>
     * <pre>
     *     /foo -> FooWebService
     * </pre>
     *
     * @param mappings Endpoint mappings.
     */
    public JaxwsHandler(Map<String, Object> mappings) {
        this(null, mappings);
    }

    /**
     * Create a new instance.
     *
     * @see JaxwsHandler#JaxwsHandler(java.util.Map)
     *
     * @param channels Channel group for connected channels.
     * @param mappings Endpoint mappings.
     */
    public JaxwsHandler(ChannelGroup channels, Map<String, Object> mappings) {
        this.channels = channels;
        this.endpointMappings = new HashMap<String, HttpAdapter>(mappings.size());
        for (Map.Entry<String, Object> entry : mappings.entrySet())
            this.endpointMappings.put(entry.getKey(), createEndpointAdapter(entry.getValue()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (channels != null) channels.add(ctx.channel());
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        Channel channel = ctx.channel();
		if (channel.isActive()) {
		    Throwable cause = e.getCause();
		    HttpResponseStatus status = (cause instanceof io.netty.handler.codec.TooLongFrameException) ?
		            HttpResponseStatus.BAD_REQUEST : HttpResponseStatus.INTERNAL_SERVER_ERROR;

		    String content = "Failure:\r\n" + cause.toString() + "\r\n";

		    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
		    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
		    //response.setContent();

		    channel.write(response).addListener(ChannelFutureListener.CLOSE);
		}

    }

    /**
     * Create a HTTP adapter for the {@link javax.jws.WebService}
     * or {@link javax.xml.ws.WebServiceProvider} annotated implementor.
     *
     * @param implementor Web Service implementor.
     * @return A HTTP adapter.
     */
    private HttpAdapter createEndpointAdapter(Object implementor) {
        // Check for WSDL location.
        Class implType = implementor.getClass();
        EndpointFactory.verifyImplementorClass(implType);
        String wsdlLocation = EndpointFactory.getWsdlLocation(implType);

        SDDocumentSource primaryWsdl = null;
        if (wsdlLocation != null) {
            ClassLoader cl = implType.getClassLoader();
            URL wsdlUrl = cl.getResource(wsdlLocation);
            if (wsdlUrl == null)
                throw new ServerRtException("cannot.load.wsdl", wsdlLocation);

            primaryWsdl = SDDocumentSource.create(wsdlUrl);
        }

        WSEndpoint endpoint = WSEndpoint.create(implementor.getClass(), true,
                InstanceResolver.createSingleton(implementor).createInvoker(),
                null, null,null,
                BindingImpl.create(BindingID.parse(implementor.getClass())),
                primaryWsdl,
                null, null, true);

        return HttpAdapter.createAlone(endpoint);
    }

    /**
     * Create a new {@link WebServiceContextDelegate}.
     *
     * @param adapter HTTP adapter.
     * @param jaxwsRequestUrl JAX-WS URL for the request.
     * @return A new {@link WebServiceContextDelegate}.
     */
    private WebServiceContextDelegate createDelegate(final HttpAdapter adapter, final JaxwsRequestUrl jaxwsRequestUrl) {
        return new WebServiceContextDelegate() {
            @Override
            public Principal getUserPrincipal(@NotNull Packet request) {
                return null;
            }

            @Override
            public boolean isUserInRole(@NotNull Packet request, String role) {
                return false;
            }

            @Override
            public @NotNull String getEPRAddress(@NotNull Packet request, @NotNull WSEndpoint endpoint) {
                PortAddressResolver resolver = adapter.owner.createPortAddressResolver(jaxwsRequestUrl.baseAddress);
                QName portName = endpoint.getPortName();
                String address = resolver.getAddressFor(endpoint.getServiceName(), portName.getLocalPart());
                if (address == null)
                    throw new WebServiceException("WsservletMessages.SERVLET_NO_ADDRESS_AVAILABLE(" + portName + ")");

                return address;
            }

            @Override
            public String getWSDLAddress(@NotNull Packet request, @NotNull WSEndpoint endpoint) {
                return getEPRAddress(request, endpoint) + "?wsdl";
            }
        };
    }

    /**
     * Checks whether a query string represents a WSDL request.
     *
     * @param queryString The query string to check.
     * @return {@code true} if the specified query string represents a WSDL request.
     */
    private boolean isWsdlRequest(String queryString) {
        return queryString != null && (queryString.equalsIgnoreCase("wsdl") || queryString.startsWith("xsd="));
    }


	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		Channel channel = ctx.channel();
        //HttpRequest request = (HttpRequest) e.getMessage();
        HttpVersion httpVersion = request.getProtocolVersion();

        JaxwsRequestUrl jaxwsRequestUrl = JaxwsRequestUrl.newInstance(ctx, request);
        String lookup = jaxwsRequestUrl.contextPath.isEmpty() ? "/" : jaxwsRequestUrl.contextPath;
        lookup = lookup + (null == jaxwsRequestUrl.pathInfo || jaxwsRequestUrl.pathInfo.isEmpty() ? "" : jaxwsRequestUrl.pathInfo);
        HttpAdapter adapter = endpointMappings.get(lookup);
        if (adapter == null) {
            DefaultHttpResponse response = new DefaultHttpResponse(httpVersion, HttpResponseStatus.NOT_FOUND);
            channel.write(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        boolean keepAlive = HttpHeaders.isKeepAlive(request);

        FullHttpResponse response = new DefaultFullHttpResponse(httpVersion, HttpResponseStatus.OK);
        WebServiceContextDelegate delegate = createDelegate(adapter, jaxwsRequestUrl);
        JaxwsConnection connection = new JaxwsConnection(request, response, jaxwsRequestUrl, delegate);

        if (request.getMethod() == HttpMethod.GET && isWsdlRequest(jaxwsRequestUrl.queryString)) {
            adapter.publishWSDL(connection);
        } else {
            adapter.handle(connection);
        }

        // Let's honor the keep-alive header since JAX-WS RI always seem to invoke close on the
        // connection, and I don't really know if that means that we should close the underlying
        // one or not.
        if (keepAlive) {
        	response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
            if (response.headers().get(HttpHeaders.Names.CONTENT_LENGTH) == null)
            	response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, response.content().readableBytes());
        } else {
        	response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        }

        ChannelFuture future = channel.write(response);
        if (!keepAlive)
            future.addListener(ChannelFutureListener.CLOSE);
		
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		super.channelReadComplete(ctx);
		ctx.flush();
	}

}
