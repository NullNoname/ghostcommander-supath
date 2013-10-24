package com.ghostsq.commander;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;

import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.IBinder;
import android.util.Log;

public class StreamServer extends Service {
    private final static String TAG = "StreamServer";
    private final static String CRLF = "\r\n";
    private Context ctx;
    public  ListenThread  thread = null;
    public  WifiLock wifiLock = null;
    public  String last_host = null;
    public  CommanderAdapter ca = null;
    
    // pass the credentials through static is better then through a foreign application
    public  static Credentials credentials = null;  

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;  //getApplicationContext();
        WifiManager manager = (WifiManager) getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( TAG );
        wifiLock.setReferenceCounted( false );
    }
    
    @Override
    public void onStart( Intent intent, int start_id ) {
        super.onStart( intent, start_id );
        //Log.d( TAG, "onStart" );
        if( thread == null ) {
            //Log.d( TAG, "Starting the server thread" );
            thread = new ListenThread();
            thread.start();
            getBaseContext();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //Log.d( TAG, "onDestroy" );
        if( thread != null && thread.isAlive() ) {
            thread.close();
            thread.interrupt();
            try {
                thread.join( 10000 );
            }
            catch( InterruptedException e ) {
                e.printStackTrace();
            }
            if( thread.isAlive() )
                Log.e( TAG, "Listen tread has ignored the interruption" );
        }
    }

    private class ListenThread extends Thread {
        private final static String TAG = "GCSS.ListenThread";
        private Thread stream_thread;
        public  ServerSocket ss = null;
        public  long lastUsed = System.currentTimeMillis();      

        public void run() {
            try {
                //Log.d( TAG, "started" );
                setName( TAG );
                setPriority( Thread.MIN_PRIORITY );
                new Thread( new Runnable() {
                    @Override
                    public void run() {
                        while( true ) {
                            try {
                                synchronized( ListenThread.this ) {
                                    final int max_idle = 100000;
                                    ListenThread.this.wait( max_idle );
                                    //Log.d( TAG, "Checking the idle time... last used: " + (System.currentTimeMillis()-lastUsed) + "ms ago " );
                                    if( System.currentTimeMillis() - max_idle > lastUsed ) {
                                        //Log.d( TAG, "Time to closer the listen thread" );
                                        ListenThread.this.close();
                                        break;
                                    }
                                }
                            } catch( InterruptedException e ) {
                                e.printStackTrace();
                            }
                        }
                        //Log.d( TAG, "Closer thread stopped" );
                    }
                }, "Closer" ).start();
                
                StreamServer.this.wifiLock.acquire();
                //Log.d( TAG, "WiFi lock" );
                synchronized( this ) {
                    ss = new ServerSocket( 5322 );
                }
                int count = 0;
                while( !isInterrupted() ) {
                    //Log.d( TAG, "Listening for a new connection..." );
                    Socket data_socket = ss.accept();
                    //Log.d( TAG, "Connection accepted" );
                    if( data_socket != null && data_socket.isConnected() ) {
                        int tn = -1; //count++
                        stream_thread = new StreamingThread( data_socket, tn );
                        stream_thread.start();
                    }
                    touch();
                }
            }
            catch( Exception e ) {
                Log.w( TAG, "Exception", e );
            }
            finally {
                StreamServer.this.wifiLock.release();
                //Log.d( TAG, "WiFi lock release" );
                this.close();
            }
            StreamServer.this.stopSelf();
        }

        public synchronized void touch() {
            lastUsed = System.currentTimeMillis();
        }        
        
        public synchronized void close() {
            try {
                if( ss != null ) {
                    ss.close();
                    ss = null;
                }
                if( stream_thread != null && stream_thread.isAlive() ) {
                    stream_thread.interrupt();
                    stream_thread = null;
                }
                if( ca != null ) {
                    ca.prepareToDestroy();
                    ca = null;
                }
            }
            catch( IOException e ) {
                e.printStackTrace();
            }
        }
    };    

    private class StreamingThread extends Thread {
        private final static String TAG = "GCSS.StreamingThread";
        private Socket data_socket;
        private int num_id;
        private boolean  l = false;

        public StreamingThread( Socket data_socket_, int num_id_ ) {
            data_socket = data_socket_;
            num_id = num_id_;
            l = num_id >= 0; 
        }
        
        private void Log( String s ) {
            if( l ) Log.d( TAG, "" + num_id + ": " + s );
        }
        
        public void run() {
            InputStream  is = null;
            OutputStream os = null;
            try {
                if( l ) Log( "Thread started" );
                setName( TAG );
                if( data_socket == null || !data_socket.isConnected() ) {
                    Log.e( TAG, "Invalid data socked" );
                    return;
                }
                os = data_socket.getOutputStream();
                if( os == null ) {
                    Log.e( TAG, "Can't get the output stream" );
                    return;
                }
                  
                OutputStreamWriter osw = new OutputStreamWriter( os );
                yield();
                
                is = data_socket.getInputStream();
                if( is == null ) {
                    Log.e( TAG, "Can't get the input stream" );
                    SendStatus( osw, 500 );
                    return;
                }
                
                InputStreamReader isr = new InputStreamReader( is );
                BufferedReader br = new BufferedReader( isr );
                String cmd = br.readLine();
                if( !Utils.str( cmd ) ) {
                    Log.e( TAG, "Invalid HTTP input" );
                    SendStatus( osw, 400 );
                    return;
                }
                                
                String[] parts = cmd.split( " " );
                if( l ) Log( cmd );
                if( parts.length <= 1 ) {
                    Log.e( TAG, "Invalid HTTP input" );
                    SendStatus( osw, 400 );
                    return;
                }
                String passed_uri_s = parts[1].substring( 1 );
                if( !Utils.str( passed_uri_s ) ) {
                    Log.w( TAG, "No URI passed in the request" );
                    SendStatus( osw, 404 );
                    return;
                } 
                Uri uri = Uri.parse( Uri.decode( passed_uri_s ) );
                if( uri == null || !Utils.str( uri.getPath() ) ) {
                    Log.w( TAG, "Wrong URI passed in the request" );
                    SendStatus( osw, 404 );
                    return;
                } 
                if( l ) Log( "Requested URI: " + uri );
                
                long offset = 0;
                while( br.ready() ) {
                    String hl = br.readLine();
                    if( hl != null ) {
                        if( l ) Log( hl );
                        if( hl.startsWith( "Range: bytes=" ) ) {
                            int end = hl.indexOf( '-', 13 );
                            String range_s = hl.substring( 13, end );
                            try {
                                offset = Long.parseLong( range_s );
                            } catch( NumberFormatException nfe ) {}
                        }
                    }
                }
                                
                String scheme = uri.getScheme();
                int ca_type = CA.GetAdapterTypeId( scheme );
                String host = uri.getHost();
                if( ca != null ) { 
                    if( ca.getType() != ca_type )
                        throw new Exception( "Can't switch to different adapter types: " + ca.getType() + "!=" + ca_type );
                }
                else {
                    ca = CA.CreateAdapterInstance( ca_type, ctx );
                    if( ca == null ) {
                        Log.e( TAG, "Can't create the adapter for: " + scheme );
                        SendStatus( osw, 500 );
                        return;
                    }
                    ca.Init( null );
                    if( l ) Log( "Adapter is created" );
                }
                last_host = host;
                if( credentials != null )
                    ca.setCredentials( credentials );
                Item item = ca.getItem( uri );
                if( item == null ) {
                    Log.e( TAG, "Can't get the item for " + uri );
                    SendStatus( osw, 404 );
                    return;
                } 
                InputStream cs = ca.getContent( uri, offset );
                if( cs == null ) {
                    Log.e( TAG, "Can't get the content for " + uri );
                    SendStatus( osw, 500 );
                    return;
                } 
                if( offset > 0 && item != null ) {
                    SendStatus( osw, 206 );
                } else {
                    SendStatus( osw, 200 );
                }
                String fn = "zip".equals( scheme ) ? uri.getFragment() : uri.getLastPathSegment();
                if( fn != null ) {
                    String ext = Utils.getFileExt( fn );
                    String mime = Utils.getMimeByExt( ext );
                    if( l ) Log( "Content-Type: " + mime );
                    osw.write( "Content-Type: " + mime + CRLF );
                }
                else
                    osw.write( "Content-Type: application/octet-stream" + CRLF );
                String content_range  = "Content-Range: bytes "; 
                String content_length = "Content-Length: ";
                if( offset == 0 ) {
                    content_length += item.size;
                    content_range  += "0-" + (item.size-1) + "/" + item.size;
                }
                else {
                    content_length += (item.size - offset);
                    content_range  += offset + "-" + (item.size-1) + "/" + item.size;
                }
                osw.write( content_length + CRLF );
                osw.write( content_range + CRLF );
                if( l ) Log( content_length );
                if( l ) Log( content_range );

                osw.write( "Connection: close" + CRLF );
                osw.write( CRLF );
                osw.flush();
                
                ReaderThread rt = new ReaderThread( cs, num_id );
                rt.start();
                setPriority( Thread.MAX_PRIORITY );
                int count = 0;
                while( rt.isAlive() ) {
                    try {
                        if( br.ready() )
                            if( l ) Log( "HTTP additional command arrived!!! " + br.readLine() );
                        thread.touch();
                        byte[] out_buf = rt.getOutputBuffer();
                        if( out_buf == null ) break;
                        int n = rt.GetDataSize();
                        if( n < 0 )
                            break;
                        if( l ) Log( "      W..." );
                        os.write( out_buf, 0, n );
                        if( l ) Log( "      ...W " + n + "/" + ( count += n ) );
                        rt.doneOutput( false );
                    }
                    catch( Exception e ) {
                        if( l ) Log( "write exception: " + e.getMessage() );
                        rt.doneOutput( true );
                        break;
                    }
                }                                            
                ca.closeStream( cs );
                //rt.interrupt();
                if( l ) Log( "----------- done -------------" );
            }
            catch( Exception e ) {
                Log.e( TAG, "Exception", e );
            }
            finally {
                if( l ) Log( "Thread exits" );
                try {
                    if( is != null ) is.close();
                    if( os != null ) os.close();
                }
                catch( IOException e ) {
                    Log.e( TAG, "Exception on Closing", e );
                }
            }
        }
        private void SendStatus( OutputStreamWriter osw, int code ) throws IOException {
            final String http = "HTTP/1.1 ";
            String descr;
            switch( code ) {
            case 200: descr = "OK";                     break;
            case 206: descr = "Partial Content";        break;
            case 400: descr = "Invalid";                break;
            case 404: descr = "Not found";              break;
            case 416: descr = "Bad Requested Range";    break;
            case 500: descr = "Server error";           break;
            default:  descr = "";  
            }
            String resp = http + code + " " + descr; 
            osw.write( resp + CRLF );
            if( l ) Log( resp );
            Date date = new Date();
            osw.write( "Date: " + date + CRLF );
            if( l ) Log( "Date: " + date + CRLF );
            
        }        
    };
    
    class ReaderThread extends Thread {
        private final static String TAG = "GCSS.RT";
        private InputStream is;
        private int      roller = 0, chunk = 4096;
        private final static int MAX = 32768; //524288
        private byte[][] bufs = { new byte[MAX], new byte[MAX] };
        private byte[]   out_buf = null;
        private int      data_size = 0;
        private int      num_id;
        private boolean  stop = false;
        private boolean  l = false;
        
        public ReaderThread( InputStream is_, int num_id_ ) {
            is = is_;
            setName( TAG );
            num_id = num_id_;
            l = num_id >= 0; 
        }
        
        private void Log( String s ) {
            if( l ) Log.d( TAG, "" + num_id + ": " + s );
        }
        public void run() {
            try {
                setPriority( Thread.MAX_PRIORITY );
                int count = 0;
                while( !stop ) {
                    byte[] inp_buf = bufs[roller++ % 2];
                    if( l ) Log( "R..." );
                    int has_read = 0;
                    has_read = is.read( inp_buf, 0, chunk );
                    if( stop || has_read < 0 )
                        break;
                    if( has_read == chunk && chunk < MAX )
                        chunk <<= 1;
                    if( chunk > MAX )
                        chunk = MAX;                    
                    if( l ) Log( "...R " + has_read + "/" + ( count += has_read ) );
                    synchronized( this ) {
                        int wcount = 0; 
                        if( l ) Log( "?.." );
                        while( out_buf != null ) {
                            wait( 10 );
                            wcount += 10;
                        }
                        if( l ) Log( "...! (" + wcount + "ms)" );
                        out_buf = inp_buf;
                        data_size = has_read; 
                        if( l ) Log( "O=I ->" );
                        notify();
                    }
                }
            } catch( Throwable e ) {
                Log.e( TAG, "" + num_id + ": ", e );
            }
            if( l ) Log( "The read thread is done!" );
        }
        public synchronized byte[] getOutputBuffer() throws InterruptedException {
            int wcount = 0;
            if( l ) Log( "       ?.." );
            while( out_buf == null && this.isAlive() ) {
                wait( 10 );
                wcount += 10;
            }
            
            if( out_buf != null ) 
                if( l ) Log( "      ..! (" + wcount + "ms)" );
            else
                if( l ) Log( "X" );
            
            return out_buf;
        }
        public int GetDataSize() {
            int ds = data_size;
            data_size = 0;
            return ds;
        }
        public synchronized void doneOutput( boolean stop_ ) {
            stop = stop_;
            out_buf = null;
            if( l ) Log( "    <- O done" );
            notify();
        }
    };
    
    
    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }
}