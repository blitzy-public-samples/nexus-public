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

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

/**
 * Factory for creation of Docker container configurations with Java 21 compatibility.
 * This class centralizes the creation of Docker containers with appropriate Java 21 JVM arguments,
 * environment variables, and image references for integration testing.
 * 
 * <p>The factory provides methods for creating various types of Java 21 containers:</p>
 * <ul>
 *   <li>Standard Java 21 containers with full JDK</li>
 *   <li>Runtime-only containers with JRE</li>
 *   <li>Maven-based containers for build testing</li>
 *   <li>Memory-optimized containers for constrained environments</li>
 *   <li>Virtual Thread-optimized containers for concurrency testing</li>
 *   <li>Nginx containers for proxy/web testing</li>
 * </ul>
 * 
 * <p>All Java 21 containers are configured with appropriate JVM arguments to enable
 * and optimize Java 21 features, particularly Virtual Threads.</p>
 */
public class Java21DockerClientITConfigFactory
{
  // Base Docker image references for Java 21
  private static final String IMAGE_JAVA21_BASE = "eclipse-temurin:21-jdk";
  private static final String IMAGE_JAVA21_MAVEN = "maven:3.9.6-eclipse-temurin-21";
  private static final String IMAGE_JAVA21_RUNTIME = "eclipse-temurin:21-jre";
  private static final String IMAGE_NGINX = "docker-all.repo.sonatype.com/nginx";
  
  // Java 21 JVM arguments for optimal container performance
  private static final String[] JAVA21_DEFAULT_JVM_ARGS = {
      "-XX:+UseZGC",                           // Use ZGC garbage collector
      "-XX:+ZGenerational",                    // Enable generational ZGC
      "-Djdk.virtualThreadScheduler.parallelism=16",  // Optimize virtual thread scheduling
      "-Djdk.virtualThreadScheduler.maxPoolSize=256", // Set maximum carrier thread pool size
      "-Djdk.tracePinnedThreads=full",         // Enable thread pinning detection for debugging
      "-XX:+EnableDynamicAgentLoading",        // Enable dynamic agent loading for testing tools
      "-XX:+UnlockExperimentalVMOptions"       // Unlock experimental options for advanced features
  };
  
  // Memory-optimized JVM arguments for constrained environments
  private static final String[] JAVA21_MEMORY_OPTIMIZED_ARGS = {
      "-XX:+UseZGC",                           // Use ZGC garbage collector
      "-XX:+ZGenerational",                    // Enable generational ZGC
      "-Xmx512m",                              // Limit max heap size
      "-XX:MaxRAMPercentage=75.0",             // Use at most 75% of available RAM
      "-XX:MinHeapFreeRatio=10",               // Minimum heap free percentage
      "-XX:MaxHeapFreeRatio=20"                // Maximum heap free percentage
  };
  
  // Environment variables for enabling Virtual Threads
  private static final Map<String, String> VIRTUAL_THREAD_ENV_VARS = new HashMap<String, String>() {{
      put("JAVA_TOOL_OPTIONS", "-Djdk.virtualThreadScheduler.parallelism=16 -Djdk.virtualThreadScheduler.maxPoolSize=256");
      put("TEST_VIRTUAL_THREADS", "true");
  }};

  private Java21DockerClientITConfigFactory() {
    // Private constructor to prevent instantiation
  }

