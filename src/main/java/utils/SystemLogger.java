package utils;

import org.apache.log4j.Logger;

public final class SystemLogger {

  private static final Logger INSTANCE = Logger.getLogger("system_logger");

  private SystemLogger() {
    throw new UnsupportedOperationException("Class cannot be instantiated");
  }

  public static Logger getInstance() {
    return INSTANCE;
  }
}
