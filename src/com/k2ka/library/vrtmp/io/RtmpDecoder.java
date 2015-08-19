package com.k2ka.library.vrtmp.io;

import com.k2ka.library.vrtmp.io.packets.Abort;
import com.k2ka.library.vrtmp.io.packets.Audio;
import com.k2ka.library.vrtmp.io.packets.Command;
import com.k2ka.library.vrtmp.io.packets.Data;
import com.k2ka.library.vrtmp.io.packets.RtmpHeader;
import com.k2ka.library.vrtmp.io.packets.RtmpPacket;
import com.k2ka.library.vrtmp.io.packets.SetChunkSize;
import com.k2ka.library.vrtmp.io.packets.SetPeerBandwidth;
import com.k2ka.library.vrtmp.io.packets.UserControl;
import com.k2ka.library.vrtmp.io.packets.Video;
import com.k2ka.library.vrtmp.io.packets.WindowAckSize;
import java.io.IOException;
import java.io.InputStream;

/**
 *
 * @author k2ka
 */
public class RtmpDecoder {
    private static final String TAG = "RtmpDecoder";
    private RtmpSessionInfo rtmpSessionInfo;

    public RtmpDecoder(RtmpSessionInfo rtmpSessionInfo) {
        this.rtmpSessionInfo = rtmpSessionInfo;
    }

    public RtmpPacket readPacket(InputStream in) throws IOException {

        RtmpHeader header = RtmpHeader.readHeader(in, rtmpSessionInfo);
        RtmpPacket rtmpPacket;

        ChunkStreamInfo chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(header.getChunkStreamId());

        chunkStreamInfo.setPrevHeaderRx(header);

        if (header.getPacketLength() > rtmpSessionInfo.getChunkSize()) {
            // This packet consists of more than one chunk; store the chunks in the chunk stream until everything is read
            if (!chunkStreamInfo.storePacketChunk(in, rtmpSessionInfo.getChunkSize())) {
                return null; // packet is not yet complete
            } else {
                in = chunkStreamInfo.getStoredPacketInputStream();
            }
        }
        switch (header.getMessageType()) {

            case SET_CHUNK_SIZE: {
                SetChunkSize setChunkSize = new SetChunkSize(header);
                setChunkSize.readBody(in);
                rtmpSessionInfo.setChunkSize(setChunkSize.getChunkSize());                
                return null;
            }
            case ABORT:
                rtmpPacket = new Abort(header);
                break;
            case USER_CONTROL_MESSAGE:
                rtmpPacket = new UserControl(header);
                break;
            case WINDOW_ACKNOWLEDGEMENT_SIZE:
                rtmpPacket = new WindowAckSize(header);
                break;
            case SET_PEER_BANDWIDTH:
                rtmpPacket = new SetPeerBandwidth(header);
                break;
            case AUDIO:
                rtmpPacket = new Audio();
                break;
            case VIDEO:
                rtmpPacket = new Video();
            case COMMAND_AMF0:
                rtmpPacket = new Command(header);
                break;
            case DATA_AMF0:
                rtmpPacket = new Data(header);
                break;
            default:
                throw new IOException("No packet body implementation for message type: " + header.getMessageType());
        }                
        rtmpPacket.readBody(in);                        
        return rtmpPacket;
    }
}
