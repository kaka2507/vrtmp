package com.k2ka.library.vrtmp.io;

import com.k2ka.library.vrtmp.RTMPPublisher;
import com.k2ka.library.vrtmp.RTMPPublisherListener;
import com.k2ka.library.vrtmp.amf.*;
import com.k2ka.library.vrtmp.io.packets.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by k2ka on 7/24/15.
 */
public class RTMPConnection implements RTMPPublisher {
    private static final String TAG = "RTMPConnection";
    private static final Pattern RTMP_URL_PATTERN = Pattern.compile("^rtmp://([^/:]+)(:(\\d+))*/([^/:]+)$");
    private static final int TCP_TIMEOUT_MS = 2000;
    private static final int DEFAULT_CHUNK_SIZE = 4096;
    private static final int MAIN_LOOP_SLEEP_WHEN_PUBLISHING = 10000;

    private Status _status;
    private String _rtmpUrl;
    private String _host;
    private int _port;
    private String _app;
    private ExecutorService _taskWorkers;
    private RTMPPublisherListener _listener;
    private Socket _socket;
    private BufferedInputStream _inputStream;
    private BufferedOutputStream _outputStream;
    private RtmpSessionInfo _rtmpSessionInfo;
    private int _transactionIDCounter;
    private int _currentStreamMsgID;
    private RtmpDecoder _rtmpDecoder;
    private int _streamID;
    private String _channelName;
    private int _mainLoopNumSleep;

    public RTMPConnection() {
        _status = Status.NEW;
    }

