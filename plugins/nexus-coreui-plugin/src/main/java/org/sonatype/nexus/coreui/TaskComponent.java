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
package org.sonatype.nexus.coreui;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import jakarta.ws.rs.NotFoundException;

import org.sonatype.nexus.coreui.TaskXO.AdvancedSchedule;
import org.sonatype.nexus.coreui.TaskXO.OnceSchedule;
import org.sonatype.nexus.coreui.TaskXO.OnceToMonthlySchedule;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.rapture.StateContributor;
import org.sonatype.nexus.scheduling.ExternalTaskState;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskDescriptor;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.schedule.Cron;
import org.sonatype.nexus.scheduling.schedule.Daily;
import org.sonatype.nexus.scheduling.schedule.Hourly;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Monthly;
import org.sonatype.nexus.scheduling.schedule.Monthly.CalendarDay;
import org.sonatype.nexus.scheduling.schedule.Now;
import org.sonatype.nexus.scheduling.schedule.Once;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.Weekly;
import org.sonatype.nexus.scheduling.schedule.Weekly.Weekday;
import org.sonatype.nexus.validation.Validate;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toList;
import static org.sonatype.nexus.repository.date.TimeZoneUtils.shiftMonthDay;
import static org.sonatype.nexus.repository.date.TimeZoneUtils.shiftWeekDay;
import static org.sonatype.nexus.scheduling.TaskState.CANCELED;
import static org.sonatype.nexus.scheduling.TaskState.FAILED;
import static org.sonatype.nexus.scheduling.TaskState.INTERRUPTED;
import static org.sonatype.nexus.scheduling.TaskState.OK;

