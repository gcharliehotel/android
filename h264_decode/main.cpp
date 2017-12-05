#include <fcntl.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <memory>

#include <android/log.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include "media_status_helper.h"

#define TAG "h264_decode"
#define EXTRACTOR

#define ALOG(priority, fmt...) \
  __android_log_print(ANDROID_LOG_##priority, TAG, fmt)

#define LOG(priority, fmt...) \
  do { fprintf(stderr, fmt); fputc('\n', stderr); } while (0)

#define AASSERT(condition)                                   \
  do {                                                      \
    if (!(condition)) {                                     \
      __android_log_assert(#condition, TAG,                 \
          "failed assertion at %s:%d", __FILE__, __LINE__); \
      exit(1);                                              \
    }                                                       \
  } while (0)

#define ASSERT(condition)                                   \
  do {                                                      \
    if (!(condition)) {                                     \
      fprintf(stderr,                                       \
        "failed assertion at %s:%d\n", __FILE__, __LINE__); \
      exit(1);                                              \
    }                                                       \
  } while (0)

#define DEFINE_DELETE_FUNCTOR(type) \
  struct _Delete##type##Functor { \
  void operator()(type *p) const { type##_delete(p); } \
  }

#define AUNIQUE_POINTER(type) \
  std::unique_ptr<type, _Delete##type##Functor>

DEFINE_DELETE_FUNCTOR(AImage);
DEFINE_DELETE_FUNCTOR(AImageReader);
DEFINE_DELETE_FUNCTOR(AMediaCodec);
DEFINE_DELETE_FUNCTOR(AMediaExtractor);
DEFINE_DELETE_FUNCTOR(AMediaFormat);

const uint8_t csd_0[] = {
  /* csd-0[0] = */ 0x00,
  /* csd-0[1] = */ 0x00,
  /* csd-0[2] = */ 0x00,
  /* csd-0[3] = */ 0x01,
  /* csd-0[4] = */ 0x67,
  /* csd-0[5] = */ 0x42,
  /* csd-0[6] = */ 0x80,
  /* csd-0[7] = */ 0x0A,
  /* csd-0[8] = */ 0xDA,
  /* csd-0[9] = */ 0x00,
  /* csd-0[10] = */ 0xF0,
  /* csd-0[11] = */ 0x03,
  /* csd-0[12] = */ 0xC6,
  /* csd-0[13] = */ 0x94,
  /* csd-0[14] = */ 0x82,
  /* csd-0[15] = */ 0x83,
  /* csd-0[16] = */ 0x02,
  /* csd-0[17] = */ 0x83,
  /* csd-0[18] = */ 0x68,
  /* csd-0[19] = */ 0x50,
  /* csd-0[20] = */ 0x9A,
  /* csd-0[21] = */ 0x80,
};

const uint8_t csd_1[] = {
  /* csd-0[0] = */ 0x00,
  /* csd-0[1] = */ 0x00,
  /* csd-0[2] = */ 0x00,
  /* csd-0[3] = */ 0x01,
  /* csd-0[4] = */ 0x68,
  /* csd-0[5] = */ 0xCE,
  /* csd-0[6] = */ 0x06,
  /* csd-0[7] = */ 0xE2,
};


class VideoToImagesDecoder {
 public:
  VideoToImagesDecoder(const std::string& path,
                       const std::string& mime_type,
                       int32_t width, int32_t height, int32_t format,
                       int32_t max_images) {
    fd_ = open(path.c_str(), O_RDONLY);
    ASSERT(fd_ >= 0);
    off_t end = lseek(fd_, 0, SEEK_END);
    (void) lseek(fd_, 0, SEEK_SET);
    LOG(INFO, "file %s opened, end=%ld", path.c_str(), end);

#ifdef EXTRACTOR
    LOG(INFO, "creating extractor");
    extractor_.reset(AMediaExtractor_new());
    ASSERT_MEDIA_STATUS_OK(
        TAG, AMediaExtractor_setDataSourceFd(extractor_.get(), fd_, 0, end));

    media_format_.reset(AMediaExtractor_getTrackFormat(extractor_.get(), 0));

    uint8_t *data;
    size_t size;
    AMediaFormat_getBuffer(media_format_.get(), "csd-0",
                           (void **) &data, &size);
    LOG(INFO, "csd-0, size=%ld", size);
    for (size_t i = 0; i < size; i++) {
      LOG(INFO, "csd-0[%ld] = 0x%02X", i, data[i]);
    }
    AMediaFormat_getBuffer(media_format_.get(), "csd-1",
                           (void **) &data, &size);
    LOG(INFO, "csd-1, size=%ld", size);
    for (size_t i = 0; i < size; i++) {
      LOG(INFO, "csd-0[%ld] = 0x%02X", i, data[i]);
    }

    /*
   format=mime: string(video/avc), durationUs: int64(32000000), track-id: int32(1), width: int32(3840), height: int32(1920), rotation-degrees: int32(0), max-input-size: int32(11059201), frame-rate: int32(25), profile: int32(1), level: int32(1), csd-0: data, csd-1: data, file-format: string(video/mp4)}

csd-0, size=22
csd-0[0] = 0x00
csd-0[1] = 0x00
csd-0[2] = 0x00
csd-0[3] = 0x01
csd-0[4] = 0x67
csd-0[5] = 0x42
csd-0[6] = 0x80
csd-0[7] = 0x0A
csd-0[8] = 0xDA
csd-0[9] = 0x00
csd-0[10] = 0xF0
csd-0[11] = 0x03
csd-0[12] = 0xC6
csd-0[13] = 0x94
csd-0[14] = 0x82
csd-0[15] = 0x83
csd-0[16] = 0x02
csd-0[17] = 0x83
csd-0[18] = 0x68
csd-0[19] = 0x50
csd-0[20] = 0x9A
csd-0[21] = 0x80
csd-1, size=8
csd-0[0] = 0x00
csd-0[1] = 0x00
csd-0[2] = 0x00
csd-0[3] = 0x01
csd-0[4] = 0x68
csd-0[5] = 0xCE
csd-0[6] = 0x06
csd-0[7] = 0xE2

    */
#else
    media_format_.reset(AMediaFormat_new());
    AMediaFormat_setString(media_format_.get(), AMEDIAFORMAT_KEY_MIME,
                           mime_type.c_str());
    AMediaFormat_setInt32(media_format_.get(), AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(media_format_.get(), AMEDIAFORMAT_KEY_HEIGHT, height);
#endif
    LOG(INFO, "format=%s", AMediaFormat_toString(media_format_.get()));

    const char *format_mime_type = nullptr;
    AMediaFormat_getString(media_format_.get(), AMEDIAFORMAT_KEY_MIME,
                           &format_mime_type);
    codec_.reset(AMediaCodec_createDecoderByType(format_mime_type));
    ASSERT(codec_ != nullptr);

    AImageReader *p;
    ASSERT_MEDIA_STATUS_OK(
        TAG, AImageReader_new(width, height, format, max_images,
                              &p));
    image_reader_.reset(p);

    AImageReader_ImageListener image_listener;
    image_listener.context = this;
    image_listener.onImageAvailable =
        [](void *context, AImageReader *image_reader) {
      reinterpret_cast<VideoToImagesDecoder *>(context)->OnImageAvailable(image_reader);
    };

    ASSERT_MEDIA_STATUS_OK(
        TAG, AMediaCodec_configure(codec_.get(), media_format_.get(), window_, nullptr, 0));
  }

  void Run(/* some consumer of images */) {
    LOG(INFO, "Run");
    LOG(INFO, "codec start");
    AMediaCodec_start(codec_.get());
    bool saw_input_eos = false;
    bool saw_output_eos = false;
    uint64_t frame_count = 0;
    while (!saw_output_eos) {
      if (!saw_input_eos) {
        ssize_t buf_index =
            AMediaCodec_dequeueInputBuffer(codec_.get(), default_timeout_us);
        if (buf_index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
          continue;
        } else if (buf_index < 0) {
          LOG(INFO, "Pumping input, got unexpected index %ld < 0", buf_index);
        }
        ASSERT(buf_index >= 0);
        size_t size;
        uint8_t *buf =
            AMediaCodec_getInputBuffer(codec_.get(), buf_index, &size);
        ssize_t n_read = read(fd_, buf, size);
        LOG(INFO, "Read %ld data", n_read);
        ASSERT(n_read >= 0);
        if (n_read == 0) {
          LOG(INFO, "input EOF");
          saw_input_eos = true;
        }
        ASSERT_MEDIA_STATUS_OK(
            TAG, AMediaCodec_queueInputBuffer(codec_.get(),
                                              buf_index,  0,
                                              n_read, 0,
                                              (n_read == 0) ?
                                              AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM : 0));
      }
      if (!saw_output_eos) {
        AMediaCodecBufferInfo info;
        ssize_t buf_index = AMediaCodec_dequeueOutputBuffer(codec_.get(), &info, default_timeout_us);
        if (buf_index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
          continue;
        } else if (buf_index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
          LOG(INFO, "output buffers changed");
          continue;
        } else if (buf_index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
          LOG(INFO, "output format changed");
          continue;
        } else if (buf_index < 0) {
          LOG(ERROR, "got output buf index of %ld", buf_index);
          continue;
        }
        ASSERT(buf_index >= 0);
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
          saw_output_eos = true;
        }
        ASSERT_MEDIA_STATUS_OK(
            TAG, AMediaCodec_releaseOutputBuffer(codec_.get(), buf_index, true));
        ++frame_count;
      }
    }
    AMediaCodec_stop(codec_.get());
  }

  virtual ~VideoToImagesDecoder() {
    // image_reader_ cleans up window_
    if (fd_ >= 0) {
      close(fd_);
    }
  }

  void OnImageAvailable(AImageReader *image_reader) {
    LOG(INFO, "OnImageAvailable");
    AImage *image;
    media_status_t status = AImageReader_acquireNextImage(image_reader, &image);
    if (status == AMEDIA_OK) {
      LOG(INFO, "OnImageAvailable: got image");
      AImage_delete(image);
    } else {
      LOG(INFO, "OnImageAvailable: error: %s", MediaStatusToString(status));
    }
  }

 private:
  int fd_ = -1;
#ifdef EXTRACTOR
  AUNIQUE_POINTER(AMediaExtractor) extractor_;
#endif
  AUNIQUE_POINTER(AMediaCodec) codec_;
  AUNIQUE_POINTER(AImageReader) image_reader_;
  ANativeWindow *window_ = nullptr;
  AUNIQUE_POINTER(AMediaFormat) media_format_;
  const int64_t default_timeout_us = 10000;
};


int main()
{
  LOG(INFO, "started");

#ifdef EXTRACTOR
  const std::string path("/data/local/tmp/test.mp4");
  const std::string mime_type("doesn't/matter");
#else
  const std::string path("/data/local/tmp/test.h264");
  const std::string mime_type("video/avc");
#endif
  const int32_t width = 3840;
  const int32_t height = 1920;
  const int32_t format = AIMAGE_FORMAT_YUV_420_888;
  const int32_t max_images = 2;

  std::unique_ptr<VideoToImagesDecoder> decoder;
  decoder.reset(new VideoToImagesDecoder(path, mime_type,
                                         width, height,
                                         format, max_images));

  decoder->Run();

  while (1) {
    sleep(5);
  }
}

/*
 * Local Variables:
 * compile-command: "make -k && adb push \
 *   libs/arm64-v8a/h264_decode /data/local/tmp"
 * End:
 */
