#ifndef _MEDIA_STATUS_HELPER_H_
#define _MEDIA_STATUS_HELPER_H_

#include <cstdlib>

#include <android/log.h>
#include <media/NdkMediaError.h>

#define ASSERT_MEDIA_STATUS_OK(tag, status)                                  \
  do {                                                                       \
    auto s = (status);                                                       \
    if (s != AMEDIA_OK) {                                                    \
      __android_log_print(ANDROID_LOG_ERROR, tag,                            \
                          "%s:%d: media_status = %s",                        \
                          __FILE__, __LINE__, MediaStatusToString(s));       \
      std::exit(1);                                                          \
    }                                                                        \
  } while (0)

const char *MediaStatusToString(const media_status_t status);

#endif
