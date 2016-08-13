package com.thinkbiganalytics.jobrepo.jpa;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.thinkbiganalytics.jobrepo.common.constants.FeedConstants;
import com.thinkbiganalytics.jobrepo.nifi.support.DateTimeUtil;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTO;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by sr186054 on 8/31/16.
 */
@Service
public class NifiJobExecutionProvider {

    private static final Logger log = LoggerFactory.getLogger(NifiJobExecutionProvider.class);

    public static final String NIFI_JOB_TYPE_PROPERTY = "tb.jobType";
    public static final String NIFI_FEED_PROPERTY = "feed";
    public static final String NIFI_CATEGORY_PROPERTY = "category";


    @Autowired
    private BatchExecutionContextProvider batchExecutionContextProvider;


    @Autowired
    private JPAQueryFactory factory;

    private NifiJobExecutionRepository jobExecutionRepository;

    private NifiJobInstanceRepository jobInstanceRepository;

    private NifiJobParametersRepository nifiJobParametersRepository;

    private NifiFailedEventRepository nifiFailedEventRepository;

    private NifiStepExecutionRepository nifiStepExecutionRepository;


    private boolean isWriteExecutionContext = true;


    @Autowired
    public NifiJobExecutionProvider(NifiJobExecutionRepository jobExecutionRepository, NifiJobInstanceRepository jobInstanceRepository, NifiFailedEventRepository nifiFailedEventRepository,
                                    NifiJobParametersRepository nifiJobParametersRepository,
                                    NifiStepExecutionRepository nifiStepExecutionRepository) {

        this.jobExecutionRepository = jobExecutionRepository;
        this.jobInstanceRepository = jobInstanceRepository;
        this.nifiFailedEventRepository = nifiFailedEventRepository;
        this.nifiJobParametersRepository = nifiJobParametersRepository;
        this.nifiStepExecutionRepository = nifiStepExecutionRepository;

    }

    public NifiJobInstance createJobInstance(ProvenanceEventRecordDTO event) {

        NifiJobInstance jobInstance = new NifiJobInstance();
        jobInstance.setJobKey(jobKeyGenerator(event));
        jobInstance.setJobName(event.getFeedName());
        return this.jobInstanceRepository.save(jobInstance);
    }

    private String jobKeyGenerator(ProvenanceEventRecordDTO t) {

        StringBuffer stringBuffer = new StringBuffer(t.getEventTime().getMillis() + "").append(t.getFlowFileUuid());
        MessageDigest digest1;
        try {
            digest1 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException var10) {
            throw new IllegalStateException("MD5 algorithm not available.  Fatal (should be in the JDK).");
        }

        try {
            byte[] e1 = digest1.digest(stringBuffer.toString().getBytes("UTF-8"));
            return String.format("%032x", new Object[]{new BigInteger(1, e1)});
        } catch (UnsupportedEncodingException var9) {
            throw new IllegalStateException("UTF-8 encoding not available.  Fatal (should be in the JDK).");
        }

    }


    public NifiJobExecution createJobExecution(NifiJobInstance jobInstance, ProvenanceEventRecordDTO event) {

        NifiJobExecution jobExecution = new NifiJobExecution();
        jobExecution.setJobInstance(jobInstance);
        //add in the parameters from the attributes
        jobExecution.setCreateTime(DateTimeUtil.convertToUTC(DateTime.now()));
        jobExecution.setStartTime(DateTimeUtil.convertToUTC(event.getEventTime()));
        jobExecution.setStatus(NifiJobExecution.JobStatus.STARTED);
        jobExecution.setExitCode(ExecutionConstants.ExitCode.EXECUTING);
        jobExecution.setLastUpdated(DateTimeUtil.convertToUTC(DateTime.now()));

        //create the job params
        Map<String, Object> jobParameters;
        if (event.getAttributeMap() != null) {
            jobParameters = new HashMap<>(event.getAttributeMap());
        } else {
            jobParameters = new HashMap<>();
        }
        //bootstrap the feed parameters
        jobParameters.put(FeedConstants.PARAM__FEED_NAME, event.getFeedName());
        jobParameters.put(FeedConstants.PARAM__JOB_TYPE, FeedConstants.PARAM_VALUE__JOB_TYPE_FEED);
        jobParameters.put(FeedConstants.PARAM__FEED_IS_PARENT, "true");

        //save the params
        List<NifiJobExecutionParameters> jobExecutionParametersList = new ArrayList<>();
        for (Map.Entry<String, Object> entry : jobParameters.entrySet()) {
            NifiJobExecutionParameters jobExecutionParameters = jobExecution.addParameter(entry.getKey(), entry.getValue());
            jobExecutionParametersList.add(jobExecutionParameters);
        }
        jobExecution.setJobParameters(jobExecutionParametersList);
        NifiEventJobExecution eventJobExecution = new NifiEventJobExecution(jobExecution, event.getEventId(), event.getJobFlowFileId());
        jobExecution.setNifiEventJobExecution(eventJobExecution);
        return this.jobExecutionRepository.save(jobExecution);
    }

