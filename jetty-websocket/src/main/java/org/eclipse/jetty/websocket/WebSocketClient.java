package org.eclipse.jetty.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.Timeout;

public class WebSocketClient extends AggregateLifeCycle
{
    private final static Logger __log = org.eclipse.jetty.util.log.Log.getLogger(WebSocketClient.class.getCanonicalName());
    private final static Random __random = new Random();
    private final static ByteArrayBuffer __ACCEPT = new ByteArrayBuffer.CaseInsensitive("Sec-WebSocket-Accept");
    
    private final ThreadPool _threadPool;
    private final Selector _selector=new Selector();
    private final Timeout _connectQ=new Timeout();
    private int _connectTimeout=30000;
    private int _bufferSize=64*1024;
    private boolean _blockingConnect=false;
    
    private WebSocketBuffers _buffers;
    
    public WebSocketClient(ThreadPool threadpool)
    {
        _threadPool=threadpool;
        addBean(_selector);
        addBean(_threadPool);
    }
    
    public WebSocketClient()
    {
        this(new QueuedThreadPool());
    }
    
    public SelectorManager getSelectorManager()
    {
        return _selector;
    }
    
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }
    
    public int getConnectTimeout()
    {
        return _connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _connectTimeout = connectTimeout;
    }
    
    public int getMaxIdleTime()
    {
        return (int)_selector.getMaxIdleTime();
    }

    public void setMaxIdleTime(int maxIdleTime)
    {
        _selector.setMaxIdleTime(maxIdleTime);
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }

    public boolean isBlockingConnect()
    {
        return _blockingConnect;
    }

    public void setBlockingConnect(boolean blockingConnect)
    {
        _blockingConnect = blockingConnect;
    }

    @Override
    protected void doStart() throws Exception
    {
        _buffers = new WebSocketBuffers(_bufferSize); 

        super.doStart();
        for (int i=0;i<_selector.getSelectSets();i++)
        {
            final int id=i;
            _threadPool.dispatch(new Runnable(){
                public void run()
                {
                    while(isRunning())
                    {
                        try
                        {
                            _selector.doSelect(id);
                        }
                        catch (IOException e)
                        {
                            __log.warn(e);
                        }
                    }
                }
            });
        }
        
        _connectQ.setDuration(_connectTimeout);
        _threadPool.dispatch(new Runnable(){
            public void run()
            {
                while(isRunning())
                {
                    try
                    {
                        Thread.sleep(200); // TODO configure?
                        _connectQ.tick(System.currentTimeMillis());
                    }
                    catch(Exception e)
                    {
                        __log.warn(e);
                    }
                }
            }
        });
    }

    public void open(URI uri, WebSocket websocket) throws IOException
    {
        open(uri,websocket,null,(int)_selector.getMaxIdleTime(),null,null);
    }
    
    public void open(URI uri, WebSocket websocket, String protocol,int maxIdleTime) throws IOException
    {
        open(uri,websocket,protocol,(int)_selector.getMaxIdleTime(),null,null);
    }
    
    public void open(URI uri, WebSocket websocket, String protocol,int maxIdleTime,Map<String,String> cookies) throws IOException
    {
        open(uri,websocket,protocol,(int)_selector.getMaxIdleTime(),cookies,null);
    }
    
    public void open(URI uri, WebSocket websocket, String protocol,int maxIdleTime,Map<String,String> cookies,List<String> extensions) throws IOException
    {
        if (!isStarted())
            throw new IllegalStateException("!started");
        String scheme=uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Bad WebSocket scheme '"+scheme+"'");
        if ("wss".equalsIgnoreCase(scheme))
            throw new IOException("wss not supported");
        
        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        channel.socket().setSoTimeout(getMaxIdleTime());

        InetSocketAddress address=new InetSocketAddress(uri.getHost(),uri.getPort());

        WebSocketHolder holder=new WebSocketHolder(websocket,uri,protocol,maxIdleTime,cookies,extensions,channel);
        

        _connectQ.schedule(holder);
        boolean thrown=true;
        try
        {
            if (isBlockingConnect())
            {
                channel.socket().connect(address,0);
                channel.configureBlocking(false);
            }
            else
            {
                channel.configureBlocking(false);
                channel.connect(address);
            }

            _selector.register( channel, holder);
            thrown=false;
        }
        finally
        {
            if (thrown)
                holder.cancel();
        }
    }
   
    
    class Selector extends SelectorManager
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadPool.dispatch(task);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, final SelectionKey sKey) throws IOException
        {
            return new SelectChannelEndPoint(channel,selectSet,sKey);
        }

        @Override
        protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            WebSocketHolder holder = (WebSocketHolder) endpoint.getSelectionKey().attachment();
            return new HandshakeConnection(endpoint,holder);
        }
        
        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
            endpoint.getConnection().closed();
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            if (!(attachment instanceof WebSocketHolder))
                super.connectionFailed(channel,ex,attachment);
            else
            {
                __log.debug(ex);
                WebSocketHolder holder = (WebSocketHolder)attachment;
                holder.cancel();
                holder.getWebSocket().onError(ex.toString(),ex);
            } 
        }
    }
    
    class HandshakeConnection extends AbstractConnection
    {
        private final SelectChannelEndPoint _endp;
        private final WebSocketHolder _holder;
        private final String _key;
        private final HttpParser _parser;
        private String _accept;
        private String _error;
        
        
        public HandshakeConnection(SelectChannelEndPoint endpoint, WebSocketHolder holder)
        {
            super(endpoint,System.currentTimeMillis());
            _endp=endpoint;
            _holder=holder;
            
            byte[] bytes=new byte[16];
            __random.nextBytes(bytes);
            _key=new String(B64Code.encode(bytes));
            
            Buffers buffers = new SimpleBuffers(_buffers.getBuffer(),null);
            _parser=new HttpParser(buffers,_endp,
            
            new HttpParser.EventHandler()
            {
                @Override
                public void startResponse(Buffer version, int status, Buffer reason) throws IOException
                {
                    if (status!=101)
                    {
                        _error="Bad response status "+status+" "+reason;
                        _endp.close();
                    }
                }
                
                @Override
                public void parsedHeader(Buffer name, Buffer value) throws IOException
                {
                    if (__ACCEPT.equals(name))
                        _accept=value.toString();
                }

                @Override
                public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
                {
                    if (_error==null)
                        _error="Bad response: "+method+" "+url+" "+version;
                    _endp.close();
                }
                
                @Override
                public void content(Buffer ref) throws IOException
                {
                    if (_error==null)
                        _error="Bad response. "+ref.length()+"B of content?";
                    _endp.close();
                }
            });
            
            
            String request=
                "GET "+_holder.getURI().getPath()+" HTTP/1.1\r\n"+
                "Host: "+holder.getURI().getHost()+":"+_holder.getURI().getPort()+"\r\n"+
                "Upgrade: websocket\r\n"+
                "Connection: Upgrade\r\n"+
                "Sec-WebSocket-Key: "+_key+"\r\n"+
                "Sec-WebSocket-Origin: http://example.com\r\n"+
                "Sec-WebSocket-Version: 8\r\n";
            
            if (holder.getProtocol()!=null)
                request+="Sec-WebSocket-Protocol: "+holder.getProtocol()+"\r\n";
                
            if (holder.getCookies()!=null && holder.getCookies().size()>0)
            {
                for (String cookie : holder.getCookies().keySet())
                    request+="Cookie: "+QuotedStringTokenizer.quoteIfNeeded(cookie,HttpFields.__COOKIE_DELIM)+
                    "="+
                    QuotedStringTokenizer.quoteIfNeeded(holder.getCookies().get(cookie),HttpFields.__COOKIE_DELIM)+
                    "\r\n";
            }

            request+="\r\n";
            
            // TODO extensions
            
            try
            {
                ByteArrayBuffer handshake = new ByteArrayBuffer(request);
                int len=handshake.length();
                if (len!=_endp.flush(handshake))
                    throw new IOException("incomplete");
            }
            catch(IOException e)
            {
                __log.debug(e);
                _holder.getWebSocket().onError("Handshake failed",e);
            }
        }

        public Connection handle() throws IOException
        {
            while (_endp.isOpen() && !_parser.isComplete())
            {
                switch (_parser.parseAvailable())
                {
                    case -1:
                        _holder.cancel();
                        _holder.getWebSocket().onError("EOF",new EOFException());
                        return this;
                    case 0:
                        return this;
                    default:
                        break;    
                }
            }
            
            if (_error==null && _accept==null)
                _error="No Sec-WebSocket-Accept";
            else if (_error==null && !WebSocketConnectionD10.hashKey(_key).equals(_accept))
                _error="Bad Sec-WebSocket-Accept";
            else
            {
                Buffer header=_parser.getHeaderBuffer();
                WebSocketConnectionD10 connection = new WebSocketConnectionD10(_holder.getWebSocket(),_endp,_buffers,System.currentTimeMillis(),_holder.getMaxIdleTime(),_holder.getProtocol(),null,10, new WebSocketGeneratorD10.RandomMaskGen());
                
                if (header.hasContent())
                    connection.fillBuffersFrom(header);
                _buffers.returnBuffer(header);

                if (_holder.getWebSocket() instanceof WebSocket.OnFrame)
                    ((WebSocket.OnFrame)_holder.getWebSocket()).onHandshake((WebSocket.FrameConnection)connection.getConnection());
                _holder.cancel();
                _holder.getWebSocket().onOpen(connection.getConnection());
                return connection;
            }

            _endp.close();
            return this;
        }

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void closed()
        {
            _holder.cancel();
            _holder.getWebSocket().onError(_error==null?"EOF":_error,null);
        }
    }


    class WebSocketHolder extends Timeout.Task
    {
        final WebSocket _websocket;;
        final URI _uri;
        final String _protocol;
        final int _maxIdleTime;
        final Map<String,String> _cookies;
        final List<String> _extensions;
        final ByteChannel _channel;
        
        public WebSocketHolder(WebSocket websocket, URI uri, String protocol, int maxIdleTime, Map<String,String> cookies,List<String> extensions, ByteChannel channel)
        {
            _websocket=websocket;
            _uri=uri;
            _protocol=protocol;
            _maxIdleTime=maxIdleTime;
            _cookies=cookies;
            _extensions=extensions;
            _channel=channel;
        }
        
        public Map<String,String> getCookies()
        {
            return _cookies;
        }

        public String getProtocol()
        {
            return _protocol;
        }

        public WebSocket getWebSocket()
        {
            return _websocket;
        }
        
        public URI getURI()
        {
            return _uri;
        }
        
        public int getMaxIdleTime()
        {
            return _maxIdleTime;
        }

        @Override
        public void expired()
        {
            try
            {
                __log.debug("expired "+this);
                getWebSocket().onError("expired",null);
                _channel.close();
            }
            catch(IOException e)
            {
                __log.ignore(e);
            }
        }
        
        public String toString()
        {
            return "[" + _uri + ","+_websocket+"]@"+hashCode();
        }
    }
    
}