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
package org.sonatype.nexus.internal.atlas;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.karaf.bundle.core.BundleInfo;
import org.apache.karaf.bundle.core.BundleService;
import org.eclipse.sisu.Parameters;
import org.osgi.framework.BundleContext;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Iso8601Date;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.SystemInformationHelper;
import org.sonatype.nexus.common.atlas.SystemInformationGenerator;
import org.sonatype.nexus.common.node.DeploymentAccess;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.common.text.Strings2;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.common.text.Strings2.MASK;

/**
 * Default {@link SystemInformationGenerator}.
 *
 * @since 2.7
 */
@Named
@Singleton
public class SystemInformationGeneratorImpl
    extends ComponentSupport
    implements SystemInformationGenerator
{
  private final ApplicationDirectories applicationDirectories;

  private final ApplicationVersion applicationVersion;

  private final Map<String, String> parameters;

  private final BundleContext bundleContext;

  private final BundleService bundleService;

  private final NodeAccess nodeAccess;

  private final DeploymentAccess deploymentAccess;

  private final Map<String, SystemInformationHelper> systemInformationHelpers;

  static final Map<String, Object> UNAVAILABLE = Map.of("unavailable", true);

  private static final List<String> SENSITIVE_FIELD_NAMES =
      List.of("password", "secret", "token", "sign", "auth", "cred", "key", "pass");

  private static final List<String> SENSITIVE_CREDENTIALS_KEYS =
      List.of("sun.java.command", "INSTALL4J_ADD_VM_PARAMS");

  // Records for structured data
  record TimeInfo(String timezone, long current, String iso8601) {}
  record RuntimeInfo(int availableProcessors, long freeMemory, long totalMemory, long maxMemory, int threads) {}
  record FileStoreInfo(String description, String type, long totalSpace, long usableSpace, long unallocatedSpace, boolean readOnly) {}
  record NetworkInterfaceInfo(String displayName, boolean up, boolean virtual, boolean multicast, boolean loopback, boolean ptp, int mtu, String addresses) {}
  record NexusStatusInfo(String version, String edition, String buildRevision, String buildTimestamp) {}
  record NexusNodeInfo(String nodeId, String deploymentId) {}
  record NexusConfigInfo(String installDirectory, String workingDirectory, String temporaryDirectory) {}
  record BundleData(long bundleId, String name, String symbolicName, String location, String version, String state, int startLevel, boolean fragment) {}

  @Inject
  public SystemInformationGeneratorImpl(
      ApplicationDirectories applicationDirectories,
      ApplicationVersion applicationVersion,
      @Parameters Map<String, String> parameters,
      BundleContext bundleContext,
      BundleService bundleService,
      NodeAccess nodeAccess,
      DeploymentAccess deploymentAccess,
      Map<String, SystemInformationHelper> systemInformationHelpers)
  {
    this.applicationDirectories = checkNotNull(applicationDirectories);
    this.applicationVersion = checkNotNull(applicationVersion);
    this.parameters = checkNotNull(parameters);
    this.bundleContext = checkNotNull(bundleContext);
    this.bundleService = checkNotNull(bundleService);
    this.nodeAccess = checkNotNull(nodeAccess);
    this.deploymentAccess = checkNotNull(deploymentAccess);
    this.systemInformationHelpers = checkNotNull(systemInformationHelpers);
  }

  @Override
  public Map<String, Object> report() {
    log.info(STR."Generating system information report");

    Map<String, Object> sections = new ConcurrentHashMap<>();
    
    // Use virtual threads for parallel collection of system information
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to collect information in parallel
      Future<?> timeTask = executor.submit(() -> sections.put("system-time", reportTime()));
      Future<?> propertiesTask = executor.submit(() -> sections.put("system-properties", reportObfuscatedProperties(System.getProperties())));
      Future<?> envTask = executor.submit(() -> sections.put("system-environment", reportObfuscatedProperties(System.getenv())));
      Future<?> runtimeTask = executor.submit(() -> sections.put("system-runtime", reportRuntime()));
      Future<?> networkTask = executor.submit(() -> sections.put("system-network", reportNetwork()));
      Future<?> fileStoresTask = executor.submit(() -> sections.put("system-filestores", reportFileStores()));
      Future<?> nexusStatusTask = executor.submit(() -> sections.put("nexus-status", reportNexusStatus()));
      Future<?> nexusNodeTask = executor.submit(() -> sections.put("nexus-node", reportNexusNode()));
      Future<?> nexusPropertiesTask = executor.submit(() -> sections.put("nexus-properties", reportObfuscatedProperties(parameters)));
      Future<?> nexusConfigTask = executor.submit(() -> sections.put("nexus-configuration", reportNexusConfiguration()));
      Future<?> nexusBundlesTask = executor.submit(() -> sections.put("nexus-bundles", reportNexusBundles()));
      
      // Wait for all tasks to complete
      List<Future<?>> tasks = List.of(
          timeTask, propertiesTask, envTask, runtimeTask, networkTask, fileStoresTask,
          nexusStatusTask, nexusNodeTask, nexusPropertiesTask, nexusConfigTask, nexusBundlesTask
      );
      
      for (Future<?> task : tasks) {
        try {
          task.get();
        } catch (Exception e) {
          log.error(STR."Error collecting system information: \{e.getMessage()}", e);
        }
      }
      
      // Merge additional system information helpers
      sections.putAll(systemInformationHelpers);
    }

    return sections;
  }

  private Map<String, Object> reportTime() {
    Date now = new Date();
    TimeInfo timeInfo = new TimeInfo(
        TimeZone.getDefault().getID(),
        now.getTime(),
        Iso8601Date.format(now)
    );
    
    // Convert record to map for backward compatibility
    Map<String, Object> data = new HashMap<>();
    data.put("timezone", timeInfo.timezone());
    data.put("current", timeInfo.current());
    data.put("iso8601", timeInfo.iso8601());
    return data;
  }

  private Map<String, Object> reportRuntime() {
    Runtime runtime = Runtime.getRuntime();
    RuntimeInfo runtimeInfo = new RuntimeInfo(
        runtime.availableProcessors(),
        runtime.freeMemory(),
        runtime.totalMemory(),
        runtime.maxMemory(),
        Thread.activeCount()
    );
    
    // Convert record to map for backward compatibility
    Map<String, Object> data = new HashMap<>();
    data.put("availableProcessors", runtimeInfo.availableProcessors());
    data.put("freeMemory", runtimeInfo.freeMemory());
    data.put("totalMemory", runtimeInfo.totalMemory());
    data.put("maxMemory", runtimeInfo.maxMemory());
    data.put("threads", runtimeInfo.threads());
    return data;
  }

  private Map<String, Object> reportFileStores() {
    Map<String, Object> fileStores = new HashMap<>();
    int counter = 1;
    
    // Use virtual threads to scan file stores in parallel
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Map.Entry<String, Map<String, Object>>>> futures = Collections.list(FileSystems.getDefault().getFileStores().iterator())
          .stream()
          .map(store -> executor.submit(() -> {
            String key = store.name();
            // Ensure unique keys
            synchronized (fileStores) {
              while (fileStores.containsKey(key)) {
                key = store.name() + "-" + counter++;
              }
            }
            return Map.entry(key, reportFileStore(store));
          }))
          .collect(Collectors.toList());
      
      // Collect results
      for (Future<Map.Entry<String, Map<String, Object>>> future : futures) {
        try {
          Map.Entry<String, Map<String, Object>> entry = future.get();
          fileStores.put(entry.getKey(), entry.getValue());
        } catch (Exception e) {
          log.error(STR."Error collecting file store information: \{e.getMessage()}", e);
        }
      }
    }
    
    return fileStores;
  }

  private Map<String, Object> reportNetwork() {
    try {
      // Use virtual threads to scan network interfaces in parallel
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .stream()
            .collect(Collectors.toConcurrentMap(
                NetworkInterface::getName,
                intf -> {
                  try {
                    return executor.submit(() -> reportNetworkInterface(intf)).get();
                  } catch (Exception e) {
                    log.error(STR."Error collecting network interface information for \{intf.getName()}: \{e.getMessage()}", e);
                    return UNAVAILABLE;
                  }
                }
            ));
      }
    } catch (SocketException e) {
      log.error(STR."Could not add report to support zip for network interfaces: \{e.getMessage()}", e);
      return UNAVAILABLE;
    }
  }

  private Map<String, Object> reportNexusStatus() {
    NexusStatusInfo statusInfo = new NexusStatusInfo(
        applicationVersion.getVersion(),
        applicationVersion.getEdition(),
        applicationVersion.getBuildRevision(),
        applicationVersion.getBuildTimestamp()
    );
    
    // Convert record to map for backward compatibility
    Map<String, Object> data = new HashMap<>();
    data.put("version", statusInfo.version());
    data.put("edition", statusInfo.edition());
    data.put("buildRevision", statusInfo.buildRevision());
    data.put("buildTimestamp", statusInfo.buildTimestamp());
    return data;
  }

  private Map<String, Object> reportNexusNode() {
    NexusNodeInfo nodeInfo = new NexusNodeInfo(
        nodeAccess.getId(),
        deploymentAccess.getId()
    );
    
    // Convert record to map for backward compatibility
    Map<String, Object> data = new HashMap<>();
    data.put("node-id", nodeInfo.nodeId());
    data.put("deployment-id", nodeInfo.deploymentId());
    return data;
  }

  private Map<String, Object> reportNexusConfiguration() {
    NexusConfigInfo configInfo = new NexusConfigInfo(
        fileref(applicationDirectories.getInstallDirectory()),
        fileref(applicationDirectories.getWorkDirectory()),
        fileref(applicationDirectories.getTemporaryDirectory())
    );
    
    // Convert record to map for backward compatibility
    Map<String, Object> data = new HashMap<>();
    data.put("installDirectory", configInfo.installDirectory());
    data.put("workingDirectory", configInfo.workingDirectory());
    data.put("temporaryDirectory", configInfo.temporaryDirectory());
    return data;
  }

  private Map<String, Object> reportNexusBundles() {
    // Use virtual threads to process bundles in parallel
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return Arrays.stream(bundleContext.getBundles())
          .collect(Collectors.toConcurrentMap(
              bundle -> Long.toString(bundleService.getInfo(bundle).getBundleId()),
              bundle -> {
                try {
                  return executor.submit(() -> {
                    BundleInfo info = bundleService.getInfo(bundle);
                    // name is not set for groovy bundles
                    String name = info.getName() == null ? "" : info.getName();
                    
                    BundleData bundleData = new BundleData(
                        info.getBundleId(),
                        name,
                        info.getSymbolicName(),
                        info.getUpdateLocation(),
                        info.getVersion(),
                        info.getState().name(),
                        info.getStartLevel(),
                        info.isFragment()
                    );
                    
                    // Convert record to map for backward compatibility
                    Map<String, Object> data = new HashMap<>();
                    data.put("bundleId", bundleData.bundleId());
                    data.put("name", bundleData.name());
                    data.put("symbolicName", bundleData.symbolicName());
                    data.put("location", bundleData.location());
                    data.put("version", bundleData.version());
                    data.put("state", bundleData.state());
                    data.put("startLevel", bundleData.startLevel());
                    data.put("fragment", bundleData.fragment());
                    return data;
                  }).get();
                } catch (Exception e) {
                  log.error(STR."Error collecting bundle information: \{e.getMessage()}", e);
                  return UNAVAILABLE;
                }
              }
          ));
    }
  }

  private String fileref(File file) {
    try {
      return file != null ? file.getCanonicalPath() : null;
    }
    catch (IOException e) {
      log.error(STR."Could not get canonical path for file \{file}: \{e.getMessage()}", e);
      return null;
    }
  }

  Map<String, Object> reportFileStore(FileStore store) {
    try {
      FileStoreInfo storeInfo = new FileStoreInfo(
          store.toString(),
          store.type(),
          store.getTotalSpace(),
          store.getUsableSpace(),
          store.getUnallocatedSpace(),
          store.isReadOnly()
      );
      
      // Convert record to map for backward compatibility
      Map<String, Object> data = new HashMap<>();
      data.put("description", storeInfo.description());
      data.put("type", storeInfo.type());
      data.put("totalSpace", storeInfo.totalSpace());
      data.put("usableSpace", storeInfo.usableSpace());
      data.put("unallocatedSpace", storeInfo.unallocatedSpace());
      data.put("readOnly", storeInfo.readOnly());
      return data;
    }
    catch (IOException e) {
      log.error(STR."Could not add report to support zip for file store \{store.name()}: \{e.getMessage()}", e);
      return UNAVAILABLE;
    }
  }

  Map<String, Object> reportNetworkInterface(NetworkInterface intf) {
    try {
      NetworkInterfaceInfo interfaceInfo = new NetworkInterfaceInfo(
          intf.getDisplayName(),
          intf.isUp(),
          intf.isVirtual(),
          intf.supportsMulticast(),
          intf.isLoopback(),
          intf.isPointToPoint(),
          intf.getMTU(),
          Collections.list(intf.getInetAddresses())
              .stream()
              .map(Object::toString)
              .collect(Collectors.joining(","))
      );
      
      // Convert record to map for backward compatibility
      Map<String, Object> data = new HashMap<>();
      data.put("displayName", interfaceInfo.displayName());
      data.put("up", interfaceInfo.up());
      data.put("virtual", interfaceInfo.virtual());
      data.put("multicast", interfaceInfo.multicast());
      data.put("loopback", interfaceInfo.loopback());
      data.put("ptp", interfaceInfo.ptp());
      data.put("mtu", interfaceInfo.mtu());
      data.put("addresses", interfaceInfo.addresses());
      return data;
    }
    catch (SocketException e) {
      log.error(STR."Could not add report to support zip for network interface \{intf.getDisplayName()}: \{e.getMessage()}", e);
      return UNAVAILABLE;
    }
  }

  private Map<String, String> reportObfuscatedProperties(Properties properties) {
    Map<String, String> map = new HashMap<>();
    for (String key : properties.stringPropertyNames()) {
      map.put(key, properties.getProperty(key));
    }
    return reportObfuscatedProperties(map);
  }

  private Map<String, String> reportObfuscatedProperties(Map<String, String> properties) {
    return properties.entrySet()
        .stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
              String key = entry.getKey();
              String value = entry.getValue();
              
              // Use pattern matching for switch to handle sensitive field detection
              return switch (key.toLowerCase(Locale.US)) {
                case String k when SENSITIVE_FIELD_NAMES.stream().anyMatch(k::contains) -> Strings2.mask(value);
                case String k when SENSITIVE_CREDENTIALS_KEYS.contains(key) -> {
                  // Check if value contains any sensitive field names
                  for (String sensitiveName : SENSITIVE_FIELD_NAMES) {
                    if (value.contains(sensitiveName)) {
                      value = value.replaceAll(sensitiveName + "=\\S*", sensitiveName + "=" + MASK);
                    }
                  }
                  value;
                }
                default -> value;
              };
            }));
  }
}