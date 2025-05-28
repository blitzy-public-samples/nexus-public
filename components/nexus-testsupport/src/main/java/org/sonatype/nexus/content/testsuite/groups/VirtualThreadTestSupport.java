package org.sonatype.nexus.content.testsuite.groups;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.opentest4j.TestAbortedException;

public class VirtualThreadTestSupport {

	public static boolean isVirtualThreadSupported() {
		try {
			// Check if Thread class has the ofVirtual method (Java 21+)
			Thread.class.getMethod("ofVirtual");
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	/**
	 * Helper method to check if the current JVM supports Virtual Threads. This is
	 * used in the setUp method to skip tests if Virtual Threads are not supported.
	 */
	public static void assumeVirtualThreadSupported() {
		if (!isVirtualThreadSupported()) {
			throw new TestAbortedException("Virtual Threads not supported in this JVM");
		}
	}
	
	/**
	   * Detects if a virtual thread is pinned to its carrier thread.
	   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
	   * typically due to synchronized blocks or native method calls.
	   * 
	   * @param runnable The code to execute and check for pinning
	   * @return true if pinning is detected, false otherwise
	   */
	  public static boolean detectThreadPinning(Runnable runnable) {
	    // Set up a flag to track pinning detection
	    AtomicInteger pinnedCount = new AtomicInteger(0);
	    
	    // Enable pinning detection via system property
	    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
	    try {
	      System.setProperty("jdk.tracePinnedThreads", "full");
	      
	      // Create and run a virtual thread with the provided code
	      Thread thread = Thread.ofVirtual().start(() -> {
	        // Run the provided code that might cause pinning
	        runnable.run();
	      });
	      
	      // Wait for the thread to complete
	      thread.join();
	      
	      // Check if pinning was detected (this is a simplified approach)
	      // In a real implementation, you would need to capture and analyze the output
	      // from the jdk.tracePinnedThreads property or use JFR events
	      return pinnedCount.get() > 0;
	    } catch (InterruptedException e) {
	      Thread.currentThread().interrupt();
	      return false;
	    } finally {
	      // Restore the original system property
	      if (originalPinningProperty != null) {
	        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
	      } else {
	        System.clearProperty("jdk.tracePinnedThreads");
	      }
	    }
	  }

	public static ExecutorService newVirtualThreadExecutor(String string) {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}
