package com.github.comelf;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class AESTest {

    /**
     * CryptoJS 의 ASE 를 통한 암호화 처리 복호화 테스트
     * <p>
     * https://cdnjs.cloudflare.com/ajax/libs/crypto-js/3.1.2/rollups/aes.js
     * var ciphertext = CryptoJS.AES.encrypt('ABCDEFGAI!@#$$^ksdjfkㄴ아리ㅓㅇㄹ', 'test_password').toString();
     */

    @Test
    public void aesDecryptTest() {
        String origin = "ABCDEFGAI!@#$$^ksdjfkㄴ아리ㅓㅇㄹ";

        String ciphertext = "U2FsdGVkX19IF6NZCpAo+l8G5uK/97wsX90r2YtFQ/xK62jzQ5+PQ9qun98jyoqKJYP843xl2M1Dj6zaigDFaQ==";
        String password = "test_password";

        String decrypted = AESUtil.decrypt(ciphertext, password);
        System.out.println(decrypted);

        assertThat(decrypted, is(origin));
    }

}
