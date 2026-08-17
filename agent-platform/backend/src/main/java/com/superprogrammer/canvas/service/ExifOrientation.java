package com.superprogrammer.canvas.service;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * JPEG EXIF 方向读取与归正（2x 四轮 S6 transform-image 前置）。
 *
 * <p>ImageIO.read 不应用 EXIF Orientation——手机竖拍照片若不归正，翻转/旋转结果与用户所见相反。
 * 本类提供零依赖最小解析：JPEG SOI → APP1(Exif) → TIFF 头 → IFD0 tag 0x0112；
 * 解析失败/PNG（无 EXIF）一律返回 1（正常方向，无需变换）。
 */
final class ExifOrientation {

    private ExifOrientation() {
    }

    /**
     * 读 JPEG EXIF Orientation（1-8）。非 JPEG / 无 EXIF / 任何解析异常 → 1。
     * 只做头几百字节顺序读，不整文件入内存。
     */
    static int readOrientation(Path src) {
        if (src == null) return 1;
        try (InputStream in = new BufferedInputStream(java.nio.file.Files.newInputStream(src), 64 * 1024)) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 != 0xFF || b1 != 0xD8) return 1; // 非 JPEG
            while (true) {
                int marker = in.read();
                if (marker != 0xFF) {
                    if (marker < 0) return 1;
                    continue; // 填充字节
                }
                int code = in.read();
                if (code < 0 || code == 0x01 || (code >= 0xD0 && code <= 0xD9)) continue; // 无长度段
                int lenHi = in.read();
                int lenLo = in.read();
                if (lenHi < 0 || lenLo < 0) return 1;
                int segLen = (lenHi << 8) | lenLo; // 含自身 2 字节
                if (segLen < 2) return 1;
                if (code == 0xE1) { // APP1
                    byte[] head = new byte[6];
                    if (readFully(in, head) < 6) return 1;
                    if (head[0] == 'E' && head[1] == 'x' && head[2] == 'i' && head[3] == 'f' && head[4] == 0 && head[5] == 0) {
                        int orient = parseTiffOrientation(in);
                        return orient > 0 ? orient : 1;
                    }
                }
                // 跳过本段剩余（已读 2 长度字节 + 可能的 6 字节 Exif 头）
                long skip = segLen - 2;
                if (code == 0xE1) skip -= 6;
                while (skip > 0) {
                    long n = in.skip(skip);
                    if (n <= 0) {
                        if (in.read() < 0) return 1;
                        n = 1;
                    }
                    skip -= n;
                }
            }
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    /** TIFF 头（字节序 + IFD0 偏移）→ 遍历 IFD0 目录项找 tag 0x0112。失败 → -1。 */
    private static int parseTiffOrientation(InputStream in) throws IOException {
        int ii = in.read();
        int mm = in.read();
        boolean littleEndian;
        if (ii == 'I' && mm == 'I') littleEndian = true;
        else if (ii == 'M' && mm == 'M') littleEndian = false;
        else return -1;
        // 魔数 42（2 字节）+ IFD0 偏移（4 字节，相对 TIFF 头起点）
        int magic = readU16(in, littleEndian);
        if (magic != 42) return -1;
        long ifdOffset = readU32(in, littleEndian);
        // TIFF 头 8 字节已读；IFD0 偏移相对 TIFF 头——当前流位置即 TIFF 头后第 8 字节
        long toSkip = ifdOffset - 8;
        while (toSkip > 0) {
            long n = in.skip(toSkip);
            if (n <= 0) {
                if (in.read() < 0) return -1;
                n = 1;
            }
            toSkip -= n;
        }
        int count = readU16(in, littleEndian);
        if (count <= 0 || count > 512) return -1; // 目录项数护栏（防炸弹头）
        for (int i = 0; i < count; i++) {
            // 目录项 12 字节：tag(2) type(2) count(4) value/offset(4)。SHORT 值内联在值域前 2 字节。
            int tag = readU16(in, littleEndian);
            int type = readU16(in, littleEndian);
            readU32(in, littleEndian); // 字段数（SHORT 型=1）
            int v0 = in.read();
            int v1 = in.read();
            if (in.read() < 0 || in.read() < 0 || v0 < 0 || v1 < 0) return -1;
            int val = littleEndian ? (v1 << 8 | v0) : (v0 << 8 | v1);
            if (tag == 0x0112 && type == 3) {
                return (val >= 1 && val <= 8) ? val : 1;
            }
        }
        return -1;
    }

    private static int readU16(InputStream in, boolean le) throws IOException {
        int a = in.read();
        int b = in.read();
        if (a < 0 || b < 0) throw new IOException("EOF");
        return le ? (b << 8 | a) : (a << 8 | b);
    }

    private static long readU32(InputStream in, boolean le) throws IOException {
        int a = readU16(in, le);
        int b = readU16(in, le);
        return le ? ((long) b << 16 | a) & 0xFFFFFFFFL : ((long) a << 16 | b) & 0xFFFFFFFFL;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    /**
     * 按 EXIF Orientation 1-8 归正为「所见即所得」朝向（1/2 横竖不变，5-8 旋转 90°交换宽高）。
     * orientation=1 或超界 → 原图直接返回。
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        int ow = src.getWidth();
        int oh = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage dst = swap
                ? new BufferedImage(oh, ow, BufferedImage.TYPE_INT_ARGB)
                : new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int rgb = src.getRGB(x, y);
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = ow - 1 - x; dy = y; }                        // 左右镜像
                    case 3 -> { dx = ow - 1 - x; dy = oh - 1 - y; }               // 180°
                    case 4 -> { dx = x; dy = oh - 1 - y; }                        // 上下镜像
                    case 5 -> { dx = y; dy = x; }                                 // 转置（镜像+90）
                    case 6 -> { dx = oh - 1 - y; dy = x; }                        // 顺 90°
                    case 7 -> { dx = oh - 1 - y; dy = ow - 1 - x; }               // 反转置
                    case 8 -> { dx = y; dy = ow - 1 - x; }                        // 逆 90°
                    default -> { dx = x; dy = y; }                                // 1：原样
                }
                dst.setRGB(dx, dy, rgb);
            }
        }
        return dst;
    }
}
