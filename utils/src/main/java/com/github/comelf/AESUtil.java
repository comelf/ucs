package com.github.comelf;

import org.apache.commons.codec.binary.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Arrays;

public class AESUtil {

    private static final int IV_LENGTH = 16;
    public static final int AES256_BIT = 32;

    private final int keyLength;
    private final Key keySpec;
    private final IvParameterSpec iv;

    public AESUtil(String key) {
        this(key, AES256_BIT);
    }

    public AESUtil(String key, int length) {
        this(Objects.requireNonNull(key).getBytes(StandardCharsets.UTF_8), length);
    }

    public AESUtil(byte[] key, int length) {
        this(key, length, null);
    }

    public AESUtil(byte[] key, int length, byte[] iv) {
        Objects.requireNonNull(key);
        this.keyLength = length;
        this.keySpec = makeKey(key);
        if (iv != null) {
            this.iv = makeIv(iv);
        } else {
            this.iv = makeIv(key);
        }
    }

    private IvParameterSpec makeIv(byte[] key) {
        byte[] iv = new byte[IV_LENGTH];
        int keyLen = key.length;
        if (keyLen < IV_LENGTH) {
            int r = (int) Math.ceil(IV_LENGTH / (double) keyLen);
            byte[] temp = new byte[keyLen * r];
            for (int i = 0; i < r; i++) {
                System.arraycopy(key, 0, temp, i * keyLen, keyLen);
            }
            key = temp;
        }
        if (key.length < IV_LENGTH) {
            throw new IllegalArgumentException("Invalid iv length");
        }
        System.arraycopy(key, 0, iv, 0, IV_LENGTH);
        return new IvParameterSpec(iv);
    }

    private Key makeKey(byte[] key) {
        byte[] pass = new byte[keyLength];
        int keyLen = key.length;
        if (keyLen == 0) {
            throw new IllegalArgumentException("iv key must be not empty.");
        }
        if (keyLen < this.keyLength) {
            int r = (int) Math.ceil(keyLength / (double) keyLen);
            byte[] temp = new byte[keyLen * r];
            for (int i = 0; i < r; i++) {
                System.arraycopy(key, 0, temp, i * keyLen, keyLen);
            }
            key = temp;
        }
        if (key.length < this.keyLength) {
            throw new IllegalArgumentException("Invalid key length");
        }
        System.arraycopy(key, 0, pass, 0, keyLength);
        return new SecretKeySpec(pass, "AES");
    }

    public String encrypt(String str) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, keySpec, iv);
        byte[] encrypted = c.doFinal(str.getBytes(StandardCharsets.UTF_8));
        return new String(Base64.encodeBase64(encrypted));
    }

    public String decrypt(String str) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, keySpec, iv);
        byte[] byteStr = Base64.decodeBase64(str.getBytes());
        return new String(c.doFinal(byteStr), StandardCharsets.UTF_8);
    }

}
