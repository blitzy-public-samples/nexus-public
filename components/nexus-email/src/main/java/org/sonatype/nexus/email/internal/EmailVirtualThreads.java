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
package org.sonatype.nexus.email.internal;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.internal.MDCUtils;

import com.google.common.base.Preconditions;

/**
 * Utility class for managing Java 21 Virtual Threads specifically for email operations.
 *
 * @since 3.60
 */
public final class EmailVirtualThreads
{
  private static final Logger log = LoggerFactory.getLogger(EmailVirtualThreads.class);
  
  private static final String EMAIL_THREAD_PREFIX = "nexus-email-vthread";
  
  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
  
  private static final LongAdder ACTIVE_THREADS = new LongAdder();
  
  private static final LongAdder COMPLETED_THREADS = new LongAdder();
  
  private static final LongAdder FAILED_THREADS = new LongAdder();
  
  private static final Map<String, Thread> TRACKED_THREADS = new ConcurrentHashMap<>();
  
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /**
   * Virtual thread executor service for email operations.
   * Uses the built-in factory method from Java 21's Executors class.
   */
  private static final ExecutorService EMAIL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private EmailVirtualThreads() {
    // Prevent instantiation of utility class
  }

  /**
   * Creates and starts a new virtual thread for an email operation.
   *
   * @param name the base name for the thread (will be prefixed)
   * @param runnable the task to execute
   * @return the created thread
   */
  public static Thread newVirtualThread(String name, Runnable runnable) {
    Preconditions.checkNotNull(name, "Thread name cannot be null");
    Preconditions.checkNotNull(runnable, "Runnable cannot be null");
    
    String threadName = String.format("%s-%s-%d", EMAIL_THREAD_PREFIX, name, THREAD_COUNTER.incrementAndGet());
    
    // Capture the current MDC context to propagate to the virtual thread
    Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    Runnable wrappedTask = () -> {
      ACTIVE_THREADS.increment();
      TRACKED_THREADS.put(threadName, Thread.currentThread());
      
      try {
        // Restore MDC context in the virtual thread
        if (mdcContext != null) {
          MDCUtils.setContextMap(mdcContext);
        }
        
        // Execute the actual task
        runnable.run();
        
        COMPLETED_THREADS.increment();
      }
      catch (Exception e) {
        FAILED_THREADS.increment();
        log.error("Error in email virtual thread {}: {}", threadName, e.getMessage(), e);
        throw e;
      }
      finally {
        ACTIVE_THREADS.decrement();
        TRACKED_THREADS.remove(threadName);
        MDC.clear();
      }
    };
    
    Thread thread = Thread.ofVirtual().name(threadName).unstarted(wrappedTask);
    return thread;
  }

  /**
   * Starts a virtual thread for an email operation and returns immediately.
   *
   * @param name the base name for the thread
   * @param runnable the task to execute
   * @return the started thread
   */
  public static Thread startVirtualThread(String name, Runnable runnable) {
    Thread thread = newVirtualThread(name, runnable);
    thread.start();
    return thread;
  }

  /**
   * Submits an email task to the virtual thread executor.
   *
   * @param name the base name for the operation
   * @param runnable the task to execute
   */
  public static void submitEmailTask(String name, Runnable runnable) {
    Preconditions.checkNotNull(name, "Task name cannot be null");
    Preconditions.checkNotNull(runnable, "Runnable cannot be null");
    
    String threadName = String.format("%s-%s-%d", EMAIL_THREAD_PREFIX, name, THREAD_COUNTER.incrementAndGet());
    
    // Capture the current MDC context to propagate to the virtual thread
    Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    EMAIL_EXECUTOR.submit(() -> {
      Thread.currentThread().setName(threadName);
      ACTIVE_THREADS.increment();
      TRACKED_THREADS.put(threadName, Thread.currentThread());
      
      try {
        // Restore MDC context in the virtual thread
        if (mdcContext != null) {
          MDCUtils.setContextMap(mdcContext);
        }
        
        // Execute the actual task
        runnable.run();
        
        COMPLETED_THREADS.increment();
      }
      catch (Exception e) {
        FAILED_THREADS.increment();
        log.error("Error in email virtual thread {}: {}", threadName, e.getMessage(), e);
        throw e;
      }
      finally {
        ACTIVE_THREADS.decrement();
        TRACKED_THREADS.remove(threadName);
        MDC.clear();
      }
    });
  }

