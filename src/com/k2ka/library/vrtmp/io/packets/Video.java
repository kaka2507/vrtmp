package com.k2ka.library.vrtmp.io.packets;

import com.k2ka.library.vrtmp.io.ChunkStreamInfo;

/**
 * Video data packet
 *  
 * @author k2ka
 */
public class Video extends ContentData {

    public Video() {
        super(new RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_STREAM_CHANNEL, RtmpHeader.MessageType.VIDEO));
    }
}