    public NifiJobExecution createNewJobExecution(ProvenanceEventRecordDTO event) {
        NifiJobInstance jobInstance = createJobInstance(event);
        NifiJobExecution jobExecution = createJobExecution(jobInstance, event);

        return jobExecution;
    }


    public NifiStepExecution save(ProvenanceEventRecordDTO event) {
        //find the JobExecution for the event if it exists, otherwise create one
        NifiJobExecution jobExecution = jobExecutionRepository.findByEventAndFlowFile(event.getJobEventId(), event.getJobFlowFileId());
        if (jobExecution == null && event.isStartOfJob()) {
            jobExecution = createNewJobExecution(event);
        }

        if (jobExecution != null) {

            NifiStepExecution stepExecution = createStepExecution(jobExecution, event);
            if (stepExecution != null) {
                //if the attrs coming in change the type to a CHECK job then update the entity
                updateJobType(jobExecution, event);
            }
            if (event.isEndOfJob()) {

                ///END OF THE JOB... fail or complete the job?
                ensureFailureSteps(jobExecution);
                jobExecution.completeOrFailJob();
                jobExecution.setEndTime(DateTimeUtil.convertToUTC(event.getEventTime()));
                log.info("Completing JOB EXECUTION with id of: {} ff: {} ", jobExecution.getJobExecutionId(), event.getJobFlowFileId());
                //add in execution contexts
                if (isWriteExecutionContext) {
                    List<NifiJobExecutionContext> jobExecutionContextList = new ArrayList<>();
                    Map<String, String> allAttrs = event.getAttributeMap();
                    if (allAttrs != null && !allAttrs.isEmpty()) {
                        for (Map.Entry<String, String> entry : allAttrs.entrySet()) {
                            NifiJobExecutionContext executionContext = new NifiJobExecutionContext(jobExecution, entry.getKey());
                            executionContext.setStringVal(entry.getValue());
                            jobExecution.addJobExecutionContext(executionContext);
                        }
                        //also persist to spring batch tables
                        batchExecutionContextProvider.saveJobExecutionContext(jobExecution.getJobExecutionId(), allAttrs);
                    }
                }
                if (jobExecution.isFailed()) {
                    failedJob(jobExecution);
                } else if (jobExecution.isSuccess()) {
                    successfulJob(jobExecution);
                }

            }





            this.jobExecutionRepository.save(jobExecution);
        }
        return null;
    }

    private void failedJob(NifiJobExecution jobExecution) {

    }

    private void successfulJob(NifiJobExecution jobExecution) {

    }

    /**
     * We get Nifi Events after a step has executed. If a flow takes some time we might not initially get the event that the given step has failed when we write the StepExecution record. This should
     * be called when a Job Completes as it will verify all failures and then update the correct step status to reflect the failure if there is one.
     */
    private void ensureFailureSteps(NifiJobExecution jobExecution) {

        //find all the Steps for this Job that have records in the Failure table for this job flow file
        List<NifiStepExecution> stepsNeedingToBeFailed = nifiStepExecutionRepository.findStepsInJobThatNeedToBeFailed(jobExecution.getJobExecutionId());
        if (stepsNeedingToBeFailed != null) {
            for (NifiStepExecution se : stepsNeedingToBeFailed) {
                se.failStep();
            }
            //save them
            nifiStepExecutionRepository.save(stepsNeedingToBeFailed);
        }
    }


