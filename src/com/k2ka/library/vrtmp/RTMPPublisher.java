package com.k2ka.library.vrtmp;

import java.util.concurrent.ExecutorService;

/**
 * Created by k2ka on 7/24/15.
 */
public interface RTMPPublisher {

    public enum Status {
        NEW,
        REPAIRING,
        READY,
        FAIL
    }
    public enum ERROR {
        SUCCESS,
        ILLEGAL_STATE,
        URL_INCORRECT,
        CONNECT_SERVER_FAIL,
        HANDSHAKE_FAIL,
        SET_CHUNK_SIZE_FAIL,
        CONNECT_CMD_FAIL,
        RELEASE_CMD_FAIL,
        FCPUBLISH_CMD_FAIL,
        PUBLISH_CMD_FAIL,
        CREATE_STREAM_CMD_FAIL,
        RECEIVE_RTMP_FAIL,
        THREAD_INTERRUPT,
        SEND_META_DATA_FAIL,
        SEND_AUDIO_HEADER_FAIL,
        SEND_VIDEO_HEADER_FAIL,
        SEND_DATA_FAIL,
    }

    public enum MediaType {
        H264_INTER,
        H264_KEY,
        AACADTS,
        AACLATM,
    }

    void Init(String rtmpUrl, ExecutorService taskHandler, RTMPPublisherListener listener, String streamName);
    void Release();
    void SetupMetaData(int height, int weight, int videoCodecID, int videoDataRate, int frameRate, int audioCodecID, int audioDataRate, int sampleRate, int channelCount, int audioConfig, byte[] SPS, byte[] PPS);
    void SendData(MediaType type, byte[] data, int length, long timestamp);
    void SendFLVTag(int type, byte[] data, int length, long timestamp);
}
