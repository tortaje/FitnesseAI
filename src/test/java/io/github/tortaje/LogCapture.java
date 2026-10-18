package io.github.tortaje;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final boolean previousUseParent;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler = new Handler() {
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  };

  LogCapture(Class<?> loggerClass) {
    this(Logger.getLogger(loggerClass.getName()));
  }

  LogCapture(Logger logger) {
    this.logger = logger;
    this.previousLevel = logger.getLevel();
    this.previousUseParent = logger.getUseParentHandlers();
    logger.setLevel(Level.ALL);
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
  }

  boolean has(Level level, String fragment) {
    return records.stream().anyMatch(r ->
      r.getLevel().equals(level) && String.valueOf(r.getMessage()).contains(fragment));
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setLevel(previousLevel);
    logger.setUseParentHandlers(previousUseParent);
  }
}