  /**
   * Creates a Docker container configuration for a Java 21 application.
   *
   * @param imageName the Docker image name
   * @param jvmArgs additional JVM arguments (will be combined with default Java 21 JVM args)
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param environmentVars additional environment variables
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createJava21Config(
      final String imageName,
      final String[] jvmArgs,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final Map<String, String> environmentVars)
  {
    // Combine default Java 21 JVM args with provided args
    List<String> allJvmArgs = new ArrayList<>();
    for (String arg : JAVA21_DEFAULT_JVM_ARGS) {
      allJvmArgs.add(arg);
    }
    if (jvmArgs != null) {
      for (String arg : jvmArgs) {
        allJvmArgs.add(arg);
      }
    }
    
    // Combine environment variables
    Map<String, String> allEnvVars = new HashMap<>(VIRTUAL_THREAD_ENV_VARS);
    if (environmentVars != null) {
      allEnvVars.putAll(environmentVars);
    }
    
    // Build and return the container configuration
    return DockerContainerConfig.builder(imageName)
        .withPathBinds(pathBinds)
        .withExposedPorts(portMappingPorts)
        .withEnvironmentVariables(allEnvVars)
        .withCommandLineArguments(allJvmArgs)
        .build();
  }
  
  /**
   * Creates a Docker container configuration for a Java 21 application with default base image.
   *
   * @param jvmArgs additional JVM arguments (will be combined with default Java 21 JVM args)
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param environmentVars additional environment variables
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createDefaultJava21Config(
      final String[] jvmArgs,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final Map<String, String> environmentVars)
  {
    return createJava21Config(
        IMAGE_JAVA21_BASE,
        jvmArgs,
        pathBinds,
        portMappingPorts,
        environmentVars);
  }
  
  /**
   * Creates a Docker container configuration for a Java 21 Maven application.
   * This is useful for running Maven-based tests in a Java 21 environment.
   *
   * @param jvmArgs additional JVM arguments (will be combined with default Java 21 JVM args)
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param environmentVars additional environment variables
   * @param mavenArgs additional Maven arguments
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createMavenJava21Config(
      final String[] jvmArgs,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final Map<String, String> environmentVars,
      final String[] mavenArgs)
  {
    // Combine environment variables with Maven-specific ones
    Map<String, String> mavenEnvVars = new HashMap<>();
    if (environmentVars != null) {
      mavenEnvVars.putAll(environmentVars);
    }
    mavenEnvVars.put("MAVEN_OPTS", "-Xmx1024m");
    
    // Create the base configuration
    DockerContainerConfig config = createJava21Config(
        IMAGE_JAVA21_MAVEN,
        jvmArgs,
        pathBinds,
        portMappingPorts,
        mavenEnvVars);
    
    // Add Maven arguments if provided
    if (mavenArgs != null && mavenArgs.length > 0) {
      List<String> mvnCommand = new ArrayList<>();
      mvnCommand.add("mvn");
      for (String arg : mavenArgs) {
        mvnCommand.add(arg);
      }
      config = config.withCommand(mvnCommand);
    }
    
    return config;
  }
  
  /**
   * Creates a Docker container configuration for a Java 21 runtime application (JRE only).
   * This is useful for running lightweight Java applications that don't need the full JDK.
   *
   * @param jvmArgs additional JVM arguments (will be combined with default Java 21 JVM args)
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param environmentVars additional environment variables
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createJava21RuntimeConfig(
      final String[] jvmArgs,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final Map<String, String> environmentVars)
  {
    return createJava21Config(
        IMAGE_JAVA21_RUNTIME,
        jvmArgs,
        pathBinds,
        portMappingPorts,
        environmentVars);
  }
  
  /**
   * Creates a Docker container configuration for an Nginx server.
   * This method is similar to the one in DockerClientITConfigFactory but ensures
   * compatibility with Java 21 testing environments.
   *
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createNginxConfig(
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts)
  {
    return DockerContainerConfig.builder(IMAGE_NGINX)
        .withPathBinds(pathBinds)
        .withExposedPorts(portMappingPorts)
        .build();
  }
  
  /**
   * Creates a Docker container configuration for a Java 21 application with memory-optimized settings.
   * This is useful for running in constrained environments or when memory efficiency is critical.
   *
   * @param imageName the Docker image name
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param environmentVars additional environment variables
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createMemoryOptimizedJava21Config(
      final String imageName,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final Map<String, String> environmentVars)
  {
    // Combine environment variables
    Map<String, String> allEnvVars = new HashMap<>();
    if (environmentVars != null) {
      allEnvVars.putAll(environmentVars);
    }
    allEnvVars.put("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=75.0");
    
    // Build and return the container configuration
    return DockerContainerConfig.builder(imageName)
        .withPathBinds(pathBinds)
        .withExposedPorts(portMappingPorts)
        .withEnvironmentVariables(allEnvVars)
        .withCommandLineArguments(JAVA21_MEMORY_OPTIMIZED_ARGS)
        .build();
  }
  
  /**
   * Creates a Docker container configuration for a Java 21 application with Virtual Threads enabled.
   * This configuration is specifically optimized for testing Virtual Thread capabilities.
   *
   * @param imageName the Docker image name
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose
   * @param mainClass the main class to execute
   * @param appArgs application arguments
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createVirtualThreadConfig(
      final String imageName,
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final String mainClass,
      final String[] appArgs)
  {
    // Virtual Thread specific JVM arguments
    String[] vtJvmArgs = {
        "-Djdk.virtualThreadScheduler.parallelism=16",
        "-Djdk.virtualThreadScheduler.maxPoolSize=256",
        "-Djdk.tracePinnedThreads=full"
    };
    
    // Environment variables for Virtual Thread testing
    Map<String, String> vtEnvVars = new HashMap<>();
    vtEnvVars.put("TEST_VIRTUAL_THREADS", "true");
    vtEnvVars.put("JAVA_TOOL_OPTIONS", String.join(" ", vtJvmArgs));
    
    // Create the base configuration
    DockerContainerConfig config = createJava21Config(
        imageName,
        vtJvmArgs,
        pathBinds,
        portMappingPorts,
        vtEnvVars);
    
    // Add command to run the main class if provided
    if (mainClass != null && !mainClass.isEmpty()) {
      List<String> command = new ArrayList<>();
      command.add("java");
      command.addAll(Arrays.asList(vtJvmArgs));
      command.add(mainClass);
      
      if (appArgs != null) {
        command.addAll(Arrays.asList(appArgs));
      }
      
      config = config.withCommand(command);
    }
    
    return config;
  }
  
  /**
   * Creates a Docker container configuration for a Nexus Repository instance with Java 21.
   * This is specifically designed for testing Nexus Repository with Java 21 features.
   *
   * @param pathBinds map of path bindings (host path -> container path)
   * @param portMappingPorts list of ports to expose (should include 8081 for Nexus web UI)
   * @param enableVirtualThreads whether to enable Virtual Threads for the Nexus instance
   * @return a Docker container configuration
   */
  public static DockerContainerConfig createNexusJava21Config(
      final Map<String, String> pathBinds,
      final List<String> portMappingPorts,
      final boolean enableVirtualThreads)
  {
    // Environment variables for Nexus
    Map<String, String> nexusEnvVars = new HashMap<>();
    nexusEnvVars.put("INSTALL4J_ADD_VM_PARAMS", String.join(" ", JAVA21_DEFAULT_JVM_ARGS));
    
    if (enableVirtualThreads) {
      nexusEnvVars.put("NEXUS_VIRTUAL_THREADS_ENABLED", "true");
      nexusEnvVars.put("INSTALL4J_ADD_VM_PARAMS", nexusEnvVars.get("INSTALL4J_ADD_VM_PARAMS") + 
          " -Djdk.virtualThreadScheduler.parallelism=16" +
          " -Djdk.virtualThreadScheduler.maxPoolSize=256" +
          " -Djdk.tracePinnedThreads=full");
    }
    
    // Use a custom Nexus image with Java 21
    // Note: This assumes a custom Nexus image with Java 21 is available
    String nexusJava21Image = "sonatype/nexus3:java21";
    
    return DockerContainerConfig.builder(nexusJava21Image)
        .withPathBinds(pathBinds)
        .withExposedPorts(portMappingPorts)
        .withEnvironmentVariables(nexusEnvVars)
        .build();
  }