@Named
@Singleton
@DirectAction(action = "coreui_Task")
public class TaskComponent
    extends DirectComponentSupport
    implements StateContributor
{
  private static final String TASK_RESULT_OK = "Ok";

  private static final String TASK_RESULT_CANCELED = "Canceled";

  private static final String TASK_RESULT_ERROR = "Error";

  private static final String TASK_RESULT_INTERRUPTED = "Interrupted";

  public static final String PLAN_RECONCILIATION_TASK_ID = "blobstore.planReconciliation";

  public static final String PLAN_RECONCILIATION_TASK_OK_TEXT = " - Plan(s) is ready to run";

  private final TaskScheduler taskScheduler;

  private final Provider<Validator> validatorProvider;

  private final boolean allowCreation;
  
  /**
   * Virtual thread executor for I/O-bound operations
   */
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public TaskComponent(
      final TaskScheduler taskScheduler,
      final Provider<Validator> validatorProvider,
      @Named("${nexus.scripts.allowCreation:-false}") final boolean allowCreation)
  {
    this.taskScheduler = checkNotNull(taskScheduler);
    this.validatorProvider = checkNotNull(validatorProvider);
    this.allowCreation = allowCreation;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Gets the state for the component.
   * 
   * @return the state as a map
   */
  @Nullable
  @Override
  public Map<String, Object> getState() {
    return ImmutableMap.of("allowScriptCreation", allowCreation);
  }

  /**
   * Retrieve a list of scheduled tasks.
   * Uses virtual threads for I/O-bound operations.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:tasks:read")
  public List<TaskXO> read() {
    try {
      Future<List<TaskXO>> future = virtualThreadExecutor.submit(() -> 
          taskScheduler.listsTasks()
              .stream()
              .filter(taskInfo -> taskInfo.getConfiguration().isVisible())
              .map(this::asTaskXO)
              .collect(toList())
      );
      return future.get();
    } 
    catch (Exception e) {
      log.error("Failed to retrieve tasks", e);
      throw new RuntimeException("Failed to retrieve tasks", e);
    }
  }

  /**
   * Retrieve available task types.
   * Uses virtual threads for I/O-bound operations.
   *
   * @return a list of task types
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:tasks:read")
  public List<TaskTypeXO> readTypes() {
    try {
      Future<List<TaskTypeXO>> future = virtualThreadExecutor.submit(() ->
          taskScheduler.getTaskFactory().getDescriptors().stream()
              .map(TaskComponent::asTaskTypeXO)
              .collect(toList())
      );
      return future.get();
    }
    catch (Exception e) {
      log.error("Failed to retrieve task types", e);
      throw new RuntimeException("Failed to retrieve task types", e);
    }
  }

  /**
   * Creates a task.
   * Uses virtual threads for I/O-bound operations.
   *
   * @param taskXO to be created
   * @return created task
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:create")
  @Validate(groups = {Create.class, Default.class})
  public TaskXO create(final @NotNull @Valid TaskXO taskXO) throws Exception {
    return virtualThreadExecutor.submit(() -> {
      Schedule schedule = asSchedule(taskXO);

      TaskConfiguration taskConfiguration = taskScheduler.createTaskConfigurationInstance(taskXO.getTypeId());
      checkState(taskConfiguration.isExposed(), "This task is not allowed to be created");

      taskXO.getProperties().forEach(taskConfiguration::setString);
      taskConfiguration.setAlertEmail(taskXO.getAlertEmail());
      taskConfiguration.setNotificationCondition(taskXO.getNotificationCondition());
      taskConfiguration.setName(taskXO.getName());
      taskConfiguration.setEnabled(taskXO.getEnabled());

      TaskInfo task = scheduleTask(() -> taskScheduler.scheduleTask(taskConfiguration, schedule));
      log.debug("Created task with type '{}': {} {}", taskConfiguration.getClass(), taskConfiguration.getName(),
          taskConfiguration.getId());
      return asTaskXO(task);
    }).get();
  }

  /**
   * Updates a task.
   * Uses virtual threads for I/O-bound operations.
   *
   * @param taskXO to be updated
   * @return updated task
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:update")
  @Validate(groups = {Update.class, Default.class})
  public TaskXO update(final @NotNull @Valid TaskXO taskXO) throws Exception {
    return virtualThreadExecutor.submit(() -> {
      TaskInfo task = taskScheduler.getTaskById(taskXO.getId());
      validateState(taskXO.getId(), task);
      if ("script".equals(task.getTypeId())) {
        validateScriptUpdate(task, taskXO);
      }
      Schedule schedule = asSchedule(taskXO);
      TaskConfiguration taskConfiguration = taskScheduler.createTaskConfigurationInstance(taskXO.getTypeId());
      taskConfiguration.apply(task.getConfiguration());
      taskConfiguration.setEnabled(taskXO.getEnabled());
      taskConfiguration.setName(taskXO.getName());
      taskConfiguration.setAlertEmail(taskXO.getAlertEmail());
      taskConfiguration.setNotificationCondition(taskXO.getNotificationCondition());
      taskXO.getProperties().forEach(taskConfiguration::setString);

      TaskInfo updatedTask = scheduleTask(() -> taskScheduler.scheduleTask(taskConfiguration, schedule));

      return asTaskXO(updatedTask);
    }).get();
  }

  /**
   * Removes a task.
   * Uses virtual threads for I/O-bound operations.
   *
   * @param id of the task to be removed
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:delete")
  @Validate
  public void remove(final @NotEmpty String id) {
    try {
      virtualThreadExecutor.submit(() -> {
        TaskInfo taskInfo = taskScheduler.getTaskById(id);
        if (taskInfo != null) {
          taskInfo.remove();
        }
        return null;
      }).get();
    }
    catch (Exception e) {
      log.error("Failed to remove task with id: {}", id, e);
      throw new RuntimeException("Failed to remove task", e);
    }
  }

  /**
   * Runs a task.
   * Uses virtual threads for I/O-bound operations.
   *
   * @param id of the task to be run
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:start")
  @Validate
  public void run(final @NotEmpty String id) throws Exception {
    virtualThreadExecutor.submit(() -> {
      TaskInfo taskInfo = taskScheduler.getTaskById(id);
      if (taskInfo != null) {
        taskInfo.runNow();
      }
      return null;
    }).get();
  }

  /**
   * Stops a task.
   * Uses virtual threads for I/O-bound operations.
   *
   * @param id of the task to be stopped
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:stop")
  @Validate
  public void stop(final @NotEmpty String id) {
    try {
      virtualThreadExecutor.submit(() -> {
        taskScheduler.cancel(id, false);
        return null;
      }).get();
    }
    catch (Exception e) {
      log.error("Failed to stop task with id: {}", id, e);
      throw new RuntimeException("Failed to stop task", e);
    }
  }

  /**
   * Converts a TaskInfo to a TaskXO using pattern matching for switch.
   * 
   * @param taskInfo the task info to convert
   * @return the converted TaskXO
   */
  private TaskXO asTaskXO(final TaskInfo taskInfo) {
    ExternalTaskState externalTaskState = taskScheduler.toExternalTaskState(taskInfo);
    TaskState taskState = externalTaskState.getState();
    TaskState endTaskState = externalTaskState.getLastEndState();
    Date lastRun = externalTaskState.getLastRunStarted();
    Long runDuration = externalTaskState.getLastRunDuration();

    TaskConfiguration configuration = taskInfo.getConfiguration();
    TaskXO result = new TaskXO();
    result.setId(taskInfo.getId());
    result.setEnabled(configuration.isEnabled());
    result.setName(taskInfo.getName());
    result.setTypeId(configuration.getTypeId());
    result.setTypeName(configuration.getTypeName());
    result.setStatus(taskState.name());
    String statusDescription = configuration.isEnabled() ? taskState.getDescription() : "Disabled";
    if (taskInfo.getCurrentState().getState().isRunning() && configuration.getProgress() != null) {
      statusDescription += ": " + configuration.getProgress();
    }
    result.setStatusDescription(statusDescription);
    result.setSchedule(getSchedule(taskInfo.getSchedule()));
    result.setLastRun(lastRun);
    result.setLastRunResult(getLastRunResult(taskInfo, endTaskState, runDuration));
    result.setNextRun(externalTaskState.getNextFireTime());
    result.setRunnable(taskState.isWaiting());
    result.setStoppable(taskState.isRunning());
    result.setAlertEmail(configuration.getAlertEmail());
    result.setNotificationCondition(configuration.getNotificationCondition());
    result.setProperties(configuration.asMap());

    // Use pattern matching for switch to handle different schedule types
    Schedule schedule = taskInfo.getSchedule();
    switch (schedule) {
      case Once o -> {
        result.setStartDate(o.getStartAt());
      }
      case Hourly h -> {
        result.setStartDate(h.getStartAt());
      }
      case Daily d -> {
        result.setStartDate(d.getStartAt());
      }
      case Weekly w -> {
        result.setStartDate(w.getStartAt());
        // expects integers with 1=SUN, 2=MON, etc...
        result.setRecurringDays(
            w.getDaysToRun()
                .stream()
                .map(dayToRun -> dayToRun.ordinal() + 1)
                .collect(toList())
                .toArray(new Integer[]{}));
      }
      case Monthly m -> {
        result.setStartDate(m.getStartAt());
        // expects ints, with 999 being the lastDayOfMonth
        result.setRecurringDays(m.getDaysToRun()
            .stream()
            .map(dayToRun -> dayToRun.isLastDayOfMonth() ? 999 : dayToRun.getDay())
            .collect(toList())
            .toArray(new Integer[]{}));
      }
      case Cron c -> {
        result.setStartDate(c.getStartAt());
        result.setCronExpression(c.getCronExpression());
      }
      default -> { /* No additional processing needed */ }
    }
    
    result.setIsReadOnlyUi(configuration.getBoolean(".readOnlyUi", false));
    return result;
  }

  /**
   * Converts a TaskXO to a Schedule using pattern matching for switch.
   * 
   * @param taskXO the task XO to convert
   * @return the converted Schedule
   */
  private Schedule asSchedule(final TaskXO taskXO) {
    // Handle advanced schedule type
    if ("advanced".equals(taskXO.getSchedule())) {
      ZoneOffset clientZoneOffset = ZoneOffset.of(taskXO.getTimeZoneOffset());
      validatorProvider.get().validate(taskXO, AdvancedSchedule.class);
      return taskScheduler.getScheduleFactory().cron(new Date(), taskXO.getCronExpression(), clientZoneOffset.getId());
    }
    
    // Handle manual schedule type
    if ("manual".equals(taskXO.getSchedule())) {
      return taskScheduler.getScheduleFactory().manual();
    }
    
    // Handle other schedule types
    if (taskXO.getStartDate() == null) {
      validatorProvider.get().validate(taskXO, OnceToMonthlySchedule.class);
    }
    
    ZoneOffset clientZoneOffset = ZoneOffset.of(taskXO.getTimeZoneOffset());
    LocalDateTime startDateClient =
        LocalDateTime.ofInstant(taskXO.getStartDate().toInstant(), ZoneId.of(clientZoneOffset.getId()));
    LocalDateTime startDateServer =
        LocalDateTime.ofInstant(taskXO.getStartDate().toInstant(), ZoneId.systemDefault());
    Calendar date = Calendar.getInstance();
    date.setTimeInMillis(taskXO.getStartDate().getTime());
    date.set(Calendar.SECOND, 0);
    date.set(Calendar.MILLISECOND, 0);
    
    // Use pattern matching for switch to handle different schedule types
    return switch (taskXO.getSchedule()) {
      case "once" -> {
        validatorProvider.get().validate(taskXO, OnceSchedule.class);
        yield taskScheduler.getScheduleFactory().once(date.getTime());
      }
      case "hourly" -> taskScheduler.getScheduleFactory().hourly(date.getTime());
      case "daily" -> taskScheduler.getScheduleFactory().daily(date.getTime());
      case "weekly" -> taskScheduler.getScheduleFactory()
          .weekly(date.getTime(), taskXO.getRecurringDays().stream()
              .map(recurringDay -> Weekday.values()[shiftWeekDay(recurringDay - 1, startDateClient,
                  startDateServer)])
              .collect(Collectors.toSet()));
      case "monthly" -> taskScheduler.getScheduleFactory()
          .monthly(date.getTime(), taskXO.getRecurringDays().stream()
              .map(recurringDay -> recurringDay == 999
                  ? CalendarDay.lastDay()
                  : CalendarDay.day(
                      shiftMonthDay(recurringDay, startDateClient, startDateServer)))
              .collect(Collectors.toSet()));
      default -> taskScheduler.getScheduleFactory().manual();
    };
  }

  @VisibleForTesting
  void validateState(final String taskId, final TaskInfo taskInfo) {
    if (taskInfo == null) {
      throw new NotFoundException(String.format("Task with id '%s' not found", taskId));
    }
    ExternalTaskState externalTaskState = taskScheduler.toExternalTaskState(taskInfo);
    if (externalTaskState.getState().isRunning()) {
      throw new IllegalStateException(
          "Task can not be edited while it is being executed or it is in line to be executed");
    }
  }

  @VisibleForTesting
  void validateScriptUpdate(final TaskInfo task, final TaskXO update) {
    String originalSource = task.getConfiguration().getString("source");
    String updateSource = update.getProperties().get("source");

    if (!allowCreation && originalSource != null && !originalSource.equals(updateSource)) {
      throw new IllegalStateException("Script source updates are not allowed");
    }
  }

  /**
   * Handle parsing errors at the quartz level, which include logically incorrect settings in addition to the purely
   * syntactic validations (regex) we already apply.
   */
  private TaskInfo scheduleTask(Callable<TaskInfo> callable) throws Exception {
    try {
      return callable.call();
    }
    catch (Exception e) {
      log.error("Failed to schedule task", e);
      throw e;
    }
  }

  /**
   * Gets the schedule type as a string using pattern matching for switch.
   * 
   * @param schedule the schedule to get the type for
   * @return the schedule type as a string
   */
  private static String getSchedule(final Schedule schedule) {
    return switch (schedule) {
      case Manual m -> "manual";
      case Now n -> "internal";
      case Once o -> "once";
      case Hourly h -> "hourly";
      case Daily d -> "daily";
      case Weekly w -> "weekly";
      case Monthly m -> "monthly";
      case Cron c -> "advanced";
      default -> schedule.getClass().getName();
    };
  }

  /**
   * Gets the last run result as a string using pattern matching for switch.
   * 
   * @param taskInfo the task info
   * @param endState the end state of the task
   * @param runDuration the duration of the run
   * @return the last run result as a string
   */
  private static String getLastRunResult(final TaskInfo taskInfo, final TaskState endState, final Long runDuration) {
    StringBuilder lastRunResult = new StringBuilder();

    if (endState != null) {
      // Use pattern matching for switch to determine the result text
      switch (endState) {
        case OK -> lastRunResult.append(TASK_RESULT_OK);
        case CANCELED -> lastRunResult.append(TASK_RESULT_CANCELED);
        case FAILED -> lastRunResult.append(TASK_RESULT_ERROR);
        case INTERRUPTED -> lastRunResult.append(TASK_RESULT_INTERRUPTED);
        default -> lastRunResult.append(endState.name());
      }

      if (runDuration != null) {
        long milliseconds = runDuration;

        int hours = (int) ((milliseconds / 1000) / 3600);
        int minutes = (int) ((milliseconds / 1000) / 60 - hours * 60);
        int seconds = (int) ((milliseconds / 1000) % 60);

        lastRunResult.append(" [");
        if (hours != 0) {
          lastRunResult.append(hours).append("h");
        }
        if (minutes != 0 || hours != 0) {
          lastRunResult.append(minutes).append("m");
        }
        lastRunResult.append(seconds).append("s]");
      }

      appendPlanReconciliationText(lastRunResult, endState, taskInfo);
    }
    return lastRunResult.toString();
  }

  /**
   * Appends plan reconciliation text to the last run result if applicable.
   * 
   * @param lastRunResult the last run result to append to
   * @param endState the end state of the task
   * @param taskInfo the task info
   */
  private static void appendPlanReconciliationText(StringBuilder lastRunResult, TaskState endState, TaskInfo taskInfo) {
    // Use pattern matching with a guarded pattern to check conditions
    if (endState == OK && taskInfo.getTypeId().equals(PLAN_RECONCILIATION_TASK_ID)) {
      lastRunResult.append(PLAN_RECONCILIATION_TASK_OK_TEXT);
    }
  }

  private static TaskTypeXO asTaskTypeXO(final TaskDescriptor taskDescriptor) {
    TaskTypeXO taskTypeXO = new TaskTypeXO(taskDescriptor.getId()
    		, taskDescriptor.getName()
    		, taskDescriptor.isExposed()
    		, taskDescriptor.allowConcurrentRun()
    		, taskDescriptor.getFormFields() != null ? taskDescriptor.getFormFields().stream().map(FormFieldXO::create).collect(toList()) : null
    		);

    return taskTypeXO;
  }
}
