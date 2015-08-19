package com.k2ka.library.vrtmp.io.packets;

import com.k2ka.library.vrtmp.io.ChunkStreamInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 *
 * @author k2ka
 */
public abstract class RtmpPacket {
    private final static String TAG = "RtmpPacket";
    protected RtmpHeader header;

    public RtmpPacket(RtmpHeader header) {
        this.header = header;
    }

    public RtmpHeader getHeader() {
        return header;
    }
    
    public abstract void readBody(InputStream in) throws IOException;
    
    protected abstract void writeBody(OutputStream out) throws IOException;
           
    public void writeTo(OutputStream out, final int chunkSize, final ChunkStreamInfo chunkStreamInfo) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeBody(baos);        
        byte[] body = baos.toByteArray();        
        header.setPacketLength(body.length);
        // Write header for first chunk
        header.writeTo(out, chunkStreamInfo);
        int remainingBytes = body.length;
        int pos = 0;
        while (remainingBytes > chunkSize) {
            out.write(body, pos, chunkSize);
            remainingBytes -= chunkSize;
            pos += chunkSize;
            header.writeAggregateHeaderByte(out);
        }
        out.write(body, pos, remainingBytes);
        out.flush();
    }
}
