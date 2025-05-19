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
package org.sonatype.nexus.repository.content.store.internal;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.repository.RepositoryStartedEvent;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.events.TaskDeletedEvent;
import org.sonatype.nexus.scheduling.schedule.Schedule;

import com.google.common.collect.ImmutableMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.DATA_STORE_NAME;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTask.CRON_SCHEDULE;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTaskDescriptor.CONTENT_STORE_FIELD_ID;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTaskDescriptor.FORMAT_FIELD_ID;
import static org.sonatype.nexus.repository.content.store.internal.AssetBlobCleanupTaskDescriptor.TYPE_ID;

/**
 * Manager for scheduling asset blob cleanup tasks.
 * 
 * @since 3.24
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named
@ManagedLifecycle(phase = TASKS)
@Singleton
public class AssetBlobCleanupTaskManager
    extends LifecycleSupport
    implements EventAware, EventAware.Asynchronous
{
  private final TaskScheduler taskScheduler;

  // Using ConcurrentHashMap for better performance with Virtual Threads
  private final Map<String, Map<String, Boolean>> activeFormatStores = new ConcurrentHashMap<>();

  @Inject
  public AssetBlobCleanupTaskManager(final TaskScheduler taskScheduler) {
    this.taskScheduler = checkNotNull(taskScheduler);
  }

  /**
   * Handle repository started events to schedule cleanup tasks as needed.
   * 
   * @param event the repository started event
   */
  @Subscribe
  public void on(final RepositoryStartedEvent event) {
    String format = event.getRepository().getFormat().getValue();

    Configuration repositoryConfiguration = event.getRepository().getConfiguration();
    NestedAttributesMap storageAttributes = repositoryConfiguration.attributes(STORAGE);
    String contentStore = (String) storageAttributes.get(DATA_STORE_NAME, DEFAULT_DATASTORE_NAME);

    // Using ConcurrentHashMap's computeIfAbsent for thread-safe initialization
    boolean isNew = activeFormatStores.computeIfAbsent(format, k -> new ConcurrentHashMap<>())
        .putIfAbsent(contentStore, Boolean.TRUE) == null;

    if (isNew && isStarted()) {
      scheduleAssetBlobCleanupTask(format, contentStore);
    }
  }

  /**
   * Handle task deleted events to update our tracking of active format stores.
   * 
   * @param event the task deleted event
   */
  @Subscribe
  public void on(final TaskDeletedEvent event) {
    TaskInfo taskInfo = event.getTaskInfo();
    if (TYPE_ID.equals(taskInfo.getTypeId())) {
      TaskConfiguration taskConfiguration = taskInfo.getConfiguration();
      String format = taskConfiguration.getString(FORMAT_FIELD_ID);
      String contentStore = taskConfiguration.getString(CONTENT_STORE_FIELD_ID);
      
      // Thread-safe removal using ConcurrentHashMap
      Map<String, Boolean> stores = activeFormatStores.get(format);
      if (stores != null) {
        stores.remove(contentStore);
        // Clean up empty maps to prevent memory leaks
        if (stores.isEmpty()) {
          activeFormatStores.remove(format);
        }
      }
    }
  }

  @Override
  protected void doStart() throws Exception {
    // Schedule cleanup tasks for all active format stores
    activeFormatStores.forEach((format, stores) -> 
        stores.keySet().forEach(contentStore -> scheduleAssetBlobCleanupTask(format, contentStore)));
  }

  /**
   * Schedule an asset blob cleanup task for the given format and content store.
   * 
   * @param format the repository format
   * @param contentStore the content store name
   */
  private void scheduleAssetBlobCleanupTask(final String format, final String contentStore) {
    Map<String, String> settings = ImmutableMap.of(FORMAT_FIELD_ID, format, CONTENT_STORE_FIELD_ID, contentStore);
    if (taskScheduler.getTaskByTypeId(TYPE_ID, settings) == null) {
      TaskConfiguration taskConfiguration = taskScheduler.createTaskConfigurationInstance(TYPE_ID);
      taskConfiguration.setName("Cleanup unused " + format + " blobs from " + contentStore);
      taskConfiguration.setString(FORMAT_FIELD_ID, format);
      taskConfiguration.setString(CONTENT_STORE_FIELD_ID, contentStore);
      Schedule schedule = taskScheduler.getScheduleFactory().cron(new Date(), CRON_SCHEDULE);
      log.info("Scheduling cleanup of unused {} blobs from {}", format, contentStore);
      taskScheduler.scheduleTask(taskConfiguration, schedule);
    }
  }
}