/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.testcommon.virtualthread;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * Provides Hamcrest matchers for testing Java 21 Virtual Thread functionality.
 * 
 * @since 3.60
 */
public class VirtualThreadMatchers
{
  /**
   * Matches if the thread is a virtual thread.
   */
  public static Matcher<Thread> isVirtualThread() {
    return new TypeSafeMatcher<Thread>() {
      @Override
      protected boolean matchesSafely(Thread thread) {
        return thread.isVirtual();
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("a virtual thread");
      }

      @Override
      protected void describeMismatchSafely(Thread thread, Description mismatchDescription) {
        mismatchDescription.appendText("was a platform thread: ").appendValue(thread);
      }
    };
  }

  /**
   * Matches if the virtual thread is pinned to its carrier thread.
   * 
   * Note: This uses reflection to access internal JDK APIs and may break in future JDK versions.
   */
  public static Matcher<Thread> isPinned() {
    return new TypeSafeDiagnosingMatcher<Thread>() {
      @Override
      protected boolean matchesSafely(Thread thread, Description mismatchDescription) {
        if (!thread.isVirtual()) {
          mismatchDescription.appendText("was not a virtual thread: ").appendValue(thread);
          return false;
        }
        
        try {
          // Access the internal isPinned method via reflection
          Method isPinnedMethod = thread.getClass().getDeclaredMethod("isPinned");
          isPinnedMethod.setAccessible(true);
          return (Boolean) isPinnedMethod.invoke(thread);
        }
        catch (Exception e) {
          mismatchDescription.appendText("could not determine pinning status: ").appendValue(e.getMessage());
          return false;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("a pinned virtual thread");
      }
    };
  }

  /**
   * Matches if the virtual thread is not pinned to its carrier thread.
   */
  public static Matcher<Thread> isNotPinned() {
    return new TypeSafeDiagnosingMatcher<Thread>() {
      @Override
      protected boolean matchesSafely(Thread thread, Description mismatchDescription) {
        Matcher<Thread> pinnedMatcher = isPinned();
        if (!thread.isVirtual()) {
          mismatchDescription.appendText("was not a virtual thread: ").appendValue(thread);
          return false;
        }
        
        if (pinnedMatcher.matches(thread)) {
          mismatchDescription.appendText("was pinned");
          return false;
        }
        
        return true;
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("a virtual thread that is not pinned");
      }
    };
  }

  /**
   * Matches if the virtual thread is mounted on a carrier thread.
   * 
   * Note: This uses reflection to access internal JDK APIs and may break in future JDK versions.
   */
  public static Matcher<Thread> hasCarrierThread() {
    return new TypeSafeDiagnosingMatcher<Thread>() {
      @Override
      protected boolean matchesSafely(Thread thread, Description mismatchDescription) {
        if (!thread.isVirtual()) {
          mismatchDescription.appendText("was not a virtual thread: ").appendValue(thread);
          return false;
        }
        
        try {
          // Access the internal getCarrierThread method via reflection
          Method getCarrierThreadMethod = thread.getClass().getDeclaredMethod("getCarrierThread");
          getCarrierThreadMethod.setAccessible(true);
          Thread carrierThread = (Thread) getCarrierThreadMethod.invoke(thread);
          
          if (carrierThread == null) {
            mismatchDescription.appendText("was not mounted on a carrier thread");
            return false;
          }
          
          return true;
        }
        catch (Exception e) {
          mismatchDescription.appendText("could not determine carrier thread: ").appendValue(e.getMessage());
          return false;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("a virtual thread mounted on a carrier thread");
      }
    };
  }

  /**
   * Matches if the callable executes within the specified time limit.
   * Useful for testing virtual thread performance characteristics.
   */
  public static <T> Matcher<Callable<T>> executesWithinMillis(long milliseconds) {
    return new TypeSafeDiagnosingMatcher<Callable<T>>() {
      @Override
      protected boolean matchesSafely(Callable<T> callable, Description mismatchDescription) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          long startTime = System.nanoTime();
          Future<T> future = executor.submit(callable);
          
          try {
            future.get(milliseconds * 2, TimeUnit.MILLISECONDS); // Allow double the expected time for safety
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            
            if (duration > milliseconds) {
              mismatchDescription.appendText("took ").appendValue(duration)
                  .appendText(" ms, which exceeds the limit of ").appendValue(milliseconds).appendText(" ms");
              return false;
            }
            
            return true;
          }
          catch (ExecutionException e) {
            mismatchDescription.appendText("threw an exception: ").appendValue(e.getCause());
            return false;
          }
          catch (java.util.concurrent.TimeoutException e) {
            mismatchDescription.appendText("did not complete within the timeout period");
            return false;
          }
        }
        catch (Exception e) {
          mismatchDescription.appendText("encountered an error: ").appendValue(e);
          return false;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("executes within ").appendValue(milliseconds).appendText(" ms");
      }
    };
  }

  /**
   * Matches if the callable executes within the specified duration.
   * Useful for testing virtual thread performance characteristics.
   */
  public static <T> Matcher<Callable<T>> executesWithin(Duration duration) {
    return executesWithinMillis(duration.toMillis());
  }
}