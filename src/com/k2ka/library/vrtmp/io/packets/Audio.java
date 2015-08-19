package com.k2ka.library.vrtmp.io.packets;

import com.k2ka.library.vrtmp.io.ChunkStreamInfo;

/**
 * Audio data packet
 *  
 * @author k2ka
 */
public class Audio extends ContentData {

    public Audio() {
        super(new RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_STREAM_CHANNEL, RtmpHeader.MessageType.AUDIO));
    }
}
