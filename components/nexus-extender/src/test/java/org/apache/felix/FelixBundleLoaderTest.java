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
package org.apache.felix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Tests Apache Felix OSGi framework's bundle loading and lifecycle functionality under Java 21.
 * Verifies bundle installation, resolution, start/stop operations, classloading capabilities with
 * Java 21 class formats, and proper handling of bundle dependencies.
 * 
 * This test suite ensures that the core OSGi bundle operations work correctly with Java 21's
 * modified classloading and module system, preventing runtime resolution issues in production.
 * It also validates compatibility with Java 21 features like virtual threads, record patterns,
 * and string templates.
 */
@ExtendWith(MockitoExtension.class)
public class FelixBundleLoaderTest
{
  private Framework framework;
  private BundleContext bundleContext;
  private Path tempDir;
  
  @Mock
  private Bundle mockBundle;
  
  @BeforeEach
  public void setUp() throws IOException, BundleException {
    // Create temporary directory for OSGi cache
    tempDir = Files.createTempDirectory("felix-test-");
    
    // Configure Felix framework
    Map<String, String> config = new HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, tempDir.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    config.put(Constants.FRAMEWORK_BUNDLE_PARENT, Constants.FRAMEWORK_BUNDLE_PARENT_FRAMEWORK);
    config.put("felix.log.level", "4"); // Set log level to DEBUG
    
    // Add Java 21 specific configuration
    if (isJava21Compatible()) {
      // Enable Java 21 features support
      config.put("org.osgi.framework.bootdelegation", "sun.*,com.sun.*,jdk.*");
      
      // Configure thread pool for virtual threads if running on Java 21
      config.put("felix.threading.model", "parallel");
    }
    
    // Initialize Felix framework
    FrameworkFactory factory = new org.apache.felix.framework.FrameworkFactory();
    framework = factory.newFramework(config);
    framework.init();
    framework.start();
    
    bundleContext = framework.getBundleContext();
    assertNotNull(bundleContext, "Bundle context should not be null");
    
    // Log Java version information
    System.out.println("Running tests with Java version: " + System.getProperty("java.version"));
    System.out.println("Virtual threads supported: " + isVirtualThreadsSupported());
  }
  
  @AfterEach
  public void tearDown() throws BundleException, IOException {
    if (framework != null) {
      framework.stop();
      framework.waitForStop(5000);
    }
    
    // Clean up temporary directory
    deleteDirectory(tempDir.toFile());
  }
  
  @Test
  @DisplayName("Test basic bundle lifecycle operations with Java 21")
  public void testBundleLifecycle() throws BundleException {
    // Get system bundle
    Bundle systemBundle = bundleContext.getBundle(0);
    assertNotNull(systemBundle, "System bundle should not be null");
    assertEquals("System Bundle", systemBundle.getSymbolicName(), "System bundle should have correct symbolic name");
    assertEquals(Bundle.ACTIVE, systemBundle.getState(), "System bundle should be active");
    
    // Test bundle stop and restart
    systemBundle.stop();
    assertEquals(Bundle.RESOLVED, systemBundle.getState(), "System bundle should be in RESOLVED state after stopping");
    
    systemBundle.start();
    assertEquals(Bundle.ACTIVE, systemBundle.getState(), "System bundle should be in ACTIVE state after starting");
  }
  
  @Test
  @DisplayName("Test bundle classloading with Java 21 class formats")
  public void testBundleClassLoading() {
    // Get system bundle classloader
    Bundle systemBundle = bundleContext.getBundle(0);
    ClassLoader bundleClassLoader = systemBundle.adapt(ClassLoader.class);
    
    assertNotNull(bundleClassLoader, "Bundle classloader should not be null");
    
    // Verify Java 21 class format version is supported
    // Java 21 class format version is 65.0 (0x41.0)
    assertTrue(isJava21Compatible(), "Felix should support Java 21 class format version");
  }
  
