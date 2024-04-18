package cn.cleartv.webrtchelper;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.webrtc.TextureBufferImpl;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;

public class YuvFrame {
    public int width;
    public int height;
    public int yStride;
    public int uStride;
    public int vStride;
    public byte[] yPlane;
    public byte[] uPlane;
    public byte[] vPlane;
    public int rotationDegree;
    public long timestamp;

    private final Object planeLock = new Object();

    public static final int PROCESSING_NONE = 0x00;
    public static final int PROCESSING_CROP_TO_SQUARE = 0x01;


    private boolean enableLog = false;

    private void log(String text) {
        if (enableLog) Log.i("YuvFrame", text);
    }

    public YuvFrame(final VideoFrame videoFrame, boolean enableLog) {
        this.enableLog = enableLog;
        fromi420Buffer(videoFrame, PROCESSING_NONE, System.nanoTime());
    }

    /**
     * Creates a YuvFrame from the provided i420Buffer. Does no processing, and uses the current time as a timestamp.
     *
     * @param videoFrame Source i420Buffer.
     */
    public YuvFrame(final VideoFrame videoFrame) {
        fromi420Buffer(videoFrame, PROCESSING_NONE, System.nanoTime());
    }


    /**
     * Creates a YuvFrame from the provided i420Buffer. Does any processing indicated, and uses the current time as a timestamp.
     *
     * @param videoFrame      Source i420Buffer.
     * @param processingFlags Processing flags, YuvFrame.PROCESSING_NONE for no processing.
     */
    @SuppressWarnings("unused")
    public YuvFrame(final VideoFrame videoFrame, final int processingFlags) {
        fromi420Buffer(videoFrame, processingFlags, System.nanoTime());
    }


    /**
     * Creates a YuvFrame from the provided i420Buffer. Does any processing indicated, and uses the given timestamp.
     *
     * @param videoFrame      Source i420Buffer.
     * @param processingFlags Processing flags, YuvFrame.PROCESSING_NONE for no processing.
     * @param timestamp       The timestamp to give the frame.
     */
    public YuvFrame(final VideoFrame videoFrame, final int processingFlags, final long timestamp) {
        fromi420Buffer(videoFrame, processingFlags, timestamp);
    }


    /**
     * Replaces the data in this YuvFrame with the data from the provided frame. Will create new byte arrays to hold pixel data if necessary,
     * or will reuse existing arrays if they're already the correct size.
     *
     * @param videoFrame      Source i420Buffer.
     * @param processingFlags Processing flags, YuvFrame.PROCESSING_NONE for no processing.
     * @param timestamp       The timestamp to give the frame.
     */
    public void fromi420Buffer(final VideoFrame videoFrame, final int processingFlags, final long timestamp) {
        synchronized (planeLock) {
            try {
                VideoFrame.Buffer buffer = videoFrame.getBuffer();
                log("buffer:" + buffer.getClass().getName() + ",width:" + buffer.getWidth() + ", height:" + buffer.getHeight() + ", rotation:" + videoFrame.getRotation());
                VideoFrame.I420Buffer i420Buffer = null;
                if (buffer instanceof TextureBufferImpl) {
                    // opengl中的buffer转420会有问题
                }
                if (i420Buffer == null) {
                    i420Buffer = videoFrame.getBuffer().toI420();
                }
                if (i420Buffer == null) return;
                // Save timestamp
                this.timestamp = timestamp;

                // TODO: Check to see if i420Buffer.yuvFrame is actually true?  Need to find out what the alternative would be.

                // Copy YUV stride information
                // TODO: There is probably a case where strides makes a difference, so far we haven't run across it.
                yStride = i420Buffer.getStrideY();
                uStride = i420Buffer.getStrideU();
                vStride = i420Buffer.getStrideV();
                log("yStride:" + yStride + ", uStride:" + uStride + ", vStride:" + vStride);

                // Copy rotation information
                rotationDegree = videoFrame.getRotation();  // Just save rotation info for now, doing actual rotation can wait until per-pixel processing.
                // Copy the pixel data, processing as requested.
                if (PROCESSING_CROP_TO_SQUARE == (processingFlags & PROCESSING_CROP_TO_SQUARE)) {
                    copyPlanesCropped(i420Buffer);
                } else {
                    copyPlanes(i420Buffer);
                }
                log("Y:" + yPlane.length + ", U:" + uPlane.length + ", V:" + vPlane.length);
                i420Buffer.release();
            } catch (Throwable t) {
                dispose();
            }
        }
    }


    public void dispose() {
        yPlane = null;
        vPlane = null;
        uPlane = null;
    }


