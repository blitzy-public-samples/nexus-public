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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;

import org.sonatype.nexus.coreui.search.BrowseableFormatXO;
import org.sonatype.nexus.coreui.service.RepositoryUiService;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.extdirect.model.StoreLoadParameters;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.validation.Validate;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import com.softwarementors.extjs.djn.config.annotations.DirectPollMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Repository {@link DirectComponent}.
 */
@Named
@Singleton
@DirectAction(action = "coreui_Repository")
public class RepositoryComponent
    extends DirectComponentSupport
{
  private final RepositoryUiService repositoryUiService;
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public RepositoryComponent(final RepositoryUiService repositoryUiService) {
    this.repositoryUiService = checkNotNull(repositoryUiService);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<RepositoryXO> read() {
    return virtualThreadExecutor.submit(() -> repositoryUiService.read()).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<ReferenceXO> readRecipes() {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readRecipes()).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<Format> readFormats() {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readFormats()).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<BrowseableFormatXO> getBrowseableFormats() {
    return virtualThreadExecutor.submit(() -> repositoryUiService.getBrowseableFormats()).join();
  }

  /**
   * Retrieve a list of available repositories references.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<RepositoryReferenceXO> readReferences(@Nullable final StoreLoadParameters parameters) {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readReferences(parameters)).join();
  }

  /**
   * Retrieve a list of available repositories references + add an entry for all repositories '*".
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<RepositoryReferenceXO> readReferencesAddingEntryForAll(@Nullable final StoreLoadParameters parameters) {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readReferencesAddingEntryForAll(parameters)).join();
  }

  /**
   * Retrieve a list of available repositories references + add an entry for all repositories '*' and an entry for
   * format 'All (format) repositories' '*(format)'".
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<RepositoryReferenceXO> readReferencesAddingEntriesForAllFormats(
      @Nullable final StoreLoadParameters parameters)
  {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readReferencesAddingEntriesForAllFormats(parameters)).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @Validate(groups = {Create.class, Default.class})
  public RepositoryXO create(@NotNull @Valid final RepositoryXO repositoryXO) throws Exception {
    return virtualThreadExecutor.submit(() -> repositoryUiService.create(repositoryXO)).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @Validate(groups = {Update.class, Default.class})
  public RepositoryXO update(@NotNull @Valid final RepositoryXO repositoryXO) throws Exception {
    return virtualThreadExecutor.submit(() -> repositoryUiService.update(repositoryXO)).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @Validate
  public void remove(@NotEmpty final String name) throws Exception {
    virtualThreadExecutor.submit(() -> {
      repositoryUiService.remove(name);
      return null;
    }).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @Validate
  public String rebuildIndex(@NotEmpty final String name) {
    return virtualThreadExecutor.submit(() -> repositoryUiService.rebuildIndex(name)).join();
  }

  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @Validate
  public void invalidateCache(@NotEmpty final String name) {
    virtualThreadExecutor.submit(() -> {
      repositoryUiService.invalidateCache(name);
      return null;
    }).join();
  }

  @Timed
  @ExceptionMetered
  @DirectPollMethod(event = "coreui_Repository_readStatus")
  @RequiresAuthentication
  public List<RepositoryStatusXO> readStatus(final Map<String, String> params) {
    return virtualThreadExecutor.submit(() -> repositoryUiService.readStatus(params)).join();
  }

  public void addRecipe(String format, Recipe recipe) {
    switch (recipe) {
      case Recipe r when format != null -> repositoryUiService.addRecipe(format, r);
      default -> throw new IllegalArgumentException("Format cannot be null");
    }
  }

  public RepositoryUiService getRepositoryUiService() {
    return repositoryUiService;
  }
}