package com.kurisa.mwarecorder.recorder;

import com.kurisa.mwarecorder.export.JsonExporter;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures the rendered world as JPEG frames.
 *
 * <p>Frames are read from the OpenGL back buffer inside {@code WorldRenderEvents.LAST}, which
 * runs after the world is drawn but before the HUD, so the video shows the game world without
 * the recording overlay. Reading and encoding are split: the render thread copies the pixel
 * buffer and hands it to a single encoder thread, which keeps frame order and avoids stalling
 * the render loop.</p>
 *
 * <p>Each frame is written to {@code frames/frame_000001.jpg} and indexed in
 * {@code frames.jsonl} together with its {@code sample_tick}, game time and dimension. That
 * index is what allows the dataset converter to align video frames with player states.</p>
 */
public final class FrameCapture {

    /** Single encoder thread, so frames keep their order without blocking rendering. */
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Mwarecorder-FrameEncoder");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicInteger PENDING = new AtomicInteger(0);

    private static int captureEveryNFrames = 2;
    private static float jpegQuality = 0.85F;
    private static int targetWidth;
    private static int targetHeight;
    private static int maxPendingFrames = 64;

    private static int renderedFrameCounter;
    private static int frameId;
    private static int droppedFrames;

    private static File framesDirectory;

    /** Reused read buffer; reallocated whenever the window size changes. */
    private static ByteBuffer readBuffer;
    private static int readBufferWidth = -1;
    private static int readBufferHeight = -1;

    private FrameCapture() {
    }

    /* =====================================================================================
     * Lifecycle
     * ===================================================================================== */

    /**
     * Starts capturing into {@code <episode>/frames}.
     *
     * @param episodeDirectory episode root
     * @param everyNFrames     capture one frame every N rendered frames
     * @param quality          JPEG quality in [0.1, 1.0]
     * @param width            target width, {@code 0} for native
     * @param height           target height, {@code 0} for native
     * @param maxPending       maximum frames queued before new ones are dropped
     */
    public static void start(
            File episodeDirectory,
            int everyNFrames,
            float quality,
            int width,
            int height,
            int maxPending) {

        framesDirectory = new File(episodeDirectory, "frames");
        if (!framesDirectory.exists() && !framesDirectory.mkdirs()) {
            System.err.println("[Mwarecorder] Cannot create frame directory: " + framesDirectory);
            return;
        }

        captureEveryNFrames = Math.max(1, everyNFrames);
        jpegQuality = clamp(quality, 0.1F, 1.0F);
        targetWidth = Math.max(0, width);
        targetHeight = Math.max(0, height);
        maxPendingFrames = Math.max(1, maxPending);

        renderedFrameCounter = 0;
        frameId = 0;
        droppedFrames = 0;
        PENDING.set(0);

        RUNNING.set(true);

        System.out.println("[Mwarecorder] Frame capture started (every " + captureEveryNFrames
                + " frames, target " + (targetWidth > 0 ? targetWidth + "x" + targetHeight : "native")
                + ", quality " + jpegQuality + ")");
    }

