// Copyright (c) YugaByte, Inc.

#include <string>
#include <gtest/gtest.h>
#include <glog/logging.h>
#include "yb/rocksutil/yb_rocksdb_logger.h"
#include "yb/util/random.h"
#include "yb/util/random_util.h"
#include "yb/util/test_util.h"
#include "yb/util/tostring.h"

using yb::YBRocksDBLogger;
using rocksdb::InfoLogLevel;

namespace yb {

namespace {
  static constexpr char kPrefix[] = "prefix: ";
} // namespace

class YBRocksDBLoggerTest: public ::testing::Test, public google::base::Logger {
 protected:
  YBRocksDBLoggerTest() : rocksdb_logger_(kPrefix) {
  }

  void SetUp() override {
    google::InitGoogleLogging("");
    old_logger_ = google::base::GetLogger(google::GLOG_WARNING);
    google::base::SetLogger(google::GLOG_WARNING, this);
  }

  void TearDown() override {
    google::base::SetLogger(google::GLOG_WARNING, old_logger_);
  }

 public:
  void Write(bool force_flush, time_t timestamp, const char *message, int message_len) override {
    log_.append(message, message_len);
  }

  void Flush() override {
  }

  google::uint32 LogSize() override {
    return 0;
  }

  YBRocksDBLogger rocksdb_logger_;
  std::string log_;

 private:
  google::base::Logger *old_logger_;
};

TEST_F(YBRocksDBLoggerTest, LogvWithContextSmall) {
  Random r(SeedRandom());

  static constexpr size_t reserve_for_prefix = 100;
  static constexpr size_t lengths_to_test[] = {512, 1024, 32768, 65536, 131072};

  for (const size_t length : lengths_to_test) {
    const std::string text = RandomHumanReadableString(length, &r);
    RWARN(&rocksdb_logger_, "%s %d", text.c_str(), length);

    const std::string trimmed_log = util::RightTrimStr(log_);
    const size_t prefix_pos = trimmed_log.find(kPrefix);
    ASSERT_NE(prefix_pos, std::string::npos);
    const std::string log_after_prefix = trimmed_log.substr(prefix_pos + sizeof(kPrefix) - 1);
    const std::string formatted_text = text + " " + util::ToString(length);
    if (length > google::LogMessage::kMaxLogMessageLen - reserve_for_prefix) {
      // Log part after prefix should be a substring of what we've logged.
      ASSERT_STR_CONTAINS(formatted_text, log_after_prefix);
    } else {
      ASSERT_EQ(formatted_text, log_after_prefix);
    }

    log_.clear();
  }
}

} // namespace yb