    public boolean hasData() {
        return yPlane != null && vPlane != null && uPlane != null;
    }


    /**
     * Copy the Y, V, and U planes from the source i420Buffer.
     * Sets width and height.
     *
     * @param i420Buffer Source frame.
     */
    private void copyPlanes(final VideoFrame.I420Buffer i420Buffer) {
        synchronized (planeLock) {
            // Copy the Y, V, and U ButeBuffers to their corresponding byte arrays.
            // Existing byte arrays are passed in for possible reuse.
            yPlane = copyByteBuffer(yPlane, i420Buffer.getDataY());
            vPlane = copyByteBuffer(vPlane, i420Buffer.getDataV());
            uPlane = copyByteBuffer(uPlane, i420Buffer.getDataU());

            // Set the width and height of the frame.
            width = i420Buffer.getWidth();
            height = i420Buffer.getHeight();
        }
    }


    /**
     * Copies the entire contents of a ByteBuffer into a byte array.
     * If the byte array exists, and is the correct size, it will be reused.
     * If the byte array is null, or isn't properly sized, a new byte array will be created.
     *
     * @param dst A byte array to copy the ByteBuffer contents to. Can be null.
     * @param src A ByteBuffer to copy data from.
     * @return A byte array containing the contents of the ByteBuffer. If the provided dst was non-null and the correct size,
     * it will be returned. If not, a new byte array will be created.
     */
    private byte[] copyByteBuffer(@Nullable byte[] dst, @NonNull final ByteBuffer src) {
        // Create a new byte array if necessary.
        byte[] out;
        if ((null == dst) || (dst.length != src.capacity())) {
            out = new byte[src.capacity()];
        } else {
            out = dst;
        }

        // Copy the ByteBuffer's contents to the byte array.
        src.get(out);

        return out;
    }


    /**
     * Copy the Y, V, and U planes from the source i420Buffer, cropping them to square.
     * Sets width and height.
     *
     * @param i420Buffer Source frame.
     */
    private void copyPlanesCropped(final VideoFrame.I420Buffer i420Buffer) {
        synchronized (planeLock) {
            // Verify that the dimensions of the i420Buffer are appropriate for cropping
            // If improper dimensions are found, default back to copying the entire frame.
            final int width = i420Buffer.getWidth();
            final int height = i420Buffer.getHeight();

            if (width > height) {
                // Calculate the size of the cropped portion of the the image
                // The cropped width must be divisible by 4, since it will be divided by 2 to crop the center of the frame,
                // and then divided by two again for processing the U and V planes, as each value there corresponds to
                // a 2x2 square of pixels. All of those measurements must be whole integers.
                final int cropWidth = width - height;
                if ((cropWidth % 4) == 0) {
                    // Create a row buffer for the crop method to use - the largest row width will be equal to the source frame's height (since we're cropping to square)
                    final byte[] row = new byte[height];  // TODO: Create a static row buffer, so this doesn't get created for every frame?

                    // Copy the Y plane.  Existing yPlane is passed in for possible reuse if it's the same size.
                    yPlane = cropByteBuffer(yPlane, i420Buffer.getDataY(), width, height, row);

                    // Copy/crop the U and V planes. The U and V planes' width and height will be half that of the Y plane's.
                    // The same row buffer can be reused, since being oversize isn't an issue.
                    vPlane = cropByteBuffer(vPlane, i420Buffer.getDataV(), width / 2, height / 2, row);
                    uPlane = cropByteBuffer(uPlane, i420Buffer.getDataU(), width / 2, height / 2, row);

                    // Set size
                    // noinspection SuspiciousNameCombination (Shut up, Lint, I know what I'm doing.)
                    this.width = height;
                    this.height = height;
                } else {
                    copyPlanes(i420Buffer);
                }
            } else {
                // Calculate the size of the cropped portion of the the image
                // The cropped height must be divisible by 4, since it will be divided by 2 to crop the center of the frame,
                // and then divided by two again for processing the U and V planes, as each value there corresponds to
                // a 2x2 square of pixels. All of those measurements must be whole integers.
                final int cropHeight = height - width;
                if ((cropHeight % 4) == 0) {
                    // Copy the Y plane. (No row buffer is needed if height >= width.)
                    yPlane = cropByteBuffer(yPlane, i420Buffer.getDataY(), width, height, null);

                    // Copy/crop the U and V planes. The U and V planes' width and height will be half that of the Y plane's.
                    // The same row buffer can be reused, since being oversize isn't an issue.
                    vPlane = cropByteBuffer(vPlane, i420Buffer.getDataV(), width / 2, height / 2, null);
                    uPlane = cropByteBuffer(uPlane, i420Buffer.getDataU(), width / 2, height / 2, null);

                    // Set size
                    // noinspection SuspiciousNameCombination (Shut up, Lint, I know what I'm doing.)
                    this.height = width;
                    this.width = width;
                } else {
                    copyPlanes(i420Buffer);
                }
            }
        }
    }


