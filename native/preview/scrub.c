/* Persistent local scrub decoder. Never runs in the playback/UI process. */
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/mem.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>

#define CACHE_FRAMES 1024
#define CACHE_BYTES (96 * 1024 * 1024)
#define SAMPLE_US 27000

struct CachedFrame { int64_t time; uint16_t *pixels; };
struct Scrub {
    int fd;
    int64_t offset, length, position, deadline;
    AVFormatContext *format;
    AVIOContext *io;
    AVCodecContext *codec;
    AVPacket *packet;
    AVFrame *frame;
    struct SwsContext *scaler;
    ANativeWindow *window;
    int stream, width, height, rotation, capacity, count, eof;
    int64_t cursor, last_cached, last_drawn, start_time, duration, seek_goal;
    struct CachedFrame cache[CACHE_FRAMES];
};

static int scrub_interrupted(void *opaque) {
    return av_gettime_relative() >= ((struct Scrub *)opaque)->deadline;
}

static int scrub_read(void *opaque, uint8_t *buffer, int bytes) {
    struct Scrub *s = opaque;
    if (scrub_interrupted(s)) return AVERROR_EXIT;
    if (s->length >= 0) {
        int64_t remaining = s->length - s->position;
        if (remaining <= 0) return AVERROR_EOF;
        if (remaining < bytes) bytes = (int)remaining;
    }
    if (s->position > INT64_MAX - s->offset) return AVERROR(EINVAL);
    ssize_t count;
    do { count = pread64(s->fd, buffer, bytes, s->offset + s->position); }
    while (count < 0 && errno == EINTR && !scrub_interrupted(s));
    if (count < 0) return AVERROR(errno);
    if (!count) return AVERROR_EOF;
    s->position += count;
    return (int)count;
}

static int64_t scrub_seek(void *opaque, int64_t offset, int whence) {
    struct Scrub *s = opaque;
    if (scrub_interrupted(s)) return AVERROR_EXIT;
    if (whence & AVSEEK_SIZE) return s->length >= 0 ? s->length : AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = s->position;
    else if (whence == SEEK_END && s->length >= 0) base = s->length;
    else return AVERROR(EINVAL);
    if ((offset > 0 && base > INT64_MAX - offset) || (offset < 0 && offset < -base)) return AVERROR(EINVAL);
    if (base + offset < 0) return AVERROR(EINVAL);
    s->position = base + offset;
    return s->position;
}

static void scrub_free(struct Scrub *s) {
    if (!s) return;
    for (int i = 0; i < s->count; i++) av_free(s->cache[i].pixels);
    sws_freeContext(s->scaler);
    av_packet_free(&s->packet);
    av_frame_free(&s->frame);
    avcodec_free_context(&s->codec);
    avformat_close_input(&s->format);
    if (s->io) { av_freep(&s->io->buffer); avio_context_free(&s->io); }
    if (s->window) ANativeWindow_release(s->window);
    if (s->fd >= 0) close(s->fd);
    av_free(s);
}

