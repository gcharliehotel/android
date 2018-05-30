#include <fcntl.h>
#include <sysexits.h>
#include <time.h>
#include <unistd.h>
#include <cassert>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

std::string argv0;

void Fail(int exit_code, const std::string &message) {
  std::cerr << argv0 << ": " << message << std::endl;
  std::exit(exit_code);
}

uint64_t GetTimeNanosconds() {
  struct timespec ts;
  const int rc = clock_gettime(CLOCK_BOOTTIME, &ts);
  if (rc < 0) {
    Fail(EX_IOERR, "clock_gettime failed");
  }
  return ts.tv_sec * 1000000000 + ts.tv_nsec;
}

class ThermalZone {
 public:
  ThermalZone(int n) : n_(n), fd_(-1) {
  }

  ~ThermalZone() {
    if (fd_ >= 0) {
      close(fd_);
    }
  }

  std::string TempPath() const {
    std::ostringstream out;
    out << "/sys/class/thermal/thermal_zone";
    out << n_;
    out << "/temp";
    return out.str();
  }

  void Open() {
    assert(fd_ < 0);
    const auto temp_path = TempPath();
    fd_  = open(temp_path.c_str(), O_RDONLY);
    if (fd_ < 0) {
      Fail(EX_NOINPUT, "Failed to open " + temp_path);
    }
  }

  double ReadTempInCelsius() const {
    assert(fd_ >= 0);
    if (lseek(fd_, SEEK_SET, 0) != 0) {
      return NAN;
    }

    char buf[1024];
    const int n = read(fd_, buf, sizeof(buf) - 1);
    if (n <= 0) {
      return NAN;
    }
    buf[n] = 0;
    const int temp_milli_c = std::stoi(buf);
    return static_cast<double>(temp_milli_c) / 1000.;
  }

  int Number() const { return n_; }

private:
  // Thermal zone number.
  int n_;
  // File descriptor.
  int fd_;
};

std::string Header(const std::vector<ThermalZone> &thermal_zones) {
  std::ostringstream out;
  out << "timestamp_ns";
  for(const auto &thermal_zone : thermal_zones) {
    out << ",temp" << thermal_zone.Number() << "_C";
  }
  return out.str();
}

std::string LogOneRow(std::vector<ThermalZone> &thermal_zones) {
  std::ostringstream out;
  out << GetTimeNanosconds();
  for (auto &thermal_zone : thermal_zones) {
    out << "," << thermal_zone.ReadTempInCelsius();
  }
  return out.str();
}

void Log(std::ostream &out, std::vector<ThermalZone> &thermal_zones) {
  out << Header(thermal_zones) << std::endl;
  while (1) {
    out << LogOneRow(thermal_zones) << std::endl;
    std::this_thread::sleep_for(std::chrono::seconds(1));
  }
}

void Usage() {
  std::cerr << "usage: " << argv0
            << " thermal_zone_num ..." << std::endl;
  std::exit(EX_USAGE);
}

int main(int argc, char **argv) {
  argv0 = argv[0];

  std::vector<ThermalZone> thermal_zones;
  for (int i = 1; i < argc; ++i) {
    int n = std::stoi(argv[i]);
    thermal_zones.emplace_back(ThermalZone(n));
  }

  if (thermal_zones.size() == 0) {
    Fail(EX_NOINPUT, "No thermal_zones specified");
  }

  for (auto &thermal_zone : thermal_zones) {
    thermal_zone.Open();
  }

  Log(std::cout, thermal_zones);
}
