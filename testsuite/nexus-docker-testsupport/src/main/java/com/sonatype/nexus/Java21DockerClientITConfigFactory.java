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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

/**
 * Factory for creation of Java 21-compatible Docker container configurations for integration testing.
 * <p>
 * This factory provides standardized configurations for Docker containers with Java 21 runtime
 * environments, appropriate JVM arguments, and base images for different testing scenarios.
 * <p>
 * Key features supported:
 * <ul>
 *   <li>Java 21 JDK and JRE base images (Eclipse Temurin)</li>
 *   <li>Generational ZGC garbage collector configuration</li>
 *   <li>Virtual Threads optimization and configuration</li>
 *   <li>Preview features enablement for Pattern Matching and String Templates</li>
 *   <li>Specialized configurations for different testing scenarios</li>
 * </ul>
 */
public class Java21DockerClientITConfigFactory
{
  // Base Docker images for Java 21
  private static final String IMAGE_JAVA21_JDK = "eclipse-temurin:21-jdk";
  private static final String IMAGE_JAVA21_JRE = "eclipse-temurin:21-jre";
  private static final String IMAGE_MAVEN_JAVA21 = "maven:3.9.6-eclipse-temurin-21";
  private static final String IMAGE_NGINX = "docker-all.repo.sonatype.com/nginx";
  
  // Image tags for specific Java 21 versions if needed
  private static final String IMAGE_JAVA21_0_1_JDK = "eclipse-temurin:21.0.1_12-jdk";
  private static final String IMAGE_JAVA21_0_1_JRE = "eclipse-temurin:21.0.1_12-jre";

  // Java 21 specific JVM arguments
  private static final String DEFAULT_JAVA21_OPTS = "-XX:+UseZGC -XX:+ZGenerational";
  private static final String VIRTUAL_THREADS_OPTS = "-Djdk.virtualThreadScheduler.parallelism=16 -Djdk.virtualThreadScheduler.maxPoolSize=256";
  private static final String VIRTUAL_THREADS_DEBUG_OPTS = "-Djdk.tracePinnedThreads=full";
  private static final String PREVIEW_FEATURES_OPTS = "--enable-preview";
  private static final String GC_LOGGING_OPTS = "-Xlog:gc*=info:file=/tmp/gc.log:time,uptime,level,tags";

  private Java21DockerClientITConfigFactory() {
    // Prevent instantiation
  }