JNIEXPORT jlong JNICALL Java_com_apocketvid_player_NativeScrub_open(
        JNIEnv *env, jclass type, jint fd, jlong offset, jlong length,
        jobject surface, jintArray size) {
    (void)type;
    if (fd < 0 || offset < 0 || !surface || !size || (*env)->GetArrayLength(env, size) < 2) return 0;
    struct Scrub *s = av_mallocz(sizeof(*s));
    if (!s) return 0;
    s->fd = dup(fd);
    s->offset = offset;
    s->length = length;
    s->deadline = av_gettime_relative() + 4500000;
    s->cursor = s->last_cached = s->last_drawn = s->seek_goal = -1;
    if (s->fd < 0) goto fail;
    if (s->length < 0) {
        struct stat status;
        if (!fstat(s->fd, &status) && S_ISREG(status.st_mode) && status.st_size >= offset)
            s->length = status.st_size - offset;
    }
    uint8_t *buffer = av_malloc(65536);
    if (!buffer) goto fail;
    s->io = avio_alloc_context(buffer, 65536, 0, s, scrub_read, NULL, scrub_seek);
    if (!s->io) { av_free(buffer); goto fail; }
    s->format = avformat_alloc_context();
    if (!s->format) goto fail;
    s->format->pb = s->io;
    s->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    s->format->interrupt_callback.callback = scrub_interrupted;
    s->format->interrupt_callback.opaque = s;
    s->format->probesize = 2 * 1024 * 1024;
    s->format->max_analyze_duration = 1500000;
    if (avformat_open_input(&s->format, NULL, NULL, NULL) < 0 || avformat_find_stream_info(s->format, NULL) < 0) goto fail;
    const AVCodec *codec = NULL;
    s->stream = av_find_best_stream(s->format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (s->stream < 0 || !codec || (codec->capabilities & AV_CODEC_CAP_HARDWARE)) goto fail;
    AVStream *stream = s->format->streams[s->stream];
    s->codec = avcodec_alloc_context3(codec);
    if (!s->codec || avcodec_parameters_to_context(s->codec, stream->codecpar) < 0) goto fail;
    // Slice threads avoid adding a queue of frame-thread latency to interactive decoding.
    s->codec->thread_count = 2;
    s->codec->thread_type = FF_THREAD_SLICE;
    s->codec->skip_loop_filter = AVDISCARD_NONREF;
    if (avcodec_open2(s->codec, codec, NULL) < 0) goto fail;
    s->packet = av_packet_alloc();
    s->frame = av_frame_alloc();
    if (!s->packet || !s->frame) goto fail;
    s->start_time = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
    s->duration = stream->duration == AV_NOPTS_VALUE ? s->format->duration
            : av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
    int width = s->codec->width, height = s->codec->height;
    if (width <= 0 || height <= 0 || width > 32768 || height > 32768) goto fail;
    AVRational aspect = av_guess_sample_aspect_ratio(s->format, stream, NULL);
    double display_width = aspect.num > 0 && aspect.den > 0 ? width * av_q2d(aspect) : width;
    double edge = display_width > height ? display_width : height;
    s->width = edge > 384 ? (int)(display_width * 384 / edge) : (int)display_width;
    s->height = edge > 384 ? (int)(height * 384 / edge) : height;
    if (s->width < 1) s->width = 1;
    if (s->height < 1) s->height = 1;
    int capacity = CACHE_BYTES / (s->width * s->height * 2);
    s->capacity = capacity > CACHE_FRAMES ? CACHE_FRAMES : capacity;
    const AVPacketSideData *matrix = av_packet_side_data_get(stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (matrix && matrix->size >= 9 * sizeof(int32_t)) {
        double angle = -av_display_rotation_get((const int32_t *)matrix->data);
        if (isfinite(angle)) s->rotation = ((int)lround(angle) % 360 + 360) % 360;
    } else {
        AVDictionaryEntry *tag = av_dict_get(stream->metadata, "rotate", NULL, 0);
        if (tag) s->rotation = ((int)strtol(tag->value, NULL, 10) % 360 + 360) % 360;
    }
    int rotated = s->rotation == 90 || s->rotation == 270;
    jint info[2] = {rotated ? s->height : s->width, rotated ? s->width : s->height};
    s->window = ANativeWindow_fromSurface(env, surface);
    if (!s->window || ANativeWindow_setBuffersGeometry(s->window, info[0], info[1], WINDOW_FORMAT_RGB_565) < 0) goto fail;
    (*env)->SetIntArrayRegion(env, size, 0, 2, info);
    if ((*env)->ExceptionCheck(env)) goto fail;
    return (jlong)(intptr_t)s;
fail:
    scrub_free(s);
    return 0;
}

static int closest(struct Scrub *s, int64_t time) {
    int result = -1;
    int64_t difference = INT64_MAX;
    for (int i = 0; i < s->count; i++) {
        int64_t distance = llabs(time - s->cache[i].time);
        if (distance < difference) { result = i; difference = distance; }
    }
    return result;
}

static int cache_picture(struct Scrub *s, int64_t target) {
    AVFrame *frame = s->frame;
    if (frame->width <= 0 || frame->height <= 0 || frame->width > 32768 || frame->height > 32768
            || !sws_isSupportedInput(frame->format)) return -1;
    int64_t pts = frame->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) return -1;
    int64_t time = av_rescale_q(pts - s->start_time, s->format->streams[s->stream]->time_base, AV_TIME_BASE_Q);
    if (time < 0) time = 0;
    s->cursor = time;
    if (s->last_cached >= 0 && time >= s->last_cached && time - s->last_cached < SAMPLE_US) return 0;
    int near = closest(s, time);
    if (near >= 0 && llabs(s->cache[near].time - time) < SAMPLE_US / 2) return 0;
    int slot = s->count;
    if (slot >= s->capacity) {
        int64_t farthest = -1;
        for (int i = 0; i < s->count; i++) {
            int64_t distance = llabs(target - s->cache[i].time);
            if (distance > farthest) { slot = i; farthest = distance; }
        }
    }
    if (slot < 0 || slot >= s->capacity) return -1;
    if (!s->cache[slot].pixels) {
        s->cache[slot].pixels = av_malloc(s->width * s->height * 2);
        if (!s->cache[slot].pixels) return -1;
    }
    s->scaler = sws_getCachedContext(s->scaler, frame->width, frame->height, frame->format,
            s->width, s->height, AV_PIX_FMT_RGB565LE, SWS_FAST_BILINEAR, NULL, NULL, NULL);
    if (!s->scaler) return -1;
    uint8_t *planes[4] = {(uint8_t *)s->cache[slot].pixels, NULL, NULL, NULL};
    int strides[4] = {s->width * 2, 0, 0, 0};
    if (sws_scale(s->scaler, (const uint8_t *const *)frame->data, frame->linesize, 0,
            frame->height, planes, strides) <= 0) return -1;
    if (slot == s->count) s->count++;
    s->cache[slot].time = time;
    s->last_cached = time;
    return 0;
}

static int draw_picture(struct Scrub *s, int index) {
    struct CachedFrame *frame = &s->cache[index];
    if (s->last_drawn == frame->time) return 0;
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(s->window, &buffer, NULL) < 0) return -1;
    int rotated = s->rotation == 90 || s->rotation == 270;
    int width = rotated ? s->height : s->width;
    int height = rotated ? s->width : s->height;
    if (buffer.format != WINDOW_FORMAT_RGB_565 || buffer.width < width || buffer.height < height) {
        ANativeWindow_unlockAndPost(s->window);
        return -1;
    }
    uint16_t *output = buffer.bits;
    if (!s->rotation) {
        for (int y = 0; y < s->height; y++)
            memcpy(output + y * buffer.stride, frame->pixels + y * s->width, s->width * 2);
    } else {
        for (int y = 0; y < s->height; y++) for (int x = 0; x < s->width; x++) {
            int dx = x, dy = y;
            if (s->rotation == 90) { dx = s->height - y - 1; dy = x; }
            else if (s->rotation == 180) { dx = s->width - x - 1; dy = s->height - y - 1; }
            else if (s->rotation == 270) { dx = y; dy = s->width - x - 1; }
            output[dy * buffer.stride + dx] = frame->pixels[y * s->width + x];
        }
    }
    ANativeWindow_unlockAndPost(s->window);
    s->last_drawn = frame->time;
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_apocketvid_player_NativeScrub_pump(
        JNIEnv *env, jclass type, jlong handle, jlong position, jboolean display) {
    (void)env; (void)type;
    struct Scrub *s = (struct Scrub *)(intptr_t)handle;
    if (!s) return -1;
    int64_t target = position < 0 ? 0 : position;
    s->deadline = av_gettime_relative() + 2000000;
    int near = closest(s, target);
    int cached = near >= 0 && llabs(s->cache[near].time - target) <= 150000;
    if (display && near >= 0 && draw_picture(s, near) < 0) return -1;
    int64_t result = display && near >= 0 ? s->cache[near].time : -1;
    // Keep a neighborhood decoded ahead of the drag. Cached reverse motion never seeks.
    int64_t end = target + 16000000;
    if (s->duration > 0 && end > s->duration) end = s->duration;
    if ((s->eof || s->cursor >= end) && cached) return display ? result : -2;
    int catching_up = s->seek_goal >= 0 && s->cursor < s->seek_goal;
    if (s->cursor < 0 || (!cached && !catching_up
            && (target < s->cursor - 200000 || target > s->cursor + 3000000))) {
        int64_t from = display ? target : target > 4000000 ? target - 4000000 : 0;
        AVStream *stream = s->format->streams[s->stream];
        int64_t seek_pts = av_rescale_q(from, AV_TIME_BASE_Q, stream->time_base) + s->start_time;
        if (av_seek_frame(s->format, s->stream, seek_pts, AVSEEK_FLAG_BACKWARD) < 0) return result;
        avcodec_flush_buffers(s->codec);
        av_packet_unref(s->packet);
        s->eof = 0;
        s->seek_goal = target;
        s->cursor = from;
        s->last_cached = -1;
    }
    int64_t budget = av_gettime_relative() + (display ? 12000 : 24000);
    for (int count = 0; count < 300 && av_gettime_relative() < budget; count++) {
        int received = avcodec_receive_frame(s->codec, s->frame);
        if (received == 0) {
            if (cache_picture(s, target) < 0) break;
            av_frame_unref(s->frame);
            if (s->cursor >= end) break;
            continue;
        }
        if (received == AVERROR_EOF) { s->eof = 1; break; }
        if (received != AVERROR(EAGAIN)) break;
        if (s->packet->size) {
            int sent = avcodec_send_packet(s->codec, s->packet);
            if (sent != AVERROR(EAGAIN)) av_packet_unref(s->packet);
            if (sent < 0 && sent != AVERROR(EAGAIN)) break;
            continue;
        }
        int read = av_read_frame(s->format, s->packet);
        if (read < 0) { avcodec_send_packet(s->codec, NULL); continue; }
        if (s->packet->stream_index != s->stream) av_packet_unref(s->packet);
    }
    near = closest(s, target);
    if (display && near >= 0) {
        if (draw_picture(s, near) == 0) result = s->cache[near].time;
    }
    return result;
}

JNIEXPORT void JNICALL Java_com_apocketvid_player_NativeScrub_close(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    scrub_free((struct Scrub *)(intptr_t)handle);
}