    public NifiStepExecution createStepExecution(NifiJobExecution jobExecution, ProvenanceEventRecordDTO event) {

        //only create the step if it doesnt exist yet for this event
        NifiStepExecution stepExecution = nifiStepExecutionRepository.findByProcessorAndJobFlowFile(event.getComponentId(), event.getJobFlowFileId());
        if (stepExecution == null) {
            stepExecution = new NifiStepExecution();
            stepExecution.setJobExecution(jobExecution);
            stepExecution.setStartTime(
                event.getPreviousEventTime() != null ? DateTimeUtil.convertToUTC(event.getPreviousEventTime())
                                                     : DateTimeUtil.convertToUTC((event.getEventTime().minus(event.getEventDuration()))));
            stepExecution.setEndTime(DateTimeUtil.convertToUTC(event.getEventTime()));
            //Attempt to find the Failure by looking at the event failure flag or the existence of the NifiFailedEvent in the FailedEvent table.
            // When job completes an additional pass is done to update any steps that need to be failed.
            stepExecution.setStepName(event.getComponentName());
            boolean failure = event.isFailure();
            if (!failure) {
                NifiFailedEvent failedEvent = nifiFailedEventRepository.findOne(new NifiFailedEvent.NiFiFailedEventPK(event.getEventId(), event.getFlowFileUuid()));
                failure = failedEvent != null;
            }
            if (failure) {
                stepExecution.failStep();
            } else {
                stepExecution.completeStep();
            }

            //add in execution contexts
            Map<String, String> updatedAttrs = event.getUpdatedAttributes();
            if (isWriteExecutionContext) {
                List<NifiStepExecutionContext> stepExecutionContextList = new ArrayList<>();
                if (updatedAttrs != null && !updatedAttrs.isEmpty()) {
                    for (Map.Entry<String, String> entry : updatedAttrs.entrySet()) {
                        NifiStepExecutionContext stepExecutionContext = new NifiStepExecutionContext(stepExecution, entry.getKey());
                        stepExecutionContext.setStringVal(entry.getValue());
                        stepExecutionContextList.add(stepExecutionContext);
                    }


                }
                stepExecution.setStepExecutionContext(stepExecutionContextList);


            }
            NifiEventStepExecution eventStepExecution = new NifiEventStepExecution(jobExecution, stepExecution, event.getEventId(), event.getJobFlowFileId());
            eventStepExecution.setComponentId(event.getComponentId());
            eventStepExecution.setJobFlowFileId(event.getJobFlowFileId());
            stepExecution.setNifiEventStepExecution(eventStepExecution);

            stepExecution = nifiStepExecutionRepository.save(stepExecution);
            jobExecution.getStepExecutions().add(stepExecution);
            if (isWriteExecutionContext) {
                //also persist to spring batch tables
                batchExecutionContextProvider.saveStepExecutionContext(stepExecution.getStepExecutionId(), updatedAttrs);
            }

            return stepExecution;

        } else {
            //update it
            Map<String, String> contextMap = new HashMap<>();
            String eventTimes = (event.getPreviousEventTime() != null ? DateTimeUtil.convertToUTC(event.getPreviousEventTime()).toString()
                                                                      : DateTimeUtil.convertToUTC((event.getEventTime().minus(event.getEventDuration()))).toString()) + " - " + event.getEventTime()
                                    .toString();
            contextMap.put("Event - " + event.getEventId(), eventTimes);
            for (Map.Entry<String, String> entry : contextMap.entrySet()) {
                NifiStepExecutionContext stepExecutionContext = new NifiStepExecutionContext(stepExecution, entry.getKey());
                stepExecutionContext.setStringVal(entry.getValue());
                stepExecution.addStepExecutionContext(stepExecutionContext);
            }

            stepExecution = nifiStepExecutionRepository.save(stepExecution);

            //also persist to spring batch tables
            batchExecutionContextProvider.saveStepExecutionContext(stepExecution.getStepExecutionId(), stepExecution.getStepExecutionContextAsMap());


        }
        return stepExecution;

    }


    public void updateJobType(NifiJobExecution jobExecution, ProvenanceEventRecordDTO event) {

        if (event.getUpdatedAttributes() != null && event.getUpdatedAttributes().containsKey(NIFI_JOB_TYPE_PROPERTY)) {
            String jobType = event.getUpdatedAttributes().get(NIFI_JOB_TYPE_PROPERTY);
            String nifiCategory = event.getAttributeMap().get(NIFI_CATEGORY_PROPERTY);
            String nifiFeedName = event.getAttributeMap().get(NIFI_FEED_PROPERTY);
            String feedName = nifiCategory + "." + nifiFeedName;
            if (FeedConstants.PARAM_VALUE__JOB_TYPE_CHECK.equalsIgnoreCase(jobType)) {
                jobExecution.setAsCheckDataJob(feedName);
            }
        }
    }
}