  /**
   * Creates a Docker container configuration with Java 21 JDK.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @param enableGCLogging Whether to enable detailed GC logging
   * @return Docker container configuration
   */
  public static DockerContainerConfig createJava21JdkConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures,
      final boolean enableGCLogging)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    
    if (enableVirtualThreads) {
      javaOpts.append(" ").append(VIRTUAL_THREADS_OPTS);
    }
    
    if (enablePreviewFeatures) {
      javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    }
    
    if (enableGCLogging) {
      javaOpts.append(" ").append(GC_LOGGING_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());

    return DockerContainerConfig.builder(IMAGE_JAVA21_JDK)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JDK.
   * This is a convenience method that defaults GC logging to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @return Docker container configuration
   */
  public static DockerContainerConfig createJava21JdkConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures)
  {
    return createJava21JdkConfig(pathBinds, exposedPorts, enableVirtualThreads, enablePreviewFeatures, false);
  }

  /**
   * Creates a Docker container configuration with Java 21 JRE.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @param enableGCLogging Whether to enable detailed GC logging
   * @return Docker container configuration
   */
  public static DockerContainerConfig createJava21JreConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures,
      final boolean enableGCLogging)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    
    if (enableVirtualThreads) {
      javaOpts.append(" ").append(VIRTUAL_THREADS_OPTS);
    }
    
    if (enablePreviewFeatures) {
      javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    }
    
    if (enableGCLogging) {
      javaOpts.append(" ").append(GC_LOGGING_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());

    return DockerContainerConfig.builder(IMAGE_JAVA21_JRE)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JRE.
   * This is a convenience method that defaults GC logging to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @return Docker container configuration
   */
  public static DockerContainerConfig createJava21JreConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures)
  {
    return createJava21JreConfig(pathBinds, exposedPorts, enableVirtualThreads, enablePreviewFeatures, false);
  }

  /**
   * Creates a Docker container configuration with Maven and Java 21.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param mavenArgs Additional Maven arguments
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @param enableGCLogging Whether to enable detailed GC logging
   * @return Docker container configuration
   */
  public static DockerContainerConfig createMavenJava21Config(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final String mavenArgs,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures,
      final boolean enableGCLogging)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    
    if (enableVirtualThreads) {
      javaOpts.append(" ").append(VIRTUAL_THREADS_OPTS);
    }
    
    if (enablePreviewFeatures) {
      javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    }
    
    if (enableGCLogging) {
      javaOpts.append(" ").append(GC_LOGGING_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());
    env.put("MAVEN_OPTS", javaOpts.toString());
    
    if (mavenArgs != null && !mavenArgs.isEmpty()) {
      env.put("MAVEN_CONFIG", mavenArgs);
    }

    return DockerContainerConfig.builder(IMAGE_MAVEN_JAVA21)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Maven and Java 21.
   * This is a convenience method that defaults GC logging to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param mavenArgs Additional Maven arguments
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @return Docker container configuration
   */
  public static DockerContainerConfig createMavenJava21Config(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final String mavenArgs,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures)
  {
    return createMavenJava21Config(pathBinds, exposedPorts, mavenArgs, enableVirtualThreads, enablePreviewFeatures, false);
  }

  /**
   * Creates a Docker container configuration with Nginx for testing Java 21 applications.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @return Docker container configuration
   */
  public static DockerContainerConfig createNginxConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts)
  {
    return DockerContainerConfig.builder(IMAGE_NGINX)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JDK using a specific version.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableVirtualThreads Whether to enable Virtual Threads optimizations
   * @param enablePreviewFeatures Whether to enable preview features
   * @return Docker container configuration
   */
  public static DockerContainerConfig createJava21_0_1_JdkConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableVirtualThreads,
      final boolean enablePreviewFeatures)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    
    if (enableVirtualThreads) {
      javaOpts.append(" ").append(VIRTUAL_THREADS_OPTS);
    }
    
    if (enablePreviewFeatures) {
      javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());

    return DockerContainerConfig.builder(IMAGE_JAVA21_0_1_JDK)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }

  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for Virtual Thread testing.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableDebugMode Whether to enable additional debugging for virtual threads
   * @return Docker container configuration with Virtual Threads enabled
   */
  public static DockerContainerConfig createVirtualThreadTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableDebugMode)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    javaOpts.append(" ").append(VIRTUAL_THREADS_OPTS);
    
    // Additional settings optimized for Virtual Thread testing
    if (enableDebugMode) {
      javaOpts.append(" ").append(VIRTUAL_THREADS_DEBUG_OPTS);
      javaOpts.append(" -Djdk.virtualThreadScheduler.showStacks=true");
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());
    env.put("SONATYPE_NEXUS_VIRTUAL_THREADS_ENABLED", "true");

    return DockerContainerConfig.builder(IMAGE_JAVA21_JDK)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for Virtual Thread testing.
   * This is a convenience method that defaults debug mode to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @return Docker container configuration with Virtual Threads enabled
   */
  public static DockerContainerConfig createVirtualThreadTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts)
  {
    return createVirtualThreadTestConfig(pathBinds, exposedPorts, false);
  }

  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for pattern matching and record pattern testing.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableGCLogging Whether to enable detailed GC logging
   * @return Docker container configuration with preview features enabled
   */
  public static DockerContainerConfig createPatternMatchingTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableGCLogging)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    
    if (enableGCLogging) {
      javaOpts.append(" ").append(GC_LOGGING_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());
    env.put("SONATYPE_NEXUS_PATTERN_MATCHING_ENABLED", "true");

    return DockerContainerConfig.builder(IMAGE_JAVA21_JDK)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for pattern matching and record pattern testing.
   * This is a convenience method that defaults GC logging to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @return Docker container configuration with preview features enabled
   */
  public static DockerContainerConfig createPatternMatchingTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts)
  {
    return createPatternMatchingTestConfig(pathBinds, exposedPorts, false);
  }

  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for string template testing.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @param enableGCLogging Whether to enable detailed GC logging
   * @return Docker container configuration with preview features enabled
   */
  public static DockerContainerConfig createStringTemplateTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts,
      final boolean enableGCLogging)
  {
    Map<String, String> env = new HashMap<>();
    StringBuilder javaOpts = new StringBuilder(DEFAULT_JAVA21_OPTS);
    javaOpts.append(" ").append(PREVIEW_FEATURES_OPTS);
    
    if (enableGCLogging) {
      javaOpts.append(" ").append(GC_LOGGING_OPTS);
    }
    
    env.put("JAVA_TOOL_OPTIONS", javaOpts.toString());
    env.put("SONATYPE_NEXUS_STRING_TEMPLATE_ENABLED", "true");

    return DockerContainerConfig.builder(IMAGE_JAVA21_JDK)
        .withPathBinds(pathBinds)
        .withExposedPorts(exposedPorts)
        .withEnv(env)
        .build();
  }
  
  /**
   * Creates a Docker container configuration with Java 21 JDK optimized for string template testing.
   * This is a convenience method that defaults GC logging to false.
   *
   * @param pathBinds Map of host paths to container paths
   * @param exposedPorts List of ports to expose
   * @return Docker container configuration with preview features enabled
   */
  public static DockerContainerConfig createStringTemplateTestConfig(
      final Map<String, String> pathBinds,
      final List<String> exposedPorts)
  {
    return createStringTemplateTestConfig(pathBinds, exposedPorts, false);
  }
}