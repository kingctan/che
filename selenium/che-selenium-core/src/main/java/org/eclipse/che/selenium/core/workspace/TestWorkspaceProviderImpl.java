/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.selenium.core.workspace;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.eclipse.che.api.core.model.workspace.WorkspaceStatus;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.commons.lang.concurrent.LoggingUncaughtExceptionHandler;
import org.eclipse.che.selenium.core.client.TestWorkspaceServiceClient;
import org.eclipse.che.selenium.core.configuration.ConfigurationException;
import org.eclipse.che.selenium.core.user.DefaultTestUser;
import org.eclipse.che.selenium.core.user.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TestWorkspaceProvider} implementation containing workspace pool.
 *
 * @author Anatolii Bazko
 */
@Singleton
public class TestWorkspaceProviderImpl implements TestWorkspaceProvider {
  private static final Logger LOG = LoggerFactory.getLogger(TestWorkspaceProviderImpl.class);

  private final int poolSize;
  private final ArrayBlockingQueue<TestWorkspace> testWorkspaceQueue;
  private final ScheduledExecutorService executor;
  private final DefaultTestUser defaultUser;
  private final int defaultMemoryGb;
  private final TestWorkspaceServiceClient workspaceServiceClient;

  @Inject
  public TestWorkspaceProviderImpl(
      @Named("sys.threads") int threads,
      @Named("workspace.default_memory_gb") int defaultMemoryGb,
      DefaultTestUser defaultUser,
      TestWorkspaceServiceClient workspaceServiceClient) {
    this.defaultUser = defaultUser;
    this.defaultMemoryGb = defaultMemoryGb;
    this.workspaceServiceClient = workspaceServiceClient;

    if (threads == 0) {
      throw new ConfigurationException("Threads number is 0");
    }

    this.poolSize = (threads - 1) / 2 + 1;
    this.testWorkspaceQueue = new ArrayBlockingQueue<>(poolSize);

    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("WorkspaceInitializer-%d")
                .setDaemon(true)
                .setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.getInstance())
                .build());
  }

  @Override
  public TestWorkspace createWorkspace(TestUser owner, int memoryGB, String template)
      throws Exception {
    if (hasDefaultValues(owner, memoryGB, template)) {
      return doGetWorkspaceFromPool();
    }

    return new TestWorkspaceImpl(generateName(), owner, memoryGB, template, workspaceServiceClient);
  }

  private boolean hasDefaultValues(TestUser testUser, int memoryGB, String template) {
    return memoryGB == defaultMemoryGb
        && WorkspaceTemplate.DEFAULT.equals(template)
        && testUser.getEmail().equals(defaultUser.getEmail());
  }

  private TestWorkspace doGetWorkspaceFromPool() throws Exception {
    try {
      // insure workspace is running
      TestWorkspace testWorkspace = testWorkspaceQueue.take();
      WorkspaceStatus testWorkspaceStatus =
          workspaceServiceClient.getById(testWorkspace.getId()).getStatus();

      if (testWorkspaceStatus != WorkspaceStatus.RUNNING) {
        workspaceServiceClient.start(
            testWorkspace.getId(), testWorkspace.getName(), testWorkspace.getOwner());
      }

      return testWorkspace;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Retrieving a new workspace has been interrupted.", e);
    }
  }

  @Override
  public void shutdown() {
    boolean isInterrupted = false;

    if (!executor.isShutdown()) {
      executor.shutdown();
      try {
        LOG.info("Shutdown workspace threads pool, wait 30s to stop normally");
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
          executor.shutdownNow();
          LOG.info("Interrupt workspace threads pool, wait 60s to stop");
          if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            LOG.error("Couldn't shutdown workspace threads pool");
          }
        }
      } catch (InterruptedException x) {
        isInterrupted = true;
        if (!executor.isShutdown()) {
          LOG.warn("Unable to terminate executor service");
        }
      }
      LOG.info("Workspace threads pool is terminated");
    }

    LOG.info("Destroy remained workspaces: {}.", extractWorkspaceInfo());
    testWorkspaceQueue.forEach(TestWorkspace::delete);

    if (isInterrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private List<String> extractWorkspaceInfo() {
    return testWorkspaceQueue
        .stream()
        .map(
            s -> {
              try {
                return s.getName();
              } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException("Error of getting name of workspace.", e);
              }
            })
        .collect(Collectors.toList());
  }

  @Inject
  public void initializePool(final TestWorkspaceServiceClient workspaceServiceClient) {
    LOG.info("Initialize workspace pool with {} entries.", poolSize);

    this.executor.scheduleWithFixedDelay(
        () -> {
          while (testWorkspaceQueue.remainingCapacity() != 0) {
            String name = generateName();
            TestWorkspace testWorkspace =
                new TestWorkspaceImpl(
                    name,
                    defaultUser,
                    defaultMemoryGb,
                    WorkspaceTemplate.DEFAULT,
                    workspaceServiceClient);

            try {
              if (!testWorkspaceQueue.offer(testWorkspace)) {
                LOG.warn("Workspace {} can't be added into the pool and will be destroyed.", name);
                testWorkspace.delete();
              }
            } catch (Exception e) {
              LOG.warn(
                  "Workspace {} can't be added into the pool and will be destroyed because of: {}",
                  name,
                  e.getMessage());
              testWorkspace.delete();
            }
          }
        },
        0,
        100,
        TimeUnit.MILLISECONDS);
  }

  private String generateName() {
    return NameGenerator.generate("workspace", 6);
  }
}
