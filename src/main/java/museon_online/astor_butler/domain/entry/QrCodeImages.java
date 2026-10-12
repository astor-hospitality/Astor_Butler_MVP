package museon_online.astor_butler.domain.entry;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** A QR code as a PNG, rendered here with ZXing: no external QR service ever sees the link. */
public final class QrCodeImages {

    public static final int DEFAULT_SIZE = 512;

    private QrCodeImages() {
    }

    public static byte[] png(String text) {
        return png(text, DEFAULT_SIZE);
    }

    public static byte[] png(String text, int sizePx) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("QR code needs a text");
        }
        int size = Math.max(128, sizePx);
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.MARGIN, 2
            );
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("QR code was not rendered: " + e.getMessage(), e);
        }
    }
}
