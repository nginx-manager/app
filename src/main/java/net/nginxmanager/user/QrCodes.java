package net.nginxmanager.user;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Map;

/** QR 코드를 SVG로 그린다. 외부 요청 없이 화면에 바로 넣는다. */
final class QrCodes {

    private QrCodes() {
    }

    static String svg(String text) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalArgumentException(e);
        }
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"qr\" viewBox=\"0 0 " + width + " " + height
                + "\" shape-rendering=\"crispEdges\" role=\"img\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }
}
