package com.k2ka.library.vrtmp;

/**
 * Created by k2ka on 7/24/15.
 */
public interface RTMPPublisherListener {
    void onInitComplete();
    void onError(RTMPPublisher.ERROR error, String msg);
}
