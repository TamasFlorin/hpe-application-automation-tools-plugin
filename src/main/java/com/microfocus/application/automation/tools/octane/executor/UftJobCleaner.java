/*
 *
 *  Certain versions of software and/or documents (“Material”) accessible here may contain branding from
 *  Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 *  the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 *  and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 *  marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * © Copyright 2012-2018 Micro Focus or one of its affiliates.
 *
 * The only warranties for products and services of Micro Focus and its affiliates
 * and licensors (“Micro Focus”) are set forth in the express warranty statements
 * accompanying such products and services. Nothing herein should be construed as
 * constituting an additional warranty. Micro Focus shall not be liable for technical
 * or editorial errors or omissions contained herein.
 * The information contained herein is subject to change without notice.
 * ___________________________________________________________________
 *
 */

package com.microfocus.application.automation.tools.octane.executor;

import com.hp.octane.integrations.OctaneSDK;
import com.hp.octane.integrations.dto.entities.Entity;
import com.hp.octane.integrations.dto.entities.EntityConstants;
import com.hp.octane.integrations.services.entities.EntitiesService;
import com.microfocus.application.automation.tools.octane.actions.UFTTestDetectionPublisher;
import com.microfocus.application.automation.tools.octane.tests.AbstractSafeLoggingAsyncPeriodWork;
import hudson.Extension;
import hudson.model.*;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;


/**
 * This class cleans outdated test execution jobs that were not used more than 7 days.
 * Only jobs that were created by {@link TestExecutionJobCreatorService} are checked.
 */
@Extension
public class UftJobCleaner extends AbstractSafeLoggingAsyncPeriodWork {

    private static Logger logger = LogManager.getLogger(UftJobCleaner.class);

    public UftJobCleaner() {
        super("Uft Job Cleaner");
        logger.warn(String.format("Initial delay %d minutes, recurrencePeriod %d minutes, outdate threshold %d days", getInitialDelay() / MIN, getRecurrencePeriod() / MIN, getOutdateThreshold()));
    }

    @Override
    public long getRecurrencePeriod() {
        return DAY;
    }

    @Override
    public long getInitialDelay() {
        return MIN * 10;//start 10 minutes after jenkins restart
    }

    private long getOutdateThreshold() {
        int DEFAULT_OUTDATE_THRESHOLD = 7;
        int threshold = DEFAULT_OUTDATE_THRESHOLD;
        String paramValue = System.getProperty("octane.plugin.UftJobCleaner.outdateThreshold");
        if (StringUtils.isNotEmpty(paramValue)) {
            try {
                threshold = Integer.parseInt(paramValue);
            } catch (NumberFormatException e) {
                threshold = DEFAULT_OUTDATE_THRESHOLD;
            }
        }
        return threshold;
    }

    @Override
    protected void doExecute(TaskListener listener) throws IOException, InterruptedException {
        List<FreeStyleProject> jobs = Jenkins.getInstance().getAllItems(FreeStyleProject.class);

        clearExecutionJobs(jobs);
        //clearDiscoveryJobs(jobs); temporary disabled
    }

    private void clearExecutionJobs(List<FreeStyleProject> jobs) {
        long thresholdTimeInMillis = new Date().getTime() - PeriodicWork.DAY * getOutdateThreshold();
        int clearCounter = 0;
        for (FreeStyleProject job : jobs) {
            if (UftJobRecognizer.isExecutorJob(job) && job.getLastBuild() != null && !job.isBuilding()) {
                if (thresholdTimeInMillis > job.getLastBuild().getTimeInMillis()) {
                    try {
                        logger.warn(String.format("Job %s is going to be deleted as outdated job, last build was executed at %s", job.getName(), job.getLastBuild().getTimestampString2()));
                        job.delete();
                    } catch (Exception e) {
                        logger.warn(String.format("Failed to delete job %s : %s", job.getName(), e.getMessage()));
                    }

                    clearCounter++;
                }
            }
        }

        logger.warn(String.format("Cleaner found %s outdated execution job", clearCounter));
    }

