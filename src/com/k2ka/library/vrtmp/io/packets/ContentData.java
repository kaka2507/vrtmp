package com.k2ka.library.vrtmp.io.packets;

import com.k2ka.library.vrtmp.utils.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Content (audio/video) data packet base
 *  
 * @author k2ka
 */
public abstract class ContentData extends RtmpPacket {

    protected byte[] data;
    protected int length;

    public ContentData(RtmpHeader header) {
        super(header);
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data, int length) {
        this.data = data;
        this.length = length;
    }

    @Override
    public void readBody(InputStream in) throws IOException {
        data = new byte[this.header.getPacketLength()];
        Util.readBytesUntilFull(in, data);
    }

    /**
     * Method is public for content (audio/video)
     * Write this packet body without chunking;
     * useful for dumping audio/video streams
     */
    @Override
    public void writeBody(OutputStream out) throws IOException {
        out.write(data, 0, length);
    }
}