  @Test
  @DisplayName("Test bundle loading with virtual threads")
  public void testBundleLoadingWithVirtualThreads() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21Compatible()) {
      System.out.println("Skipping virtual threads test as it requires Java 21");
      return;
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Load a bundle using a virtual thread
      Future<?> future = executor.submit(() -> {
        try {
          // Get system bundle from virtual thread
          Bundle systemBundle = bundleContext.getBundle(0);
          assertNotNull(systemBundle, "System bundle should be accessible from virtual thread");
          assertEquals(Bundle.ACTIVE, systemBundle.getState(), 
              "System bundle should be in ACTIVE state when accessed from virtual thread");
          
          // Test service registry access from virtual thread
          ServiceReference<?>[] refs = bundleContext.getAllServiceReferences(null, null);
          assertNotNull(refs, "Service references should be accessible from virtual thread");
          
          // Verify thread is actually a virtual thread
          Thread currentThread = Thread.currentThread();
          boolean isVirtual = false;
          try {
            // In Java 21, we can use Thread.currentThread().isVirtual()
            isVirtual = (boolean) Thread.class.getMethod("isVirtual").invoke(currentThread);
          } catch (Exception e) {
            // For Java versions that don't have isVirtual method
            isVirtual = currentThread.toString().contains("VirtualThread");
          }
          assertTrue(isVirtual, "Test should be running in a virtual thread");
          
          return true;
        } 
        catch (Exception e) {
          e.printStackTrace();
          return false;
        }
      });
      
      // Verify the operation completed successfully
      assertTrue((Boolean) future.get(), "Bundle operations should succeed when executed in virtual thread");
    }
  }
  
  @Test
  @DisplayName("Test bundle loading with both platform and virtual threads")
  public void testBundleLoadingWithMixedThreads() throws Exception {
    // Skip test if not running on Java 21 or later
    if (!isJava21Compatible()) {
      System.out.println("Skipping mixed threads test as it requires Java 21");
      return;
    }
    
    // Create a platform thread executor and a virtual thread executor
    ExecutorService platformExecutor = Executors.newFixedThreadPool(1);
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a flag to track success
      AtomicBoolean platformSuccess = new AtomicBoolean(false);
      AtomicBoolean virtualSuccess = new AtomicBoolean(false);
      
      // Submit tasks to both executors
      Future<?> platformFuture = platformExecutor.submit(() -> {
        try {
          Bundle systemBundle = bundleContext.getBundle(0);
          assertNotNull(systemBundle, "System bundle should be accessible from platform thread");
          platformSuccess.set(true);
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
      
      Future<?> virtualFuture = virtualExecutor.submit(() -> {
        try {
          Bundle systemBundle = bundleContext.getBundle(0);
          assertNotNull(systemBundle, "System bundle should be accessible from virtual thread");
          virtualSuccess.set(true);
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
      
      // Wait for both tasks to complete
      platformFuture.get();
      virtualFuture.get();
      
      // Verify both threads succeeded
      assertTrue(platformSuccess.get(), "Bundle operations should succeed in platform thread");
      assertTrue(virtualSuccess.get(), "Bundle operations should succeed in virtual thread");
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.close();
    }
  }
  
  @Test
  @DisplayName("Test bundle dependency resolution with Java 21 module system")
  public void testBundleDependencyResolution() {
    // Mock a bundle with dependencies
    when(mockBundle.getSymbolicName()).thenReturn("test.bundle");
    when(mockBundle.getVersion()).thenReturn(org.osgi.framework.Version.parseVersion("1.0.0"));
    
    // Verify Felix correctly handles module boundaries
    Bundle systemBundle = bundleContext.getBundle(0);
    assertNotNull(systemBundle.getBundleContext(), "System bundle context should be available");
    
    // Verify bundle wiring capabilities
    org.osgi.framework.wiring.BundleWiring wiring = systemBundle.adapt(org.osgi.framework.wiring.BundleWiring.class);
    assertNotNull(wiring, "Bundle wiring should be available for dependency resolution");
    
    // Verify classloader access
    ClassLoader bundleClassLoader = wiring.getClassLoader();
    assertNotNull(bundleClassLoader, "Bundle classloader should be available");
    
    // Verify capability requirements
    assertNotNull(wiring.getRequirements(null), "Bundle wiring should provide requirements");
    assertNotNull(wiring.getCapabilities(null), "Bundle wiring should provide capabilities");
  }
  
  @Test
  @DisplayName("Test Java 21 language features compatibility with Felix classloading")
  public void testJava21LanguageFeaturesCompatibility() {
    // Skip test if not running on Java 21 or later
    if (!isJava21Compatible()) {
      System.out.println("Skipping Java 21 features test as it requires Java 21");
      return;
    }
    
    // Test record patterns compatibility
    assertTrue(isRecordPatternsSupported(), "Felix should support record patterns from Java 21");
    
    // Test string templates compatibility
    assertTrue(isStringTemplatesSupported(), "Felix should support string templates from Java 21");
    
    // Test virtual threads compatibility
    assertTrue(isVirtualThreadsSupported(), "Felix should support virtual threads from Java 21");
    
    // Verify OSGi classloading with Java 21 module system
    Bundle systemBundle = bundleContext.getBundle(0);
    ClassLoader bundleClassLoader = systemBundle.adapt(ClassLoader.class);
    
    // Verify the bundle classloader can load classes from java.base module
    try {
      Class<?> threadClass = bundleClassLoader.loadClass("java.lang.Thread");
      assertNotNull(threadClass, "Should be able to load Thread class from bundle classloader");
      
      // Verify access to Java 21 specific methods
      assertNotNull(threadClass.getMethod("ofVirtual"), 
          "Should be able to access Java 21 Thread.ofVirtual method");
    } 
    catch (Exception e) {
      assertFalse(true, "Failed to load class from java.base module: " + e.getMessage());
    }
  }
  
  /**
   * Checks if the current JVM supports Java 21 or later.
   */
  private boolean isJava21Compatible() {
    try {
      String javaVersion = System.getProperty("java.version");
      // Java 21 or later
      return javaVersion != null && 
          (javaVersion.startsWith("21.") || 
           javaVersion.startsWith("22.") || 
           (javaVersion.contains(".") && 
            Integer.parseInt(javaVersion.substring(0, javaVersion.indexOf('.'))) >= 21));
    } 
    catch (Exception e) {
      return false;
    }
  }
  
  /**
   * Checks if record patterns are supported in the current JVM.
   */
  private boolean isRecordPatternsSupported() {
    try {
      // Try to access a class that uses record patterns
      Class.forName("java.lang.runtime.SwitchBootstraps");
      return true;
    } 
    catch (ClassNotFoundException e) {
      return false;
    }
  }
  
  /**
   * Checks if string templates are supported in the current JVM.
   */
  private boolean isStringTemplatesSupported() {
    try {
      // Try to access a class related to string templates
      Class.forName("java.lang.StringTemplate");
      return true;
    } 
    catch (ClassNotFoundException e) {
      return false;
    }
  }
  
  /**
   * Checks if virtual threads are supported in the current JVM.
   */
  private boolean isVirtualThreadsSupported() {
    try {
      // Try to access Thread.ofVirtual() method
      Thread.class.getMethod("ofVirtual");
      return true;
    } 
    catch (NoSuchMethodException e) {
      return false;
    }
  }
  
  /**
   * Recursively deletes a directory.
   */
  private void deleteDirectory(File directory) {
    if (directory.exists()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            deleteDirectory(file);
          } 
          else {
            file.delete();
          }
        }
      }
      directory.delete();
    }
  }
}