    private void clearDiscoveryJobs(List<FreeStyleProject> jobs) {

        //Generally, after deleting executor in Octane, relevant job in Jenkins is also deleted. But if jenkins was down during delete of executor, job remains
        //This method handle orphan job that doesn't hava matching executors in Octane
        //1. build map of discovery jobs per workspace
        //2. Loop by workspace
        //2.1 Get from octane executors in workspace
        //2.2 If some discovery job exist that doesn't have matching executor - remove it from Jenkins

        Map<Long, Map<String, FreeStyleProject>> workspace2executorLogical2DiscoveryJobMap = new HashMap<>();
        for (FreeStyleProject job : jobs) {
            if (UftJobRecognizer.isDiscoveryJob(job)) {
                String executorLogicalName = UftJobRecognizer.getExecutorLogicalName(job);
                Long workspaceId = getOctaneWorkspaceId(job);
                if (executorLogicalName != null && workspaceId != null) {
                    if (!workspace2executorLogical2DiscoveryJobMap.containsKey(workspaceId)) {
                        workspace2executorLogical2DiscoveryJobMap.put(workspaceId, new HashMap<>());
                    }
                    workspace2executorLogical2DiscoveryJobMap.get(workspaceId).put(executorLogicalName, job);
                }
            }
        }

        //TOO not working correctly
        if (!workspace2executorLogical2DiscoveryJobMap.isEmpty()) {
            if (OctaneSDK.getClients().get(0).getConfigurationService().isCurrentConfigurationValid()) {
                EntitiesService entitiesService = OctaneSDK.getClients().get(0).getEntitiesService();
                int deleteCounter = 0;
                for (Long workspaceId : workspace2executorLogical2DiscoveryJobMap.keySet()) {
                    try {
                        Map<String, FreeStyleProject> discoveryJobs = workspace2executorLogical2DiscoveryJobMap.get(workspaceId);
                        Set<String> octaneExecutorsLogicalNames = getOctaneExecutorsLogicalNames(entitiesService, workspaceId);
                        for (String jobExecutorLogical : discoveryJobs.keySet()) {
                            boolean isExistInOctane = octaneExecutorsLogicalNames.contains(jobExecutorLogical);
                            if (!isExistInOctane) {
                                //found discovery job that is not related to any executor in Octane
                                FreeStyleProject job = discoveryJobs.get(jobExecutorLogical);
                                try {
                                    logger.warn(String.format("Job %s is going to be deleted as is doesn't have matching executor in Octane in workspace %s", job.getName(), workspaceId));
                                    deleteCounter++;
                                    job.delete();
                                } catch (Exception e) {
                                    logger.warn(String.format("Failed to delete job %s : %s", job.getName(), e.getMessage()));
                                }

                            }
                        }
                    } catch (Exception e) {
                        //on exception - do nothing and skip to next workspace
                        logger.warn(String.format("Failed to get executors from workspace %s : %s", workspaceId, e.getMessage()));
                        continue;
                    }
                }
                logger.warn(String.format("Cleaner found %s not-related discovery job", deleteCounter));
            }
        }
    }

    private Set<String> getOctaneExecutorsLogicalNames( EntitiesService entitiesService, Long workspaceId) {
        List<Entity> entities = entitiesService.getEntities(workspaceId, EntityConstants.Executors.COLLECTION_NAME, null,
                Arrays.asList(EntityConstants.Executors.ID_FIELD, EntityConstants.Executors.LOGICAL_NAME_FIELD));
        Set<String> octaneExecutorIds = new HashSet<>();
        for (Entity executor : entities) {
            octaneExecutorIds.add(executor.getStringValue(EntityConstants.Executors.LOGICAL_NAME_FIELD));
        }
        return octaneExecutorIds;
    }


    private Long getOctaneWorkspaceId(FreeStyleProject job) {

        UFTTestDetectionPublisher uftTestDetectionPublisher;
        List publishers = job.getPublishersList();
        for (Object publisher : publishers) {
            if (publisher instanceof UFTTestDetectionPublisher) {
                uftTestDetectionPublisher = (UFTTestDetectionPublisher) publisher;
                String workspaceId = uftTestDetectionPublisher.getWorkspaceName();
                return Long.valueOf(workspaceId);
            }
        }

        return null;
    }

    /**
     * Delete discovery job that related to specific executor in Octane
     *
     * @param executorToDelete
     */
    public static void deleteDiscoveryJobByExecutor(String executorToDelete) {

        List<FreeStyleProject> jobs = Jenkins.getInstance().getAllItems(FreeStyleProject.class);
        for (FreeStyleProject proj : jobs) {
            if (UftJobRecognizer.isDiscoveryJob(proj)) {
                String executorId = UftJobRecognizer.getExecutorId(proj);
                String executorLogicalName = UftJobRecognizer.getExecutorLogicalName(proj);
                if ((StringUtils.isNotEmpty(executorId) && executorId.equals(executorToDelete)) ||
                        (StringUtils.isNotEmpty(executorLogicalName) && executorLogicalName.equals(executorToDelete))) {
                    boolean waitBeforeDelete = false;

                    if (proj.isBuilding()) {
                        proj.getLastBuild().getExecutor().interrupt();
                        waitBeforeDelete = true;
                    } else if (proj.isInQueue()) {
                        Jenkins.getInstance().getQueue().cancel(proj);
                        waitBeforeDelete = true;
                    }

                    if (waitBeforeDelete) {
                        try {
                            //we cancelled building/queue - wait before deleting the job, so Jenkins will be able to complete some IO actions
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            //do nothing
                        }
                    }

                    try {
                        logger.warn(String.format("Job '%s' is going to be deleted since matching executor in Octane was deleted", proj.getName()));
                        proj.delete();
                    } catch (IOException | InterruptedException e) {
                        logger.error("Failed to delete job  " + proj.getName() + " : " + e.getMessage());
                    }
                }
            }
        }
    }
}
