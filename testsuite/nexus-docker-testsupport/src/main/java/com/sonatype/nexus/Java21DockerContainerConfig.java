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
package com.sonatype.nexus;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Java 21-specific extension of {@link DockerContainerConfig} that adds JVM arguments and environment variables
 * optimized for Java 21 features, particularly Virtual Threads.
 * <p>
 * This class provides a fluent Builder API to configure Java 21-specific settings such as:
 * <ul>
 *   <li>Z Garbage Collector (ZGC) with generational mode</li>
 *   <li>Thread pinning detection for Virtual Thread debugging</li>
 *   <li>Virtual Thread scheduler parallelism and pool size configuration</li>
 *   <li>Custom JVM arguments for Java 21 features</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * Java21DockerContainerConfig config = Java21DockerContainerConfig.builder("eclipse-temurin:21-jdk")
 *     .withExposedPort("8081")
 *     .withZGC()
 *     .withThreadPinningDetection()
 *     .withVirtualThreadSchedulerParallelism("16")
 *     .withVirtualThreadSchedulerMaxPoolSize("256")
 *     .withJava21JvmArg("XX:+UseStringDeduplication", "")
 *     .build();
 * </pre>
 * 
 * @since 3.60
 */
public class Java21DockerContainerConfig
    extends DockerContainerConfig
{
  /**
   * Default parallelism for the Virtual Thread scheduler.
   * This value determines how many platform threads (carrier threads) are used to execute virtual threads.
   * The default value of 16 is a reasonable starting point for most applications.
   */
  private static final String DEFAULT_VIRTUAL_THREAD_PARALLELISM = "16";
  
  /**
   * Default maximum pool size for the Virtual Thread scheduler.
   * This value limits the maximum number of platform threads that can be created to handle virtual threads.
   * The default value of 256 allows for significant scaling while preventing resource exhaustion.
   */
  private static final String DEFAULT_VIRTUAL_THREAD_MAX_POOL_SIZE = "256";
  
  /**
   * Java 21-specific JVM arguments.
   * This map contains JVM argument keys and their values (if any).
   * For boolean flags, the value is an empty string.
   */
  private final Map<String, String> java21JvmArgs;

  /**
   * Private constructor used by the builder.
   * 
   * @param image Docker image name (nullable if dockerfile is provided)
   * @param dockerfile Path to Dockerfile (nullable if image is provided)
   * @param java21JvmArgs Map of Java 21 JVM arguments
   */
  private Java21DockerContainerConfig(@Nullable final String image, 
                                     @Nullable final Path dockerfile,
                                     final Map<String, String> java21JvmArgs) {
    super(image, dockerfile);
    this.java21JvmArgs = java21JvmArgs;
  }

  /**
   * Get the Java 21-specific JVM arguments.
   *
   * @return Map of JVM argument keys to values
   */
  public Map<String, String> getJava21JvmArgs() {
    return java21JvmArgs;
  }

  /**
   * Create a new builder with the specified image name.
   *
   * @param image Docker image name
   * @return A new builder instance
   */
  public static Java21Builder builder(final String image) {
    return new Java21Builder(image);
  }

  /**
   * Create a new builder with the specified Dockerfile path.
   *
   * @param dockerfile Path to Dockerfile
   * @return A new builder instance
   */
  public static Java21Builder builder(final Path dockerfile) {
    return new Java21Builder(dockerfile);
  }

  /**
   * Builder for Java21DockerContainerConfig that extends DockerContainerConfig.Builder
   * with Java 21-specific configuration options.
   */
  /**
   * Builder for Java21DockerContainerConfig that extends DockerContainerConfig.Builder
   * with Java 21-specific configuration options. This builder automatically applies
   * default Java 21 optimizations when created.
   */
  public static final class Java21Builder
      extends Builder
  {
    /**
     * Map of Java 21 JVM arguments and their values.
     */
    private Map<String, String> java21JvmArgs = new HashMap<>();

    /**
     * Constructor for the builder with an image name.
     * Automatically applies default Java 21 optimizations.
     *
     * @param image Docker image name
     */
    private Java21Builder(final String image) {
      super(image);
      // Set default Java 21 JVM arguments
      withZGC()
          .withThreadPinningDetection()
          .withVirtualThreadSchedulerParallelism(DEFAULT_VIRTUAL_THREAD_PARALLELISM)
          .withVirtualThreadSchedulerMaxPoolSize(DEFAULT_VIRTUAL_THREAD_MAX_POOL_SIZE);
    }

    /**
     * Constructor for the builder with a Dockerfile path.
     * Automatically applies default Java 21 optimizations.
     *
     * @param dockerfile Path to Dockerfile
     */
    private Java21Builder(final Path dockerfile) {
      super(dockerfile);
      // Set default Java 21 JVM arguments
      withZGC()
          .withThreadPinningDetection()
          .withVirtualThreadSchedulerParallelism(DEFAULT_VIRTUAL_THREAD_PARALLELISM)
          .withVirtualThreadSchedulerMaxPoolSize(DEFAULT_VIRTUAL_THREAD_MAX_POOL_SIZE);
    }

    /**
     * Enable the Z Garbage Collector (ZGC) for improved latency and throughput.
     *
     * @return this builder instance
     */
    /**
     * Enable the Z Garbage Collector (ZGC) for improved latency and throughput.
     * Also enables ZGenerational mode for better performance with generational garbage collection.
     *
     * @return this builder instance
     */
    public Java21Builder withZGC() {
      java21JvmArgs.put("XX:+UseZGC", "");
      java21JvmArgs.put("XX:+ZGenerational", "");
      return this;
    }

    /**
     * Enable thread pinning detection for Virtual Thread debugging.
     *
     * @return this builder instance
     */
    /**
     * Enable thread pinning detection for Virtual Thread debugging.
     * When enabled, the JVM will log stack traces when virtual threads become pinned,
     * which helps identify blocking operations that prevent efficient virtual thread execution.
     *
     * @return this builder instance
     */
    public Java21Builder withThreadPinningDetection() {
      java21JvmArgs.put("jdk.tracePinnedThreads", "full");
      return this;
    }

    /**
     * Configure the parallelism level for the Virtual Thread scheduler.
     *
     * @param parallelism the parallelism level (number of carrier threads)
     * @return this builder instance
     */
    /**
     * Configure the parallelism level for the Virtual Thread scheduler.
     * This setting controls how many platform threads (carrier threads) are used
     * to execute virtual threads. The default is typically based on available CPU cores.
     *
     * @param parallelism the parallelism level (number of carrier threads)
     * @return this builder instance
     * @throws NullPointerException if parallelism is null
     */
    public Java21Builder withVirtualThreadSchedulerParallelism(final String parallelism) {
      java21JvmArgs.put("jdk.virtualThreadScheduler.parallelism", checkNotNull(parallelism));
      return this;
    }

    /**
     * Configure the maximum pool size for the Virtual Thread scheduler.
     *
     * @param maxPoolSize the maximum pool size
     * @return this builder instance
     */
    /**
     * Configure the maximum pool size for the Virtual Thread scheduler.
     * This setting limits the maximum number of platform threads that can be created
     * to handle virtual threads, preventing resource exhaustion under high load.
     *
     * @param maxPoolSize the maximum pool size
     * @return this builder instance
     * @throws NullPointerException if maxPoolSize is null
     */
    public Java21Builder withVirtualThreadSchedulerMaxPoolSize(final String maxPoolSize) {
      java21JvmArgs.put("jdk.virtualThreadScheduler.maxPoolSize", checkNotNull(maxPoolSize));
      return this;
    }

    /**
     * Add a custom Java 21 JVM argument.
     *
     * @param key the JVM argument key
     * @param value the JVM argument value (can be empty string for flags)
     * @return this builder instance
     */
    /**
     * Add a custom Java 21 JVM argument.
     * This allows adding any additional JVM arguments that are not covered by the
     * specialized methods in this builder.
     *
     * @param key the JVM argument key (e.g., "XX:+UseStringDeduplication")
     * @param value the JVM argument value (can be empty string for boolean flags)
     * @return this builder instance
     * @throws NullPointerException if key or value is null
     */
    public Java21Builder withJava21JvmArg(final String key, final String value) {
      java21JvmArgs.put(checkNotNull(key), checkNotNull(value));
      return this;
    }

    /**
     * Build the Java21DockerContainerConfig instance.
     *
     * @return a new Java21DockerContainerConfig instance
     */
    /**
     * Build the Java21DockerContainerConfig instance.
     * This method first builds the parent DockerContainerConfig, then creates a new
     * Java21DockerContainerConfig with the same properties plus the Java 21-specific JVM arguments.
     * The Java 21 JVM arguments are added to the JAVA_OPTS environment variable.
     *
     * @return a new Java21DockerContainerConfig instance
     */
    @Override
    public Java21DockerContainerConfig build() {
      // First build the parent DockerContainerConfig
      DockerContainerConfig parentConfig = super.build();
      
      // Create Java21DockerContainerConfig with the same properties
      Java21DockerContainerConfig config = new Java21DockerContainerConfig(
          parentConfig.getImage(),
          parentConfig.getDockerfile(),
          this.java21JvmArgs
      );
      
      // Copy properties from parent
      config.pathBinds = parentConfig.getPathBinds();
      config.exposedPorts = parentConfig.getExposedPorts();
      config.workingDir = parentConfig.getWorkingDir();
      config.env = parentConfig.getEnv();
      
      // Add Java 21 JVM arguments to environment variables if needed
      if (!java21JvmArgs.isEmpty()) {
        StringBuilder jvmArgs = new StringBuilder();
        for (Map.Entry<String, String> entry : java21JvmArgs.entrySet()) {
          if (jvmArgs.length() > 0) {
            jvmArgs.append(" ");
          }
          jvmArgs.append("-").append(entry.getKey());
          if (!entry.getValue().isEmpty()) {
            jvmArgs.append("=").append(entry.getValue());
          }
        }
        
        // If JAVA_OPTS already exists, append to it, otherwise create it
        Map<String, String> env = config.env != null ? new HashMap<>(config.env) : new HashMap<>();
        String existingOpts = env.getOrDefault("JAVA_OPTS", "");
        if (!existingOpts.isEmpty()) {
          existingOpts += " ";
        }
        env.put("JAVA_OPTS", existingOpts + jvmArgs.toString());
        config.env = env;
      }
      
      return config;
    }
  }
}