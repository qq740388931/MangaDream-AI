package com.example.imagetool.utilitys;


import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

public class jiekou1 {


    public static String getRealImageFormat(String LOCAL_IMAGE_PATH) {
        try {
            // 核心步骤1：读取图片二进制数据（复用，避免重复读取文件）
            byte[] imageBytes = readImageToBytes(LOCAL_IMAGE_PATH);
            // 核心步骤2：自动识别图片真实格式
            String realImageFormat = getImageFormatByBytes(imageBytes);
            System.out.println("===== 图片格式识别结果 =====");
            System.out.println("图片路径：" + LOCAL_IMAGE_PATH);
            System.out.println("自动识别的真实格式：" + realImageFormat);
            // 核心步骤3：生成带标准前缀的完整Base64字符串
            String imageBase64WithPrefix = generateDataUrl(imageBytes, realImageFormat);

            // 打印结果验证
            System.out.println("\n===== Base64 转换结果 =====");
            System.out.println("完整Base64字符串（前100位）：\n" + imageBase64WithPrefix.substring(0, 100) + "...");
            System.out.println("\n转换成功！最终前缀：" + getStandardPrefixByFormat(realImageFormat));
            return imageBase64WithPrefix;

        } catch (Exception e) {
            System.err.println("处理失败：" + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 核心：通过图片二进制头信息自动识别真实格式（不是靠文件名后缀）
     * JPG: 0xFFD8FF  | WEBP: RIFF + WEBP | PNG: 89504E47
     */
    public static String getImageFormatByBytes(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 4) {
            throw new IllegalArgumentException("图片数据为空或不完整，无法识别格式");
        }

        // 1. 识别JPG/JPEG（文件头：0xFF 0xD8 0xFF）
        if (imageBytes[0] == (byte) 0xFF && imageBytes[1] == (byte) 0xD8 && imageBytes[2] == (byte) 0xFF) {
            return "jpeg"; // 统一返回jpeg，对应标准前缀image/jpeg
        }

        // 2. 识别PNG（文件头：89 50 4E 47）
        if (imageBytes[0] == (byte) 0x89 && imageBytes[1] == (byte) 0x50 &&
                imageBytes[2] == (byte) 0x4E && imageBytes[3] == (byte) 0x47) {
            return "png";
        }

        // 3. 识别WEBP（文件头：52 49 46 46 xx xx xx xx 57 45 42 50）
        if (imageBytes.length >= 12 &&
                imageBytes[0] == (byte) 0x52 && imageBytes[1] == (byte) 0x49 &&
                imageBytes[2] == (byte) 0x46 && imageBytes[3] == (byte) 0x46 &&
                imageBytes[8] == (byte) 0x57 && imageBytes[9] == (byte) 0x45 &&
                imageBytes[10] == (byte) 0x42 && imageBytes[11] == (byte) 0x50) {
            return "webp";
        }

        // 无法识别时抛出异常，避免默认值导致错误
        throw new UnsupportedOperationException("不支持的图片格式！仅支持JPG/PNG/WEBP");
    }

    /**
     * 获取标准的Data URL前缀
     */
    private static String getStandardPrefixByFormat(String format) {
        String lowerFormat = (format == null) ? "" : format.trim().toLowerCase();
        if ("jpg".equals(lowerFormat) || "jpeg".equals(lowerFormat)) {
            return "data:image/jpeg;base64,";
        } else if ("webp".equals(lowerFormat)) {
            return "data:image/webp;base64,";
        } else if ("png".equals(lowerFormat)) {
            return "data:image/png;base64,";
        } else {
            throw new IllegalArgumentException("不支持的格式：" + format);
        }
    }

    /**
     * 生成完整的Data URL（纯Base64 + 标准前缀）
     */
    private static String generateDataUrl(byte[] imageBytes, String format) {
        // 1. 转纯Base64
        String pureBase64 = Base64.getEncoder().encodeToString(imageBytes);
        // 2. 拼接标准前缀
        String prefix = getStandardPrefixByFormat(format);
        return prefix + pureBase64;
    }

    /**
     * 读取图片文件为二进制数组（Java 8兼容）
     */
    public static byte[] readImageToBytes(String localPath) throws IOException {
        try (InputStream inputStream = new FileInputStream(localPath);
             java.io.ByteArrayOutputStream byteOut = new java.io.ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteOut.write(buffer, 0, bytesRead);
            }
            return byteOut.toByteArray();
        }
    }

    /**
     * 兼容旧方法：本地图片转纯Base64（无前缀）
     */
    public static String localImageToBase64(String localPath) throws IOException {
        byte[] imageBytes = readImageToBytes(localPath);
        return Base64.getEncoder().encodeToString(imageBytes);
    }
}