    @Override
    public void Init(String rtmpUrl, ExecutorService taskHandler, RTMPPublisherListener listener, String streamName) {
        _listener = listener;
        if (_status != Status.NEW && _status != Status.FAIL) {
            _listener.onError(ERROR.ILLEGAL_STATE, "");
            return;
        }
        _status = Status.REPAIRING;
        _rtmpUrl = rtmpUrl;
        _taskWorkers = taskHandler;
        _channelName = streamName;

        //parse rtmp url to get app name
        try {
            Matcher matcher = RTMP_URL_PATTERN.matcher(_rtmpUrl);
            matcher.matches();
            _host = matcher.group(1);
            _app = matcher.group(4);
            String portStr = matcher.group(3);
            _port = portStr != null ? Integer.parseInt(portStr) : 1935;
        } catch (Exception e) {
            e.printStackTrace();
            _status = Status.FAIL;
            _listener.onError(ERROR.URL_INCORRECT, e.toString());
            return;
        }

        // begin make connection to rtmp server and handshaking
        taskHandler.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    _socket = new Socket();
                    SocketAddress socketAddress = new InetSocketAddress(_host, _port);
                    _socket.connect(socketAddress, TCP_TIMEOUT_MS);
                    _inputStream = new BufferedInputStream(_socket.getInputStream());
                    _outputStream = new BufferedOutputStream(_socket.getOutputStream());
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.CONNECT_SERVER_FAIL, e.toString());
                    return;
                }
                // handshaking
                try {
                    handshake();
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.HANDSHAKE_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo = new RtmpSessionInfo();
                _transactionIDCounter = 0;
                _currentStreamMsgID = 0;
                _rtmpDecoder = new RtmpDecoder(_rtmpSessionInfo);

                try {
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.CONTROL_CHANNEL);
                    SetChunkSize setChunkSize = new SetChunkSize(DEFAULT_CHUNK_SIZE);
                    setChunkSize.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (IOException e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.SET_CHUNK_SIZE_FAIL, e.toString());
                    return;
                }

                _mainLoopNumSleep = 20;
                Thread mainLoop = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (_status != Status.NEW && _status != Status.FAIL) {
                            MainHandlerLoopFunction();
                            try {
                                Thread.sleep(_mainLoopNumSleep);
                            } catch (InterruptedException e) {
                                _status = Status.FAIL;
                                _listener.onError(ERROR.THREAD_INTERRUPT, e.toString());
                            }
                        }
                    }
                });
                mainLoop.start();
                Connect();
            }
        });
    }

    private void Connect() {
        _taskWorkers.submit(new Runnable() {
            @Override
            public void run() {
                Command connectCmd = new Command("connect", ++_transactionIDCounter, _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
                connectCmd.getHeader().setMessageStreamId(_currentStreamMsgID++);

                AmfObject args = new AmfObject();
                args.setProperty("app", _app);
                args.setProperty("type", "nonprivate");
                args.setProperty("flashVer", "FMLE/3.0 (compatible; FMSc/1.0)");
                args.setProperty("swfUrl", _rtmpUrl);
                args.setProperty("tcUrl", _rtmpUrl);
                connectCmd.addData(args);
                connectCmd.getHeader().setAbsoluteTimestamp(0);

                final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.CONTROL_CHANNEL);
                try {
                    connectCmd.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.CONNECT_CMD_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo.addInvokedCommand(connectCmd.getTransactionId(), connectCmd.getCommandName());
            }
        });
    }

    private void PublishStream() {
        _taskWorkers.submit(new Runnable() {
            @Override
            public void run() {
                // release stream first
                Command releaseCmd = new Command("releaseStream", ++_transactionIDCounter, _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
                releaseCmd.getHeader().setMessageStreamId(_currentStreamMsgID++);
                releaseCmd.addData(new AmfNull());
                AmfString argsChannelName = new AmfString(_channelName, false);
                releaseCmd.addData(argsChannelName);
                releaseCmd.getHeader().setAbsoluteTimestamp(0);
                final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                try {
                    releaseCmd.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.RELEASE_CMD_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo.addInvokedCommand(releaseCmd.getTransactionId(), releaseCmd.getCommandName());

                // FCPublish stream
                Command fcPublishCmd = new Command("FCPublish", ++_transactionIDCounter, _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
                fcPublishCmd.getHeader().setMessageStreamId(_currentStreamMsgID++);
                fcPublishCmd.addData(new AmfNull());
                fcPublishCmd.addData(argsChannelName);
                fcPublishCmd.getHeader().setAbsoluteTimestamp(0);
                try {
                    fcPublishCmd.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.FCPUBLISH_CMD_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo.addInvokedCommand(fcPublishCmd.getTransactionId(), fcPublishCmd.getCommandName());

                // create Stream
                Command createStream = new Command("createStream", ++_transactionIDCounter, _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
                createStream.getHeader().setMessageStreamId(_currentStreamMsgID++);
                createStream.getHeader().setAbsoluteTimestamp(0);
                try {
                    createStream.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.CREATE_STREAM_CMD_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo.addInvokedCommand(createStream.getTransactionId(), createStream.getCommandName());
            }
        });
    }

    @Override
    public void Release() {
        _taskWorkers = null;
        _listener = null;
        _app = null;
        _host = null;
        _rtmpDecoder = null;
        _rtmpSessionInfo = null;
        _rtmpUrl = null;
        _channelName = null;
        _currentStreamMsgID = 0;
        _streamID = 0;
        _mainLoopNumSleep = 0;
        _port = 0;
        try {
            _socket.close();
            _socket = null;

            _inputStream.close();
            _inputStream = null;

            _outputStream.close();
            _outputStream = null;
        } catch (Exception e) {
            //ignore
        } finally {
            _status = Status.NEW;
        }
    }

    @Override
    public void SetupMetaData(final int height, final int weight, final int videoCodecID, final int videoDataRate,final int frameRate, final int audioCodecID, final int audioDataRate, final int sampleRate, final int channelCount, final int audioConfig, final byte[] SPSBytes, final byte[] PPSBytes) {
        _taskWorkers.submit(new Runnable() {
            @Override
            public void run() {
                // send meta information to rtmp server
                Data metaData = new Data("@setDataFrame");
                metaData.getHeader().setMessageStreamId(_streamID);
                metaData.getHeader().setChunkStreamId(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
                AmfString args1 = new AmfString("onMetaData", false);
                AmfMap args2 = new AmfMap();
                args2.setProperty("width", weight);
                args2.setProperty("height", height);
                args2.setProperty("videocodecid", "avc1");
                args2.setProperty("framerate", frameRate);
                args2.setProperty("audiocodecid", "mp4a");
                args2.setProperty("stereo", 1);
                args2.setProperty("audiosamplerate", sampleRate);
                args2.setProperty("audiosamplesize", 16);
                metaData.addData(args1);
                metaData.addData(args2);
                try {
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_STREAM_CHANNEL);
                    metaData.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.SEND_META_DATA_FAIL, e.toString());
                }

                // send audio metadata to rtmp server
                Audio audio = new Audio();
                audio.getHeader().setMessageStreamId(_streamID);
                byte[] audioHeader = new byte[4];
                audioHeader[0] = (byte) 0xaf;
                audioHeader[1] = (byte) 0x00;
                audioHeader[2] = (byte) (0x15);
                audioHeader[3] = (byte) (0x88);
                audio.setData(audioHeader, 4);
                try {
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                    audio.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.SEND_AUDIO_HEADER_FAIL, e.toString());
                }

                // send video metadata to rtmp server
                Video video = new Video();
                video.getHeader().setMessageStreamId(_streamID);
                byte[] videoHeader = new byte[11 + 2 + SPSBytes.length + 1 +  2 + PPSBytes.length];
                videoHeader[0]  = (byte) 0x17;
                videoHeader[1] = (byte) 0x00;
                videoHeader[2] = (byte) 0x00;videoHeader[3] = (byte) 0x00;videoHeader[4] = (byte) 0x00;
                videoHeader[5] = (byte) 0x01;
                videoHeader[6] = SPSBytes[1];
                videoHeader[7] = SPSBytes[2];
                videoHeader[8] = SPSBytes[3];
                videoHeader[9] = (byte) 0xff;
                videoHeader[10] = (byte) 0xe1;
                byte[] length;
                length = ByteBuffer.allocate(2).putShort((short) SPSBytes.length).array();
                System.arraycopy(length, 0, videoHeader, 11, 2);
                System.arraycopy(SPSBytes, 0, videoHeader, 13, SPSBytes.length);
                videoHeader[13 + SPSBytes.length] = (byte)0x01;
                length = ByteBuffer.allocate(2).putShort((short)PPSBytes.length).array();
                System.arraycopy(length, 0, videoHeader, 14 + SPSBytes.length, 2);
                System.arraycopy(PPSBytes, 0, videoHeader, 16 + SPSBytes.length, PPSBytes.length);

                video.setData(videoHeader, 11 + 2 + SPSBytes.length + 1 + 2 + PPSBytes.length);
                try {
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                    video.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.SEND_VIDEO_HEADER_FAIL, e.toString());
                }
            }
        });
    }

    @Override
    public void SendData(final MediaType type, final byte[] data, final int length, final long timestamp) {
        _taskWorkers.submit(new Runnable() {
            @Override
            public void run() {
                ContentData packet;
                if(type == MediaType.AACADTS || type == MediaType.AACLATM) {
                    //audio
                    packet = new Audio();
                    byte[] packetData = new byte[2 + length];
                    packetData[0] = (byte) 0xaf;
                    packetData[1] = (byte) 0x01;
                    System.arraycopy(data, 0, packetData, 2, length);
                    packet.setData(packetData, length + 2);
                } else {
                    //video
                    packet = new Video();
                    byte[] packetData= new byte[5 + length];
                    if(type == MediaType.H264_KEY)
                        packetData[0] = 0x17;
                    else
                        packetData[0] = 0x27;
                    packetData[1] = 0x01;
                    packetData[2] = 0x00;packetData[3] = 0x00;packetData[4] = 0x00;
                    System.arraycopy(data, 0, packetData, 5, length);
                    packet.setData(packetData, 5 + length);
                }
                try {
                    packet.getHeader().setMessageStreamId(_streamID);
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                    if(_rtmpSessionInfo.getMarkAbsoluteTimestamp() == 0 && timestamp != 0)
                        _rtmpSessionInfo.setMarkAbsoluteTimestamp(timestamp);
                    packet.getHeader().setAbsoluteTimestamp((int) (timestamp - _rtmpSessionInfo.getMarkAbsoluteTimestamp()));
                    packet.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.SEND_DATA_FAIL, e.toString());
                }
            }
        });
    }

    @Override
    public void SendFLVTag(int type, byte[] data, int length, long timestamp) {
        ContentData packet = null;
        if(type == 0x12) {
            // meta data
            packet = new MetaData();
        } else if(type == 0x08) {
            // audio data
            packet = new Audio();
        } else if(type == 0x09) {
            // video data
            packet = new Video();
        }
        if(packet != null) {
            packet.setData(data, length);
            packet.getHeader().setMessageStreamId(_streamID);
            try {
                final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                if(_rtmpSessionInfo.getMarkAbsoluteTimestamp() == 0 && timestamp != 0)
                    _rtmpSessionInfo.setMarkAbsoluteTimestamp(timestamp);
                packet.getHeader().setAbsoluteTimestamp((int) (timestamp - _rtmpSessionInfo.getMarkAbsoluteTimestamp()));
                packet.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
            } catch (Exception e) {
                _status = Status.FAIL;
                _listener.onError(ERROR.SEND_DATA_FAIL, e.toString());
                e.printStackTrace();
            }
        }
    }

    private void MainHandlerLoopFunction() {
        try {
            RtmpPacket packet = _rtmpDecoder.readPacket(_inputStream);
            if (packet != null) {
                switch (packet.getHeader().getMessageType()) {
                    case WINDOW_ACKNOWLEDGEMENT_SIZE: {
                        WindowAckSize windowAckSize = (WindowAckSize) packet;
                        _rtmpSessionInfo.setAcknowledgmentWindowSize(windowAckSize.getAcknowledgementWindowSize());
                        break;
                    }
                    case SET_PEER_BANDWIDTH: {
                        break;
                    }
                    case USER_CONTROL_MESSAGE: {
                        HandleUserControlMessage((UserControl) packet);
                        break;
                    }
                    case COMMAND_AMF0: {
                        HandleCommandMessage((Command) packet);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            //ignore
        }
    }

    private void HandleUserControlMessage(UserControl msg) {
        switch (msg.getType()) {
            case PING_REQUEST: {
                ChunkStreamInfo channelInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.CONTROL_CHANNEL);
                final UserControl pong = new UserControl(msg, channelInfo);
                _taskWorkers.submit(new Runnable() {
                    @Override
                    public void run() {
                        final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                        try {
                            pong.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                        } catch (Exception e) {
                            _status = Status.FAIL;
                            _listener.onError(ERROR.RECEIVE_RTMP_FAIL, e.toString());
                        }
                    }
                });
                break;
            }
        }
    }

    private void HandleCommandMessage(Command command) {
        if(command.getCommandName().equalsIgnoreCase("onFCPublish")) {
            // ignore
        } else if (command.getCommandName().equalsIgnoreCase("_result")) {
            String method = _rtmpSessionInfo.takeInvokedCommand(command.getTransactionId());
            if ("connect".contains(method)) {
                AmfObject resultObj = (AmfObject) command.getData().get(1);
                String result = ((AmfString) resultObj.getProperty("code")).getValue();
                if (result.equalsIgnoreCase("NetConnection.Connect.Success")) {
                    PublishStream();
                }
            } else if ("createStream".contains(method)) {
                _streamID = (int) ((AmfNumber) command.getData().get(1)).getValue();
                // send publish command
                Command publishCommand = new Command("publish", ++_transactionIDCounter, _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL));
                publishCommand.getHeader().setMessageStreamId(_currentStreamMsgID++);
                publishCommand.getHeader().setAbsoluteTimestamp(0);
                publishCommand.getHeader().setMessageStreamId(_streamID);
                AmfNull amfNull = new AmfNull();
                publishCommand.addData(amfNull);
                AmfString argsChannelName = new AmfString(_channelName, false);
                publishCommand.addData(argsChannelName);
                AmfString argsLive = new AmfString("live", false);
                publishCommand.addData(argsLive);

                try {
                    final ChunkStreamInfo chunkStreamInfo = _rtmpSessionInfo.getChunkStreamInfo(ChunkStreamInfo.RTMP_COMMAND_CHANNEL);
                    publishCommand.writeTo(_outputStream, DEFAULT_CHUNK_SIZE, chunkStreamInfo);
                } catch (Exception e) {
                    _status = Status.FAIL;
                    _listener.onError(ERROR.PUBLISH_CMD_FAIL, e.toString());
                    return;
                }
                _rtmpSessionInfo.addInvokedCommand(publishCommand.getTransactionId(), publishCommand.getCommandName());
            } else if ("publish".contains(method)) {
                // ignore
            } else if ("releaseStream".contains(method)) {
                // ignore
            } else if ("FCPublish".contains(method)) {
                // ignore
            } else {
                // ignore
            }
        } else if (command.getCommandName().equalsIgnoreCase("onStatus")) {
            AmfObject resultObj = (AmfObject) command.getData().get(1);
            String result = ((AmfString) resultObj.getProperty("code")).getValue();
            if (result.equalsIgnoreCase("NetStream.Publish.Start")) {
                _status = Status.READY;
                _listener.onInitComplete();
                _mainLoopNumSleep = MAIN_LOOP_SLEEP_WHEN_PUBLISHING;
            } else {
                _status = Status.FAIL;
                _listener.onError(ERROR.RECEIVE_RTMP_FAIL, "Could not publish to rtmp server with result:" + result);
            }
        }
    }

    private void handshake() throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        Handshake handshake = new Handshake();
        handshake.writeC0(_outputStream);
        handshake.writeC1(_outputStream); // Write C1 without waiting for S0
        handshake.readS0(_inputStream);
        handshake.readS1(_inputStream);
        handshake.writeC2(_outputStream);
        handshake.readS2(_inputStream);
    }
}
