/* APocketVid's local, software-only preview decoder. FFmpeg remains dynamically linked. */
#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/mem.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

struct Input {
    int fd;
    int64_t offset, size, position, deadline;
};

static int interrupted(void *opaque) {
    return av_gettime_relative() >= ((struct Input *)opaque)->deadline;
}

static int read_local(void *opaque, uint8_t *buffer, int size) {
    struct Input *input = opaque;
    if (interrupted(input)) return AVERROR_EXIT;
    if (input->size >= 0) {
        int64_t remaining = input->size - input->position;
        if (remaining <= 0) return AVERROR_EOF;
        if (remaining < size) size = (int)remaining;
    }
    if (input->position > INT64_MAX - input->offset) return AVERROR(EINVAL);
    ssize_t count;
    do { count = pread64(input->fd, buffer, size, input->offset + input->position); }
    while (count < 0 && errno == EINTR && !interrupted(input));
    if (count < 0) return AVERROR(errno);
    if (count == 0) return AVERROR_EOF;
    input->position += count;
    return (int)count;
}

static int64_t seek_local(void *opaque, int64_t offset, int whence) {
    struct Input *input = opaque;
    if (interrupted(input)) return AVERROR_EXIT;
    if (whence & AVSEEK_SIZE) return input->size >= 0 ? input->size : AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = input->position;
    else if (whence == SEEK_END && input->size >= 0) base = input->size;
    else return AVERROR(EINVAL);
    if ((offset > 0 && base > INT64_MAX - offset) || (offset < 0 && offset < -base)) return AVERROR(EINVAL);
    input->position = base + offset;
    return input->position;
}

JNIEXPORT jbyteArray JNICALL Java_com_apocketvid_player_NativePreview_readFrame(
        JNIEnv *env, jclass type, jint fd, jlong offset, jlong length,
        jlong time_us, jint max_edge, jintArray dimensions) {
    (void)type;
    if (fd < 0 || offset < 0 || max_edge < 1 || max_edge > 320
            || dimensions == NULL || (*env)->GetArrayLength(env, dimensions) < 3) return NULL;
    struct Input input = {fd, offset, length, 0, av_gettime_relative() + 4500000};
    if (input.size < 0) {
        struct stat status;
        if (fstat(fd, &status) == 0 && S_ISREG(status.st_mode) && status.st_size >= offset)
            input.size = status.st_size - offset;
    }
    AVFormatContext *format = NULL;
    AVIOContext *io = NULL;
    AVCodecContext *codec = NULL;
    AVFrame *frame = NULL;
    AVPacket *packet = NULL;
    struct SwsContext *scaler = NULL;
    uint8_t *buffer = NULL, *pixels = NULL;
    jbyteArray output = NULL;

    buffer = av_malloc(65536);
    if (!buffer) goto cleanup;
    io = avio_alloc_context(buffer, 65536, 0, &input, read_local, NULL, seek_local);
    if (!io) goto cleanup;
    buffer = NULL; /* The AVIO context now owns this allocation. */
    format = avformat_alloc_context();
    if (!format) goto cleanup;
    format->pb = io;
    format->flags |= AVFMT_FLAG_CUSTOM_IO;
    format->interrupt_callback.callback = interrupted;
    format->interrupt_callback.opaque = &input;
    format->probesize = 2 * 1024 * 1024;
    format->max_analyze_duration = 3000000;
    if (avformat_open_input(&format, NULL, NULL, NULL) < 0) goto cleanup;
    if (avformat_find_stream_info(format, NULL) < 0) goto cleanup;
    const AVCodec *decoder = NULL;
    int stream_index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &decoder, 0);
    if (stream_index < 0 || !decoder || (decoder->capabilities & AV_CODEC_CAP_HARDWARE)) goto cleanup;
    AVStream *stream = format->streams[stream_index];
    codec = avcodec_alloc_context3(decoder);
    if (!codec || avcodec_parameters_to_context(codec, stream->codecpar) < 0) goto cleanup;
    codec->thread_count = 1;
    if (avcodec_open2(codec, decoder, NULL) < 0) goto cleanup;
    if (time_us > 0) {
        int64_t seek_time = av_rescale_q(time_us, AV_TIME_BASE_Q, stream->time_base);
        if (stream->start_time != AV_NOPTS_VALUE) seek_time += stream->start_time;
        if (av_seek_frame(format, stream_index, seek_time, AVSEEK_FLAG_BACKWARD) >= 0) avcodec_flush_buffers(codec);
    }
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame) goto cleanup;
    int decoded = 0;
    for (int count = 0; count < 8000 && !interrupted(&input); count++) {
        int result = av_read_frame(format, packet);
        if (result < 0) {
            avcodec_send_packet(codec, NULL);
            decoded = avcodec_receive_frame(codec, frame) == 0;
            break;
        }
        if (packet->stream_index == stream_index) {
            result = avcodec_send_packet(codec, packet);
            if (result >= 0 || result == AVERROR(EAGAIN)) decoded = avcodec_receive_frame(codec, frame) == 0;
        }
        av_packet_unref(packet);
        if (decoded) break;
    }
    // A decoded frame is the source of truth. Unknown container pixel formats never reach swscale.
    if (!decoded || frame->width <= 0 || frame->height <= 0
            || frame->width > 32768 || frame->height > 32768 || !sws_isSupportedInput(frame->format)) goto cleanup;
    int longest = frame->width > frame->height ? frame->width : frame->height;
    int width = longest > max_edge ? (int)((int64_t)frame->width * max_edge / longest) : frame->width;
    int height = longest > max_edge ? (int)((int64_t)frame->height * max_edge / longest) : frame->height;
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    scaler = sws_getContext(frame->width, frame->height, frame->format, width, height,
            AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    if (!scaler) goto cleanup;
    int bytes = width * height * 4;
    pixels = av_malloc(bytes);
    if (!pixels) goto cleanup;
    uint8_t *planes[4] = {pixels, NULL, NULL, NULL};
    int strides[4] = {width * 4, 0, 0, 0};
    if (sws_scale(scaler, (const uint8_t *const *)frame->data, frame->linesize, 0,
            frame->height, planes, strides) <= 0) goto cleanup;
    int rotation = 0;
    const AVPacketSideData *matrix = av_packet_side_data_get(stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (matrix && matrix->size >= 9 * sizeof(int32_t)) {
        double angle = -av_display_rotation_get((const int32_t *)matrix->data);
        if (isfinite(angle)) rotation = ((int)lround(angle) % 360 + 360) % 360;
    } else {
        AVDictionaryEntry *tag = av_dict_get(stream->metadata, "rotate", NULL, 0);
        if (tag) rotation = ((int)strtol(tag->value, NULL, 10) % 360 + 360) % 360;
    }
    jint info[3] = {width, height, rotation};
    (*env)->SetIntArrayRegion(env, dimensions, 0, 3, info);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    output = (*env)->NewByteArray(env, bytes);
    if (output) (*env)->SetByteArrayRegion(env, output, 0, bytes, (const jbyte *)pixels);
cleanup:
    av_free(pixels);
    sws_freeContext(scaler);
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codec);
    avformat_close_input(&format);
    if (io) { av_freep(&io->buffer); avio_context_free(&io); }
    av_free(buffer);
    return output;
}
