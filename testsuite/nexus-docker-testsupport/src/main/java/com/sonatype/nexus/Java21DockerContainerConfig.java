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
import java.util.Map;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

/**
 * Java 21-specific extension of {@link DockerContainerConfig} that adds JVM arguments and environment variables
 * optimized for Java 21 features, particularly Virtual Threads.
 * <p>
 * This class provides a fluent Builder API to configure Java 21-specific settings like ZGC garbage collector,
 * thread pinning detection, and Virtual Thread scheduler parameters.
 *
 * @since 3.60
 */
public class Java21DockerContainerConfig
    extends DockerContainerConfig
{
  /**
   * Default value for Virtual Thread scheduler parallelism
   */
  public static final String DEFAULT_VIRTUAL_THREAD_PARALLELISM = "16";

  /**
   * Default value for Virtual Thread scheduler maximum pool size
   */
  public static final String DEFAULT_VIRTUAL_THREAD_MAX_POOL_SIZE = "256";

  /**
   * Private constructor used by the Builder
   */
  private Java21DockerContainerConfig(final String image, final Path dockerfile) {
    super(image, dockerfile);
  }

  /**
   * Creates a new Builder for Java21DockerContainerConfig with the specified Docker image.
   *
   * @param image the Docker image name
   * @return a new Builder instance
   */
  public static Builder builder(final String image) {
    return new Builder(image);
  }

  /**
   * Creates a new Builder for Java21DockerContainerConfig with the specified Dockerfile path.
   *
   * @param dockerfile the path to the Dockerfile
   * @return a new Builder instance
   */
  public static Builder builder(final Path dockerfile) {
    return new Builder(dockerfile);
  }

  /**
   * Builder for Java21DockerContainerConfig that extends DockerContainerConfig.Builder
   * with Java 21-specific configuration options.
   */
  public static final class Builder
      extends DockerContainerConfig.Builder
  {
    private boolean useZGC = true;
    private boolean useZGenerational = true;
    private boolean enableThreadPinningDetection = true;
    private String virtualThreadParallelism = DEFAULT_VIRTUAL_THREAD_PARALLELISM;
    private String virtualThreadMaxPoolSize = DEFAULT_VIRTUAL_THREAD_MAX_POOL_SIZE;
    private Map<String, String> java21Env = new HashMap<>();

    private Builder(final String image) {
      super(image);
    }

    private Builder(final Path dockerfile) {
      super(dockerfile);
    }

    /**
     * Enables or disables the Z Garbage Collector (ZGC).
     * ZGC is a scalable low-latency garbage collector suitable for Java 21 applications.
     *
     * @param useZGC true to enable ZGC, false to disable
     * @return this builder instance
     */
    public Builder withZGC(final boolean useZGC) {
      this.useZGC = useZGC;
      return this;
    }

    /**
     * Enables or disables the Generational Z Garbage Collector.
     * Generational ZGC splits the heap into young and old generations for improved performance.
     *
     * @param useZGenerational true to enable Generational ZGC, false to disable
     * @return this builder instance
     */
    public Builder withZGenerational(final boolean useZGenerational) {
      this.useZGenerational = useZGenerational;
      return this;
    }

    /**
     * Enables or disables thread pinning detection for Virtual Threads.
     * When enabled, the JVM will log when virtual threads are pinned to carrier threads.
     *
     * @param enableThreadPinningDetection true to enable thread pinning detection, false to disable
     * @return this builder instance
     */
    public Builder withThreadPinningDetection(final boolean enableThreadPinningDetection) {
      this.enableThreadPinningDetection = enableThreadPinningDetection;
      return this;
    }

    /**
     * Sets the parallelism level for the Virtual Thread scheduler.
     * This controls how many carrier threads are used for virtual thread execution.
     *
     * @param parallelism the parallelism level as a string
     * @return this builder instance
     */
    public Builder withVirtualThreadParallelism(final String parallelism) {
      this.virtualThreadParallelism = parallelism;
      return this;
    }

    /**
     * Sets the maximum pool size for the Virtual Thread scheduler.
     * This limits the maximum number of carrier threads that can be created.
     *
     * @param maxPoolSize the maximum pool size as a string
     * @return this builder instance
     */
    public Builder withVirtualThreadMaxPoolSize(final String maxPoolSize) {
      this.virtualThreadMaxPoolSize = maxPoolSize;
      return this;
    }

    /**
     * Adds a Java 21-specific environment variable to the container configuration.
     *
     * @param name the environment variable name
     * @param value the environment variable value
     * @return this builder instance
     */
    public Builder withJava21EnvVar(final String name, final String value) {
      this.java21Env.put(name, value);
      return this;
    }

    /**
     * Builds the Java21DockerContainerConfig with all configured options.
     * This method constructs the JVM arguments based on the configured settings
     * and adds them to the container environment variables.
     *
     * @return a new Java21DockerContainerConfig instance
     */
    @Override
    public Java21DockerContainerConfig build() {
      // Build Java 21 JVM arguments
      StringBuilder jvmArgs = new StringBuilder();

      // Configure ZGC if enabled
      if (useZGC) {
        jvmArgs.append("-XX:+UseZGC ");
        if (useZGenerational) {
          jvmArgs.append("-XX:+ZGenerational ");
        }
      }

      // Configure thread pinning detection if enabled
      if (enableThreadPinningDetection) {
        jvmArgs.append("-Djdk.tracePinnedThreads=full ");
      }

      // Configure Virtual Thread scheduler parameters
      jvmArgs.append("-Djdk.virtualThreadScheduler.parallelism=").append(virtualThreadParallelism).append(" ");
      jvmArgs.append("-Djdk.virtualThreadScheduler.maxPoolSize=").append(virtualThreadMaxPoolSize).append(" ");

      // Get existing environment variables or create new map if none exist
      Map<String, String> env = getEnv();
      if (env == null) {
        env = new HashMap<>();
      }

      // Add Java 21 JVM arguments to JAVA_TOOL_OPTIONS environment variable
      String existingJavaOpts = env.getOrDefault("JAVA_TOOL_OPTIONS", "");
      env.put("JAVA_TOOL_OPTIONS", existingJavaOpts + " " + jvmArgs.toString().trim());

      // Add any additional Java 21-specific environment variables
      env.putAll(java21Env);

      // Update environment variables in the builder
      withEnv(env);

      // Create the Java21DockerContainerConfig instance
      Java21DockerContainerConfig config = new Java21DockerContainerConfig(getImage(), getDockerfile());
      config.pathBinds = getPathBinds();
      config.exposedPorts = getExposedPorts();
      config.workingDir = getWorkingDir();
      config.env = env;

      return config;
    }
  }
}