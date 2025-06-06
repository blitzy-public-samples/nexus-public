package com.amazonaws.services.s3;

import java.util.concurrent.Executors;

public class VirtualThreadUtil {
  /**
   * Checks if virtual threads are enabled.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadEnabled() {
    try {
      Executors.newVirtualThreadPerTaskExecutor().close();
      return true;
    } catch (UnsupportedOperationException e) {
      return false;
    }
  }
}
