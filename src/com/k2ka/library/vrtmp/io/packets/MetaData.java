package com.k2ka.library.vrtmp.io.packets;

import com.k2ka.library.vrtmp.io.ChunkStreamInfo;

/**
 * Created by k2ka on 8/18/2015.
 */
public class MetaData extends ContentData {
    public MetaData() {
        super(new RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_STREAM_CHANNEL, RtmpHeader.MessageType.DATA_AMF0));
    }
}
