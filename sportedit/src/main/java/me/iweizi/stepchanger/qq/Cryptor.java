package me.iweizi.stepchanger.qq;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Created by iweiz on 2017/9/7.
 * 安卓手机客户端加解密算法
 */

public class Cryptor {

    private static final long UINT32_MASK = 0xffffffffL;
    private final long[] mKey;
    private final Random mRandom;
    private byte[] mOutput;
    private byte[] mInBlock;
    private int mIndexPos;
    private byte[] mIV;
    private int mOutPos;
    private int mPreOutPos;
    private boolean isFirstBlock;
    private boolean isRand;

    public Cryptor(byte[] key) {
        mKey = new long[4];
        for (int i = 0; i < 4; i++) {
            mKey[i] = pack(key, i * 4, 4);
        }
        isRand = true;
        mRandom = new Random();
        isFirstBlock = true;
    }

    private static long pack(byte[] bytes, int offset, int len) {
        long result = 0;
        int max_offset = len > 8 ? offset + 8 : offset + len;
        for (int index = offset; index < max_offset; index++) {
            result = result << 8 | ((long) bytes[index] & 0xffL);
        }
        return result >> 32 | result & UINT32_MASK;
    }

    private int rand() {
        return isRand ? mRandom.nextInt() : 0xff00ff;
    }

    public void enableRandom(boolean rand) {
        isRand = rand;
    }

    private byte[] encode(byte[] bytes) {
        long v0 = pack(bytes, 0, 4);
        long v1 = pack(bytes, 4, 4);
        long sum = 0;
        long delta = 0x9e3779b9L;
        for (int i = 0; i < 16; i++) {
            sum = (sum + delta) & UINT32_MASK;
            v0 += ((v1 << 4) + mKey[0]) ^ (v1 + sum) ^ ((v1 >>> 5) + mKey[1]);
            v0 &= UINT32_MASK;
            v1 += ((v0 << 4) + mKey[2]) ^ (v0 + sum) ^ ((v0 >>> 5) + mKey[3]);
            v1 &= UINT32_MASK;
        }
        return ByteBuffer.allocate(8).putInt((int) v0).putInt((int) v1).array();
    }

    private byte[] decode(byte[] bytes, int offset) {
        long v0 = pack(bytes, offset, 4);
        long v1 = pack(bytes, offset + 4, 4);
        long delta = 0x9e3779b9L;
        long sum = (delta << 4) & UINT32_MASK;
        for (int i = 0; i < 16; i++) {
            v1 -= ((v0 << 4) + mKey[2]) ^ (v0 + sum) ^ ((v0 >>> 5) + mKey[3]);
            v1 &= UINT32_MASK;
            v0 -= ((v1 << 4) + mKey[0]) ^ (v1 + sum) ^ ((v1 >>> 5) + mKey[1]);
            v0 &= UINT32_MASK;
            sum = (sum - delta) & UINT32_MASK;
        }
        return ByteBuffer.allocate(8).putInt((int) v0).putInt((int) v1).array();
    }

    private void encodeOneBlock() {
        for (mIndexPos = 0; mIndexPos < 8; mIndexPos++) {
            mInBlock[mIndexPos] = isFirstBlock ?
                    mInBlock[mIndexPos]
                    : ((byte) (mInBlock[mIndexPos] ^ mOutput[mPreOutPos + mIndexPos]));
        }

        System.arraycopy(encode(mInBlock), 0, mOutput, mOutPos, 8);
        for (mIndexPos = 0; mIndexPos < 8; mIndexPos++) {
            int out_pos = mOutPos + mIndexPos;
            mOutput[out_pos] = (byte) (mOutput[out_pos] ^ mIV[mIndexPos]);
        }
        System.arraycopy(mInBlock, 0, mIV, 0, 8);
        mPreOutPos = mOutPos;
        mOutPos += 8;
        mIndexPos = 0;
        isFirstBlock = false;
    }