    /**
     * Copies the contents of a ByteBuffer into a byte array, cropping the center of the image to square.
     * If the byte array exists, and is the correct size, it will be reused.
     * If the byte array is null, or isn't properly sized, a new byte array will be created.
     *
     * @param dst       A byte array to copy the ByteBuffer contents to. Can be null.
     * @param src       A ByteBuffer to copy data from.
     * @param srcWidth  The width of the source frame.
     * @param srcHeight The height of ths source frame.
     * @param row       A byte array with a length equal to or greater than the cropped frame's width, for use as a buffer.
     *                  Can be null. If no row buffer is provided and one is needed, or the buffer is too short, an exception
     *                  will likely result.
     * @return A byte array containing the cropped contents of the ByteBuffer. If the provided dst was non-null and the correct size,
     * it will be returned. If not, a new byte array will be created.
     * @throws NullPointerException
     */
    private byte[] cropByteBuffer(@Nullable byte[] dst, @NonNull final ByteBuffer src, final int srcWidth, final int srcHeight, @Nullable final byte[] row)
            throws NullPointerException {
        // If the frame is wider than it is tall, copy the center of each row to trim off the left and right edges
        if (srcWidth > srcHeight) {
            // We'll need a row buffer, here. Throw an exception if we don't have one.
            if (null == row) {
                throw new NullPointerException("YuvFrame.cropByteBffer: Need row buffer array, and the array provided was null.");
            }

            // Create a new destination byte array if necessary.
            final int croppedSize = srcHeight * srcHeight;
            final byte[] out;
            if ((null == dst) || (dst.length != croppedSize)) {
                out = new byte[croppedSize];
            } else {
                out = dst;
            }

            // Calculate where on each row to start copying
            final int indent = (srcWidth - srcHeight) / 2;

            // Copy the ByteBuffer
            for (int i = 0; i < srcHeight; i++) {
                // Set the position of the ByteBuffer to the beginning of the current row,
                // adding the calculated indent to trim off the left side.
                src.position((i * srcWidth) + indent);

                // Copy the cropped row to the row buffer
                src.get(row, 0, srcHeight);

                // Copy the row buffer to the destination array
                System.arraycopy(row, 0, out, i * srcHeight, srcHeight);
            }

            return out;
        }
        // If the frame is taller than it is wide, copy the center of the image, cropping off the top and bottom edges.
        // NOTE: If the width and height are equal, this method should result in a straight copy of the source ByteBuffer,
        //       as the calculated row offset will be zero.
        else {
            // Create a new destination byte array if necessary.
            final int croppedSize = srcWidth * srcWidth;
            final byte[] out;
            if ((null == dst) || (dst.length != croppedSize)) {
                out = new byte[croppedSize];
            } else {
                out = dst;
            }

            // Calculate where to start reading
            final int start = ((srcHeight - srcWidth) / 2) * srcWidth;  // ((h-w)/2) is the number of rows to skip, multiply by w again to get the starting ByteBuffer position.

            // Copy the ByteBuffer
            // Since we need to take a sequential series of whole rows, only one copy is necessary
            src.position(start);
            src.get(out, 0, croppedSize);

            return out;
        }
    }