  /**
   * Sends an email using a virtual thread.
   *
   * @param email the email to send
   * @param emailSender the function that will actually send the email
   */
  public static void sendEmailAsync(Email email, Consumer<Email> emailSender) {
    Preconditions.checkNotNull(email, "Email cannot be null");
    Preconditions.checkNotNull(emailSender, "Email sender cannot be null");
    
    String subject = email.getSubject();
    String threadName = "send-" + (subject != null ? subject.replaceAll("\\s+", "-") : "email");
    
    submitEmailTask(threadName, () -> {
      try {
        emailSender.accept(email);
        log.debug("Successfully sent email: {}", subject);
      }
      catch (Exception e) {
        log.error("Failed to send email: {}", subject, e);
      }
    });
  }

  /**
   * Sends an email using a virtual thread with the FakeAlmightySubject.
   *
   * @param email the email to send
   * @param emailSender the function that will actually send the email
   */
  public static void sendEmailAsyncWithSystemSubject(Email email, Consumer<Email> emailSender) {
    Preconditions.checkNotNull(email, "Email cannot be null");
    Preconditions.checkNotNull(emailSender, "Email sender cannot be null");
    
    String subject = email.getSubject();
    String threadName = "send-system-" + (subject != null ? subject.replaceAll("\\s+", "-") : "email");
    
    // Capture the current MDC context to propagate to the virtual thread
    Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    EMAIL_EXECUTOR.submit(() -> {
      Thread.currentThread().setName(threadName);
      ACTIVE_THREADS.increment();
      TRACKED_THREADS.put(threadName, Thread.currentThread());
      
      try {
        // Restore MDC context in the virtual thread
        if (mdcContext != null) {
          MDCUtils.setContextMap(mdcContext);
        }
        
        // Use FakeAlmightySubject for system operations
        Subject.Builder subjectBuilder = new Subject.Builder();
        subjectBuilder.principals(FakeAlmightySubject.TASK_SUBJECT.getPrincipals());
        subjectBuilder.authenticated(true);
        Subject fakeSubject = subjectBuilder.buildSubject();
        
        fakeSubject.execute(() -> {
          try {
            emailSender.accept(email);
            log.debug("Successfully sent system email: {}", subject);
          }
          catch (Exception e) {
            log.error("Failed to send system email: {}", subject, e);
            throw new RuntimeException("Email sending failed", e);
          }
        });
        
        COMPLETED_THREADS.increment();
      }
      catch (Exception e) {
        FAILED_THREADS.increment();
        log.error("Error in email virtual thread {}: {}", threadName, e.getMessage(), e);
      }
      finally {
        ACTIVE_THREADS.decrement();
        TRACKED_THREADS.remove(threadName);
        MDC.clear();
      }
    });
  }

  /**
   * Sends a verification email using a virtual thread.
   *
   * @param address the email address to send verification to
   * @param verificationSender the function that will send the verification email
   */
  public static void sendVerificationEmailAsync(String address, Consumer<String> verificationSender) {
    Preconditions.checkNotNull(address, "Email address cannot be null");
    Preconditions.checkNotNull(verificationSender, "Verification sender cannot be null");
    
    String threadName = "verify-" + address.replaceAll("[@\\.]", "-");
    
    submitEmailTask(threadName, () -> {
      try {
        verificationSender.accept(address);
        log.debug("Successfully sent verification email to: {}", address);
      }
      catch (EmailException e) {
        log.error("Failed to send verification email to: {}", address, e);
      }
      catch (Exception e) {
        log.error("Unexpected error sending verification email to: {}", address, e);
      }
    });
  }

  /**
   * Gets the current number of active email virtual threads.
   *
   * @return the count of active threads
   */
  public static int getActiveThreadCount() {
    return ACTIVE_THREADS.intValue();
  }

  /**
   * Gets the total number of completed email virtual threads.
   *
   * @return the count of completed threads
   */
  public static long getCompletedThreadCount() {
    return COMPLETED_THREADS.sum();
  }