    private boolean decodeOneBlock(byte[] ciphertext, int offset, int len) {
        for (mIndexPos = 0; mIndexPos < 8; mIndexPos++) {
            if (mOutPos + mIndexPos < len) {
                mIV[mIndexPos] = (byte) (mIV[mIndexPos] ^ ciphertext[mOutPos + offset + mIndexPos]);
                continue;
            }
            return true;
        }

        mIV = decode(mIV, 0);
        mOutPos += 8;
        mIndexPos = 0;
        return true;

    }

    private byte[] encrypt(byte[] plaintext, int offset, int len) {
        mInBlock = new byte[8];
        mIV = new byte[8];
        mOutPos = 0;
        mPreOutPos = 0;
        isFirstBlock = true;
        mIndexPos = (len + 10) % 8;
        if (mIndexPos != 0) {
            mIndexPos = 8 - mIndexPos;
        }
        mOutput = new byte[mIndexPos + len + 10];
        mInBlock[0] = (byte) (rand() & 0xf8 | mIndexPos);
        for (int i = 1; i <= mIndexPos; i++) {
            mInBlock[i] = (byte) (rand() & 0xff);
        }
        ++mIndexPos;
        for (int i = 0; i < 8; i++) {
            mIV[i] = 0;
        }

        int g = 0;
        while (g < 2) {
            if (mIndexPos < 8) {
                mInBlock[mIndexPos++] = (byte) (rand() & 0xff);
                ++g;
            }
            if (mIndexPos == 8) {
                encodeOneBlock();
            }
        }

        for (; len > 0; len--) {
            if (mIndexPos < 8) {
                mInBlock[mIndexPos++] = plaintext[offset++];
            }
            if (mIndexPos == 8) {
                encodeOneBlock();
            }
        }
        for (g = 0; g < 7; g++) {
            if (mIndexPos < 8) {
                mInBlock[mIndexPos++] = (byte) 0;
            }
            if (mIndexPos == 8) {
                encodeOneBlock();
            }
        }
        return mOutput;
    }

    private byte[] decrypt(byte[] ciphertext, int offset, int len) {
        if (len % 8 != 0 || len < 16) {
            return null;
        }
        mIV = decode(ciphertext, offset);
        mIndexPos = mIV[0] & 7;
        int plen = len - mIndexPos - 10;
        isFirstBlock = true;
        if (plen < 0) {
            return null;
        }
        mOutput = new byte[plen];
        mPreOutPos = 0;
        mOutPos = 8;
        ++mIndexPos;
        int g = 0;
        while (g < 2) {
            if (mIndexPos < 8) {
                ++mIndexPos;
                ++g;
            }
            if (mIndexPos == 8) {
                isFirstBlock = false;
                if (!decodeOneBlock(ciphertext, offset, len)) {
                    return null;
                }
            }
        }

        for (int outpos = 0; plen != 0; plen--) {
            if (mIndexPos < 8) {
                mOutput[outpos++] = isFirstBlock ?
                        mIV[mIndexPos] :
                        (byte) (ciphertext[mPreOutPos + offset + mIndexPos] ^ mIV[mIndexPos]);
                ++mIndexPos;
            }
            if (mIndexPos == 8) {
                mPreOutPos = mOutPos - 8;
                isFirstBlock = false;
                if (!decodeOneBlock(ciphertext, offset, len)) {
                    return null;
                }
            }
        }
        for (g = 0; g < 7; g++) {
            if (mIndexPos < 8) {
                if ((ciphertext[mPreOutPos + offset + mIndexPos] ^ mIV[mIndexPos]) != 0) {
                    return null;
                } else {
                    ++mIndexPos;
                }
            }

            if (mIndexPos == 8) {
                mPreOutPos = mOutPos;
                if (!decodeOneBlock(ciphertext, offset, len)) {
                    return null;
                }
            }
        }
        return mOutput;
    }

    public byte[] encrypt(byte[] plaintext) {
        return encrypt(plaintext, 0, plaintext.length);
    }

    public byte[] decrypt(byte[] ciphertext) {
        return decrypt(ciphertext, 0, ciphertext.length);
    }
}
