package local.photoscan;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class JpegNotes {
    private static final int ASCII = 2;
    private static final int LONG = 4;
    private static final int UNDEFINED = 7;
    private static final int IFD0 = 8;
    private static final int ENTRY_BYTES = 12;
    private static final byte[] UTC_OFFSET = "+00:00\0".getBytes(StandardCharsets.US_ASCII);

    private JpegNotes() {}

    static OutputStream embed(OutputStream jpeg, String caption, String date) {
        if (caption.isEmpty() && date.isEmpty()) return jpeg;
        byte[] app1 = segment(caption, date);
        return new OutputStream() {
            private int headerBytes;

            @Override public void write(int value) throws IOException {
                if (headerBytes < 2) {
                    int expected = headerBytes == 0 ? 0xff : 0xd8;
                    if ((value & 0xff) != expected) throw new IOException("Invalid encoded JPEG.");
                    jpeg.write(value);
                    if (++headerBytes == 2) jpeg.write(app1);
                } else {
                    jpeg.write(value);
                }
            }

            @Override public void write(byte[] data, int offset, int length) throws IOException {
                int headerLength = Math.min(2 - headerBytes, length);
                for (int index = 0; index < headerLength; index++) write(data[offset + index]);
                jpeg.write(data, offset + headerLength, length - headerLength);
            }
        };
    }

    private static byte[] segment(String caption, String date) {
        boolean calendarDate = PhotoDate.isCalendarDate(date);
        String comment = caption;
        if (!date.isEmpty()) {
            comment = (caption.isEmpty() ? "" : caption + "\n") + "Date: " + date;
            if (calendarDate) comment += "\nOriginal time unknown. Noon UTC is used for sorting.";
        }
        boolean plainComment = StandardCharsets.US_ASCII.newEncoder().canEncode(comment);
        byte[] encodedComment = comment.getBytes(plainComment ? StandardCharsets.US_ASCII : StandardCharsets.UTF_16LE);
        byte[] commentPrefix = (plainComment ? "ASCII\0\0\0" : "UNICODE\0").getBytes(StandardCharsets.US_ASCII);
        int commentLength = commentPrefix.length + (plainComment ? 0 : 2) + encodedComment.length;
        byte[] description = caption.isEmpty() || !StandardCharsets.US_ASCII.newEncoder().canEncode(caption)
                ? new byte[0] : (caption + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] originalDate = calendarDate
                ? (date.replace('-', ':') + " 12:00:00\0").getBytes(StandardCharsets.US_ASCII)
                : new byte[0];
        int mainEntries = description.length == 0 ? 1 : 2;
        int exifEntries = calendarDate ? 4 : 2;
        int exifIfd = IFD0 + ifdLength(mainEntries);
        int descriptionOffset = exifIfd + ifdLength(exifEntries);
        int descriptionBytes = description.length > 4 ? (description.length + 1) & ~1 : 0;
        int dateOffset = descriptionOffset + descriptionBytes;
        int timezoneOffset = dateOffset + originalDate.length;
        int commentOffset = timezoneOffset + (calendarDate ? UTC_OFFSET.length + 1 : 0);
        ByteBuffer tiff = ByteBuffer.allocate(commentOffset + commentLength).order(ByteOrder.LITTLE_ENDIAN);
        tiff.putShort((short) 0x4949).putShort((short) 42).putInt(IFD0);
        tiff.putShort((short) mainEntries);
        if (description.length > 0) {
            if (description.length <= 4) {
                tag(tiff, 0x010e, ASCII, description.length);
                tiff.put(description).position(tiff.position() + 4 - description.length);
            } else {
                tag(tiff, 0x010e, ASCII, description.length).putInt(descriptionOffset);
            }
        }
        tag(tiff, 0x8769, LONG, 1).putInt(exifIfd);
        tiff.putInt(0);
        tiff.putShort((short) exifEntries);
        tag(tiff, 0x9000, UNDEFINED, 4).put("0232".getBytes(StandardCharsets.US_ASCII));
        if (calendarDate) {
            tag(tiff, 0x9003, ASCII, originalDate.length).putInt(dateOffset);
            tag(tiff, 0x9011, ASCII, UTC_OFFSET.length).putInt(timezoneOffset);
        }
        tag(tiff, 0x9286, UNDEFINED, commentLength).putInt(commentOffset);
        tiff.putInt(0);
        if (description.length > 4) tiff.put(description);
        tiff.position(dateOffset);
        if (calendarDate) tiff.put(originalDate).put(UTC_OFFSET);
        tiff.position(commentOffset);
        tiff.put(commentPrefix);
        if (!plainComment) tiff.putShort((short) 0xfeff);
        tiff.put(encodedComment);

        byte[] identifier = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + identifier.length + tiff.capacity())
                .putShort((short) 0xffe1).putShort((short) (2 + identifier.length + tiff.capacity()))
                .put(identifier).put(tiff.array()).array();
    }

    private static int ifdLength(int entries) {
        return 2 + entries * ENTRY_BYTES + 4;
    }

    private static ByteBuffer tag(ByteBuffer buffer, int id, int type, int count) {
        return buffer.putShort((short) id).putShort((short) type).putInt(count);
    }
}