    /**
     * Converts this YUV frame to an ARGB_8888 Bitmap. Applies stored rotation.
     * Remaning code based on http://stackoverflow.com/a/12702836 by rics (http://stackoverflow.com/users/21047/rics)
     *
     * @return A new Bitmap containing the converted frame.
     */
    public Bitmap getBitmap() {
        if (!hasData()) return null;
        // Calculate the size of the frame
        final int size = width * height;

        // Allocate an array to hold the ARGB pixel data
        final int[] argb = new int[size];

        if (yPlane.length / uPlane.length > 2) {
            convertYuvToArgb(argb, rotationDegree);
        } else {
            convertYuv422ToArgb(argb, rotationDegree);
        }
        if (rotationDegree % 180 != 0) {
            return Bitmap.createBitmap(argb, height, width, Bitmap.Config.ARGB_8888);
        } else {
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private void convertYuvToArgb(final int[] outputArgb, int degree) {
        synchronized (planeLock) {
            // Calculate the size of the frame
            final int size = width * height;
            final int invertSize = size - 1;
            final int uvWidth = width / 2;
            final int uvHeight = height / 2;
            // 如果是90或270，先旋转90一下
            final boolean needRotation = degree % 180 != 0;
            // 270或者180，再翻转一下
            final boolean needInvert = degree == -90 || degree == 270 || degree == 180 || degree == -180;

            int u, v;
            int y1, y2, y3, y4;
            int p1, p2, p3, p4;
            int uvIndex;
            for (int row = 0; row < uvHeight; row++) {
                for (int col = 0; col < uvWidth; col++) {
                    p1 = (row * 2 * width) + (col * 2);
                    p2 = p1 + 1;
                    p3 = p1 + width;
                    p4 = p3 + 1;
                    uvIndex = (row * uvWidth) + col;

                    if (uPlane.length > uvIndex) {
                        u = uPlane[uvIndex] & 0xff;
                        v = vPlane[uvIndex] & 0xff;
                        u = u - 128;
                        v = v - 128;
                    } else {
                        u = 0;
                        v = 0;
                    }
                    if (yPlane.length > p4) {
                        y1 = yPlane[p1] & 0xff;
                        y2 = yPlane[p2] & 0xff;
                        y3 = yPlane[p3] & 0xff;
                        y4 = yPlane[p4] & 0xff;
                    } else {
                        y1 = 0;
                        y2 = 0;
                        y3 = 0;
                        y4 = 0;
                    }


                    // 如果是90或270，先旋转90一下
                    if (needRotation) {
                        p1 = (col * 2 + 1) * height - row * 2 - 1;
                        p2 = p1 + height;
                        p3 = p1 - 1;
                        p4 = p3 + height;
                    }

                    // 270或者180，再翻转一下
                    if (needInvert) {
                        p1 = invertSize - p1;
                        p2 = invertSize - p2;
                        p3 = invertSize - p3;
                        p4 = invertSize - p4;
                    }

                    // Convert each YUV pixel to RGB
                    outputArgb[p1] = convertYuvToArgb(y1, u, v);
                    outputArgb[p2] = convertYuvToArgb(y2, u, v);
                    outputArgb[p3] = convertYuvToArgb(y3, u, v);
                    outputArgb[p4] = convertYuvToArgb(y4, u, v);

                }
            }
        }
    }

    private void convertYuv422ToArgb(final int[] outputArgb, int degree) {
        synchronized (planeLock) {
            final int size = width * height;
            final int invertSize = size - 1;
            final int uvWidth = width / 2;
            final int uvHeight = height;
            // 如果是90或270，先旋转270一下
            final boolean needRotation = degree % 180 != 0;
            // 90或者180，再翻转一下
            final boolean needInvert = degree == -90 || degree == 270 || degree == 180 || degree == -180;
            int u, v;
            int y1, y2;
            int p1, p2;
            int uvIndex;

            for (int row = 0; row < uvHeight; row++) {
                for (int col = 0; col < uvWidth; col++) {
                    p1 = width * row + col * 2;
                    p2 = p1 + 1;
                    uvIndex = uvWidth * row + col;
                    y1 = yPlane[p1] & 0xff;
                    y2 = yPlane[p2] & 0xff;
                    if (uPlane.length > uvIndex) {
                        u = uPlane[uvIndex] & 0xff;
                        v = vPlane[uvIndex] & 0xff;
                        u = u - 128;
                        v = v - 128;
                    } else {
                        u = 0;
                        v = 0;
                    }

                    // 如果是90或270，先旋转90一下
                    if (needRotation) {
                        p1 = (col * 2 + 1) * height - row - 1;
                        p2 = p1 + height;
                    }

                    // 270或者180，再翻转一下
                    if (needInvert) {
                        p1 = invertSize - p1;
                        p2 = invertSize - p2;
                    }
                    outputArgb[p1] = convertYuvToArgb(y1, u, v);
                    outputArgb[p2] = convertYuvToArgb(y2, u, v);
                }
            }
        }
    }

    private int convertYuvToArgb(final int y, final int u, final int v) {
        int r, g, b;

//        v = u;
        // Convert YUV to RGB
        r = y + (int) (1.402f * v);
        g = y - (int) (0.344f * u + 0.714f * v);
        b = y + (int) (1.772f * u);

//		// Convert YUV to RGB
//		r = y + (int)(1.13983f*v);
//		g = y - (int)(0.39465f*u +0.5806f*v);
//		b = y + (int)(2.03211f*u);

        // Clamp RGB values to [0,255]
        r = (r > 255) ? 255 : (r < 0) ? 0 : r;
        g = (g > 255) ? 255 : (g < 0) ? 0 : g;
        b = (b > 255) ? 255 : (b < 0) ? 0 : b;

        // Shift the RGB values into position in the final ARGB pixel
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

}