    /** Stops capturing and waits until the encoder has written every queued frame. */    public static void stop() {
        if (!RUNNING.getAndSet(false)) {
            return;
        }

        long deadline = System.currentTimeMillis() + 30_000L;
        while (PENDING.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (PENDING.get() > 0) {
            System.err.println("[Mwarecorder] Frame encoder is still busy after 30 s; "
                    + PENDING.get() + " frame(s) may be missing.");
        }

        System.out.println("[Mwarecorder] Frame capture stopped. Encoded " + frameId
                + " frame(s), dropped " + droppedFrames + ".");
    }

    /* =====================================================================================
     * Render callback
     * ===================================================================================== */

    /** Called from {@code WorldRenderEvents.LAST}, on the render thread. */
    public static void onFrameRendered() {
        if (!RUNNING.get() || framesDirectory == null) {
            return;
        }
        if (!EpisodeManager.isRecording()) {
            return;
        }

        // A render frame can happen between "episode started" and the first client tick. Such a
        // frame would be indexed with sample_tick 0 and could never be matched to a state, so
        // it is skipped.
        final int sampleTick = (int) TickRecorder.getSampleTick();
        if (sampleTick <= 0) {
            return;
        }

        renderedFrameCounter++;
        if (renderedFrameCounter % captureEveryNFrames != 0) {
            return;
        }

        // Protect memory and the render thread when the disk cannot keep up.
        if (PENDING.get() >= maxPendingFrames) {
            droppedFrames++;
            RecordingStats.onFrameDropped();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) {
            return;
        }

        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int byteSize = width * height * 4;

        if (readBuffer == null || readBufferWidth != width || readBufferHeight != height) {
            readBuffer = BufferUtils.createByteBuffer(byteSize);
            readBufferWidth = width;
            readBufferHeight = height;
        }

        readBuffer.clear();
        GL11.glReadPixels(
                0, 0, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                readBuffer);
        readBuffer.rewind();

        // The render thread reuses its buffer, so the encoder needs its own copy.
        ByteBuffer copy = BufferUtils.createByteBuffer(byteSize);
        copy.put(readBuffer);
        copy.flip();

        final int sourceWidth = width;
        final int sourceHeight = height;
        final long worldTime = minecraft.level != null ? minecraft.level.getGameTime() : -1L;
        final String dimension = minecraft.level != null
                ? minecraft.level.dimension().location().toString()
                : "";
        final int id = ++frameId;

        PENDING.incrementAndGet();
        ENCODER.submit(() -> {
            try {
                encodeAndSave(copy, sourceWidth, sourceHeight, id, sampleTick, worldTime, dimension);
            } finally {
                PENDING.decrementAndGet();
            }
        });
    }

    /* =====================================================================================
     * Encoding
     * ===================================================================================== */

    private static void encodeAndSave(
            ByteBuffer pixels,
            int width,
            int height,
            int id,
            int sampleTick,
            long worldTime,
            String dimension) {

        try {
            int outWidth = targetWidth > 0 ? targetWidth : width;
            int outHeight = targetHeight > 0 ? targetHeight : height;

            BufferedImage image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);

            int[] rowPixels = new int[outWidth * outHeight];

            double scaleX = (double) width / outWidth;
            double scaleY = (double) height / outHeight;

            // OpenGL's origin is bottom-left while BufferedImage's is top-left, hence the
            // vertical flip and the nearest-neighbour downscale in the same pass.
            for (int y = 0; y < outHeight; y++) {
                int sourceY = height - 1 - (int) (y * scaleY);
                sourceY = Math.max(0, Math.min(height - 1, sourceY));

                int sourceRow = sourceY * width;
                int destinationRow = y * outWidth;

                for (int x = 0; x < outWidth; x++) {
                    int sourceX = (int) (x * scaleX);
                    sourceX = Math.min(sourceX, width - 1);

                    int offset = (sourceRow + sourceX) * 4;
                    int red = pixels.get(offset) & 0xFF;
                    int green = pixels.get(offset + 1) & 0xFF;
                    int blue = pixels.get(offset + 2) & 0xFF;

                    rowPixels[destinationRow + x] = (red << 16) | (green << 8) | blue;
                }
            }

            image.setRGB(0, 0, outWidth, outHeight, rowPixels, 0, outWidth);

            String filename = String.format("frame_%06d.jpg", id);
            writeJpeg(image, new File(framesDirectory, filename));

            JsonExporter.writeFrameIndex(id, sampleTick, worldTime, filename, dimension);

        } catch (Exception e) {
            System.err.println("[Mwarecorder] Frame encoding failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void writeJpeg(BufferedImage image, File output) throws Exception {
        ImageWriter writer = null;
        ImageOutputStream stream = null;

        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                throw new IllegalStateException("No JPEG writer available");
            }

            writer = writers.next();
            stream = ImageIO.createImageOutputStream(output);
            writer.setOutput(stream);

            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(jpegQuality);

            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // Closing is best effort during shutdown.
                }
            }
            if (writer != null) {
                writer.dispose();
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
