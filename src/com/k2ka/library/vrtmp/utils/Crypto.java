package com.k2ka.library.vrtmp.utils;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Some helper utilities for SHA256, mostly (used during handshake)
 * This is separated in order to be more easily replaced on platforms that
 * do not have the javax.crypto.* and/or java.security.* packages
 * 
 * This implementation is directly inspired by the RTMPHandshake class of the
 * Red5  Open Source Flash Server project
 * 
 * @author k2ka
 */
public class Crypto {
    private final static String TAG = "Crypto";
    private Mac hmacSHA256;

    public Crypto() throws SecurityException, NoSuchAlgorithmException{
        hmacSHA256 = Mac.getInstance("HmacSHA256");
    }

    /**
     * Calculates an HMAC SHA256 hash using a default key length.
     *
     *
     * @param input
     * @param key
     * @return hmac hashed bytes
     */
    public byte[] calculateHmacSHA256(byte[] input, byte[] key) throws InvalidKeyException{
        byte[] output = null;
        hmacSHA256.init(new SecretKeySpec(key, "HmacSHA256"));
        output = hmacSHA256.doFinal(input);
        return output;
    }

    /**
     * Calculates an HMAC SHA256 hash using a set key length.
     *
     * @param input
     * @param key
     * @param length
     * @return hmac hashed bytes
     */
    public byte[] calculateHmacSHA256(byte[] input, byte[] key, int length) throws InvalidKeyException{
        byte[] output = null;
        hmacSHA256.init(new SecretKeySpec(key, 0, length, "HmacSHA256"));
        output = hmacSHA256.doFinal(input);
        return output;
    }
}