  /**
   * Gets the total number of failed email virtual threads.
   *
   * @return the count of failed threads
   */
  public static long getFailedThreadCount() {
    return FAILED_THREADS.sum();
  }

  /**
   * Attempts to cancel all tracked email virtual threads.
   */
  public static void cancelAllThreads() {
    log.info("Cancelling all email virtual threads: {} active", ACTIVE_THREADS.sum());
    TRACKED_THREADS.forEach((name, thread) -> {
      try {
        log.debug("Interrupting email thread: {}", name);
        thread.interrupt();
      }
      catch (Exception e) {
        log.warn("Failed to interrupt email thread {}: {}", name, e.getMessage());
      }
    });
  }
  
  /**
   * Gets a summary of all currently tracked email virtual threads.
   *
   * @return a string containing thread information
   */
  public static String getThreadsSummary() {
    return TRACKED_THREADS.entrySet().stream()
        .map(entry -> String.format("%s [%s]", entry.getKey(), entry.getValue().getState()))
        .collect(Collectors.joining("\n"));
  }

  /**
   * Shuts down the email executor service.
   *
   * @param awaitTermination whether to wait for tasks to complete
   */
  public static void shutdown(boolean awaitTermination) {
    if (awaitTermination) {
      log.info("Shutting down email virtual thread executor and awaiting termination");
      EMAIL_EXECUTOR.shutdown();
    }
    else {
      log.info("Shutting down email virtual thread executor immediately");
      EMAIL_EXECUTOR.shutdownNow();
    }
  }
  
  /**
   * Executes a task with a timeout using a virtual thread.
   * If the task doesn't complete within the timeout, the thread will be interrupted.
   *
   * @param <T> the type of result returned by the task
   * @param name the base name for the thread
   * @param task the task to execute
   * @param timeout the maximum duration to wait for completion
   * @return the result of the task, or null if it timed out or failed
   */
  @Nullable
  public static <T> T executeWithTimeout(String name, Supplier<T> task, Duration timeout) {
    Preconditions.checkNotNull(name, "Thread name cannot be null");
    Preconditions.checkNotNull(task, "Task cannot be null");
    Preconditions.checkNotNull(timeout, "Timeout cannot be null");
    
    String threadName = String.format("%s-%s-%d", EMAIL_THREAD_PREFIX, name, THREAD_COUNTER.incrementAndGet());
    
    // Capture the current MDC context to propagate to the virtual thread
    Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    final Thread[] workerThread = new Thread[1];
    final T[] result = (T[]) new Object[1];
    
    Thread monitorThread = Thread.ofVirtual().name(threadName + "-monitor").start(() -> {
      try {
        workerThread[0] = Thread.ofVirtual().name(threadName).start(() -> {
          try {
            // Restore MDC context in the virtual thread
            if (mdcContext != null) {
              MDCUtils.setContextMap(mdcContext);
            }
            
            result[0] = task.get();
          }
          catch (Exception e) {
            log.error("Error executing task in virtual thread {}: {}", threadName, e.getMessage(), e);
          }
          finally {
            MDC.clear();
          }
        });
        
        workerThread[0].join(timeout.toMillis());
        
        if (workerThread[0].isAlive()) {
          log.warn("Task in thread {} timed out after {}, interrupting", threadName, timeout);
          workerThread[0].interrupt();
        }
      }
      catch (InterruptedException e) {
        log.warn("Monitor thread for {} was interrupted", threadName);
        if (workerThread[0] != null && workerThread[0].isAlive()) {
          workerThread[0].interrupt();
        }
        Thread.currentThread().interrupt();
      }
    });
    
    try {
      monitorThread.join();
    }
    catch (InterruptedException e) {
      log.warn("Interrupted while waiting for monitor thread");
      Thread.currentThread().interrupt();
    }
    
    return result[0];
  }
  
  /**
   * Executes a task with the default timeout using a virtual thread.
   *
   * @param <T> the type of result returned by the task
   * @param name the base name for the thread
   * @param task the task to execute
   * @return the result of the task, or null if it timed out or failed
   */
  @Nullable
  public static <T> T executeWithTimeout(String name, Supplier<T> task) {
    return executeWithTimeout(name, task, DEFAULT_TIMEOUT);
  }
}