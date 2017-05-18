#include <stdio.h>
#include <signal.h>
#include <getopt.h>
#include <stdlib.h>
#include <time.h>
#include <inttypes.h>

#include <android/log.h>
#include <android/sensor.h>

#include "scope_exit.h"

#define TAG "imu_logger"

#ifndef ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED
#define ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED (16)
#endif

#define LOG(priority, fmt...) \
  __android_log_print(ANDROID_LOG_##priority, TAG, fmt)

#define ASSERT(condition)                                   \
  do {                                                      \
    if (!(condition)) {                                     \
      __android_log_assert(#condition, TAG,                 \
          "failed assertion at %s:%d", __FILE__, __LINE__); \
      exit(1);                                         \
    }                                                       \
  } while (0)


namespace {
constexpr int FrequencyToIntervalUs(int freq_hertz) {
  return 1000000 / freq_hertz;
}
constexpr int IntervalUsToFrequency(int interval_us) {
  return 1000000 / interval_us;
}

volatile bool stop_requested = false;

void signal_handler(int signum) {
  stop_requested = true;
}

void set_signal_handler(int signum) {
  struct sigaction act;
  memset(&act, 0, sizeof(act));
  act.sa_handler = signal_handler;
  ASSERT(sigaction(signum, &act, NULL) == 0);
}

} // namespace

int main()
{
  LOG(INFO, TAG " started");

  ASensorManager *manager = ASensorManager_getInstance();
  ASSERT(manager);

  ASensor const *gyro_sensor =
      ASensorManager_getDefaultSensor(
          manager, ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED);
  if (gyro_sensor) {
    LOG(INFO, "Found uncalibrated gyroscope.");
  } else {
    LOG(WARN, "Did not find uncalibrated gyroscope.");
    gyro_sensor =
      ASensorManager_getDefaultSensor(
          manager, ASENSOR_TYPE_GYROSCOPE);
  }
  ASSERT(gyro_sensor);

  ASensor const *accel_sensor =
      ASensorManager_getDefaultSensor(
          manager, ASENSOR_TYPE_ACCELEROMETER);
  ASSERT(accel_sensor);

  const int kLooperId = 99;
  ALooper *looper =
      ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
  ASSERT(looper);
  ASensorEventQueue *queue =
      ASensorManager_createEventQueue(
          manager, looper, kLooperId, nullptr, nullptr);
  SCOPE_EXIT(ASensorManager_destroyEventQueue(manager, queue));

  int gyro_min_delay = ASensor_getMinDelay(gyro_sensor);
  LOG(INFO, "gyro_min_delay = %d [us] = %d [Hz]",
      gyro_min_delay, IntervalUsToFrequency(gyro_min_delay));
  ASensorEventQueue_enableSensor(queue, gyro_sensor);
  SCOPE_EXIT(ASensorEventQueue_disableSensor(queue, gyro_sensor));
  int gyro_requested_delay = FrequencyToIntervalUs(100);
  if (gyro_requested_delay < gyro_min_delay) {
    LOG(WARN, "gyro requested delay is shorter than min delay");
  }
  LOG(INFO, "gyro_requested_delay = %d [us] = %d [Hz]",
      gyro_requested_delay, IntervalUsToFrequency(gyro_requested_delay));
  ASensorEventQueue_setEventRate(queue, gyro_sensor, gyro_requested_delay);

  int accel_min_delay = ASensor_getMinDelay(accel_sensor);
  LOG(INFO, "accel_min_delay = %d [us] = %d [Hz]",
      accel_min_delay, IntervalUsToFrequency(accel_min_delay));
  ASensorEventQueue_enableSensor(queue, accel_sensor);
  SCOPE_EXIT(ASensorEventQueue_disableSensor(queue, accel_sensor));
  int accel_requested_delay = FrequencyToIntervalUs(125);
  if (accel_requested_delay < accel_min_delay) {
    LOG(WARN, "accel requested delay is shorter than min delay");
  }
  LOG(INFO, "accel_requested_delay = %d [us] = %d [Hz]",
      accel_requested_delay, IntervalUsToFrequency(accel_requested_delay));
  ASensorEventQueue_setEventRate(queue, accel_sensor, accel_requested_delay);

  FILE *accel_out = fopen("/data/local/tmp/accel_data.txt", "w");
  ASSERT(accel_out != NULL);
  SCOPE_EXIT(fclose(accel_out));
  FILE *gyro_out = fopen("/data/local/tmp/gyro_data.txt", "w");
  ASSERT(gyro_out != NULL);
  SCOPE_EXIT(fclose(gyro_out));

  set_signal_handler(SIGINT);
  set_signal_handler(SIGHUP);

  while (!stop_requested) {
    int events;
    int timeoutMillis = 10;
    int result = ALooper_pollAll(timeoutMillis, nullptr, &events, nullptr);
    if (result == ALOOPER_POLL_WAKE) {
      continue;
    } else if (result == ALOOPER_POLL_TIMEOUT) {
      continue;
    } else if (result == ALOOPER_POLL_ERROR) {
      LOG(ERROR, "ALooper error");
      break;
    } else if (result != kLooperId) {
      continue;
    }

    ASensorEvent event;
    while (ASensorEventQueue_getEvents(queue, &event, 1) > 0) {
      struct timespec ts;
      clock_gettime(CLOCK_MONOTONIC, &ts);
      switch (event.type) {
        case ASENSOR_TYPE_ACCELEROMETER:
          fprintf(accel_out,
                  "%" PRIu64 " %a %a %a\n",
                  event.timestamp,
                  event.vector.x,
                  event.vector.y,
                  event.vector.z);
          break;
        case ASENSOR_TYPE_GYROSCOPE:
        case ASENSOR_TYPE_GYROSCOPE_UNCALIBRATED:
          fprintf(gyro_out,
                  "%" PRIu64 " %a %a %a\n",
                  event.timestamp,
                  event.vector.x,
                  event.vector.y,
                  event.vector.z);
          break;
        default:
          break;
      }
    }
  }

  LOG(INFO, TAG " exiting");
  return 0;
}
