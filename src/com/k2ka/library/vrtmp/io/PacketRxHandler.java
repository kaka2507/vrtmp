package com.k2ka.library.vrtmp.io;

import com.k2ka.library.vrtmp.io.packets.RtmpPacket;

/**
 * Handler interface for received RTMP packets
 * @author k2ka
 */
public interface PacketRxHandler {
    
    public void handleRxPacket(RtmpPacket rtmpPacket);
    
    public void notifyWindowAckRequired(final int numBytesReadThusFar);    
}
