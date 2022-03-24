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

    private static final int KEY_LEN = 32;
    private static final int IV_LEN = 16;

    public static String decrypt(String ciphertext, String passphrase) {
        try {
            byte[] ctBytes = Base64.decodeBase64(ciphertext.getBytes("UTF-8"));

            byte[] saltBytes = Arrays.copyOfRange(ctBytes, 8, 16);

            byte[] ciphertextBytes = Arrays.copyOfRange(ctBytes, 16, ctBytes.length);

            byte[] passBytes = passphrase.getBytes("UTF-8");

            Cipher cipher2 = getCipher(passBytes, saltBytes);
            return new String(cipher2.doFinal(ciphertextBytes));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Cipher getCipher(byte[] passBytes, byte[] saltBytes) throws Exception {
        byte[] key = new byte[KEY_LEN];
        byte[] iv = new byte[IV_LEN];

        byte[] derivedBytes = new byte[48];
        int numberOfDerivedWords = 0;
        byte[] block = null;
        MessageDigest hasher = MessageDigest.getInstance("MD5");
        while (numberOfDerivedWords < 12) {
            if (block != null) {
                hasher.update(block);
            }
            hasher.update(passBytes);
            // Salting
            block = hasher.digest(saltBytes);
            hasher.reset();
            System.arraycopy(block, 0, derivedBytes, numberOfDerivedWords * 4, Math.min(block.length, (12 - numberOfDerivedWords) * 4));
            numberOfDerivedWords += block.length / 4;
        }
        System.arraycopy(derivedBytes, 0, key, 0, KEY_LEN);
        System.arraycopy(derivedBytes, KEY_LEN, iv, 0, IV_LEN);


        // 복호화
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

}
